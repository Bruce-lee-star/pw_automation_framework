package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link AssetRepository} 的**文件型 JSON** 实现（设计 §3.1.3 资产层 / P1.5 剩余项
 * 「资产本体 JSON + 元数据索引存储」）。
 *
 * <p><b>存储布局</b>（仓库根目录 {@code root}）：</p>
 * <ul>
 *   <li>{@code assets/asset-<seq>.json} —— 单个资产<b>本体</b>，{@code contentJson} 以
 *       嵌套 JSON 对象（{@code content} 字段）落盘，而非双重转义字符串，便于 codegen / P3 直接消费；</li>
 *   <li>{@code _index.json} —— <b>元数据索引</b>：{@code seq}（下一个序号）、{@code assets}
 *       （每个资产的 id/文件/类型/需求/状态/更新时间轻量元数据）、{@code byRequirement}
 *       （需求 id → 资产 id 列表，支撑 {@link #queryByRequirement} 免全量扫描）。</li>
 * </ul>
 *
 * <p><b>生命周期</b>：构造时从 {@code _index.json} 重建内存视图，故进程重启后资产与状态不丢失；
 * 所有写操作先原子写临时文件再 rename，避免写入中途崩溃导致本体/索引损坏。状态迁移严格委托
 * {@link AssetStateMachine.DefaultAssetStateMachine}，与 {@link InMemoryAssetRepository} 行为一致。</p>
 *
 * <p>线程安全：所有变更方法 {@code synchronized}，单进程内串行化读写。</p>
 */
public final class JsonFileAssetRepository implements AssetRepository {

    private static final Logger LOG = LoggerFactory.getLogger(JsonFileAssetRepository.class);
    private static final String INDEX_FILE = "_index.json";
    private static final String ASSETS_DIR = "assets";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path root;
    private final AssetStateMachine stateMachine = new AssetStateMachine.DefaultAssetStateMachine();

    private final Map<AssetId, StoredAsset> store = new ConcurrentHashMap<>();
    /** 序号本身用原子量承载：loadIndex（构造期）与 writeIndex 的读取不再依赖方法级锁，消除锁不一致。 */
    private final AtomicLong seq = new AtomicLong(1);

    public JsonFileAssetRepository(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root.resolve(ASSETS_DIR));
            loadIndex();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialize JsonFileAssetRepository at " + root, e);
        }
    }

    @Override
    public synchronized AssetId save(AssetDraft draft) {
        long n = seq.getAndIncrement();
        AssetId id = new AssetId("asset:" + draft.type().name().toLowerCase() + ":" + n, 1);
        long now = System.currentTimeMillis();
        StoredAsset asset = new StoredAsset(id, draft.type(), draft.requirementId(), AssetState.DRAFT,
                draft.provenance(), draft.contentJson(), null, now, now, null);
        store.put(id, asset);
        persist(asset);
        LOG.debug("Asset saved as DRAFT: {}", id);
        return id;
    }

    @Override
    public synchronized StoredAsset submit(AssetId id, String actor) {
        return transition(id, AssetStateMachine.Event.SUBMIT, actor);
    }

    @Override
    public synchronized StoredAsset approve(AssetId id, String approver) {
        return transition(id, AssetStateMachine.Event.APPROVE, approver);
    }

    @Override
    public synchronized StoredAsset activate(AssetId id) {
        return transition(id, AssetStateMachine.Event.ACTIVATE, "system");
    }

    @Override
    public synchronized StoredAsset deprecate(AssetId id, String reason) {
        return transition(id, AssetStateMachine.Event.DEPRECATE, "system:" + reason);
    }

    @Override
    public List<StoredAsset> queryByRequirement(String requirementId) {
        List<StoredAsset> result = new ArrayList<>();
        for (StoredAsset a : store.values()) {
            if (requirementId.equals(a.requirementId())) {
                result.add(a);
            }
        }
        return result;
    }

    @Override
    public StoredAsset get(AssetId id) {
        return store.get(id);
    }

    // ---- 内部：状态迁移 ----

    private StoredAsset transition(AssetId id, AssetStateMachine.Event event, String actor) {
        StoredAsset current = store.get(id);
        if (current == null) {
            throw new IllegalArgumentException("Unknown asset: " + id);
        }
        AssetState next = stateMachine.transition(current.state(), event);
        // T8 审批绑定：审批通过时经单一来源记录 STEP_PLAN 的内容指纹；其它迁移沿用既有绑定。
        String approvedDigest = (event == AssetStateMachine.Event.APPROVE)
                ? StepPlanApprovalBinding.digestOf(current.type(), current.contentJson())
                : current.approvedDigest();
        StoredAsset updated = new StoredAsset(id, current.type(), current.requirementId(), next,
                current.provenance(), current.contentJson(), current.report(), current.createdAt(),
                System.currentTimeMillis(), approvedDigest);
        store.put(id, updated);
        persist(updated);
        LOG.info("Asset {} transition {} -> {} by {}", id, current.state(), next, actor);
        return updated;
    }

    @Override
    public synchronized StoredAsset recordVerification(AssetId id, VerificationReport report) {
        StoredAsset current = store.get(id);
        if (current == null) {
            throw new IllegalArgumentException("Unknown asset: " + id);
        }
        StoredAsset updated = new StoredAsset(id, current.type(), current.requirementId(), current.state(),
                current.provenance(), current.contentJson(), report, current.createdAt(),
                System.currentTimeMillis(), current.approvedDigest());
        store.put(id, updated);
        persist(updated);
        LOG.info("Asset {} verification recorded (status={}, stability={})", id, report.status(), report.stability());
        return updated;
    }

    // ---- 持久化 ----

    private String fileNameFor(AssetId id) {
        // id 形如 "asset:linkage:3@v1" -> "asset-linkage-3-v1.json"（由 id 派生，确定性、唯一）
        String core = id.toString().replace(':', '-').replace('@', '-');
        return core + ".json";
    }

    private void persist(StoredAsset asset) {
        try {
            Path assetFile = root.resolve(ASSETS_DIR).resolve(fileNameFor(asset.id()));
            ObjectNode node = toJson(asset);
            atomicWrite(assetFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            writeIndex();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist asset " + asset.id(), e);
        }
    }

    private ObjectNode toJson(StoredAsset asset) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", asset.id().id());
        node.put("version", asset.id().version());
        node.put("type", asset.type().name());
        node.put("requirementId", asset.requirementId());
        node.put("state", asset.state().name());
        ObjectNode prov = mapper.createObjectNode();
        prov.put("sourceFingerprint", asset.provenance().sourceFingerprint());
        prov.put("generator", asset.provenance().generator());
        prov.put("generatedAtEpoch", asset.provenance().generatedAtEpoch());
        node.set("provenance", prov);
        // contentJson 已是合法 JSON 字符串 -> 作为嵌套对象嵌入，避免双重转义
        if (asset.contentJson() != null && !asset.contentJson().isBlank()) {
            try {
                node.set("content", mapper.readTree(asset.contentJson()));
            } catch (IOException e) {
                // content 不是合法 JSON（异常边界）：退化为字符串，保证不丢数据
                node.put("contentRaw", asset.contentJson());
            }
        }
        node.put("createdAt", asset.createdAt());
        node.put("updatedAt", asset.updatedAt());
        if (asset.approvedDigest() != null) {
            node.put("approvedDigest", asset.approvedDigest());
        }
        // 回写的验证报告（§4.6 量化验收）手动序列化，避免对 VerificationReport 施加 Jackson 绑定
        if (asset.report() != null) {
            node.set("verification", toReportJson(asset.report()));
        }
        return node;
    }

    private StoredAsset fromJson(ObjectNode node) {
        String idStr = node.get("id").asText();
        int version = node.get("version").asInt();
        AssetId id = new AssetId(idStr, version);
        AssetType type = AssetType.valueOf(node.get("type").asText());
        String requirementId = node.get("requirementId").asText("");
        AssetState state = AssetState.valueOf(node.get("state").asText());
        ObjectNode prov = (ObjectNode) node.get("provenance");
        Provenance provenance = new Provenance(
                prov.get("sourceFingerprint").asText(),
                prov.get("generator").asText(),
                prov.get("generatedAtEpoch").asLong());
        String contentJson;
        if (node.has("content")) {
            contentJson = node.get("content").toString();
        } else if (node.has("contentRaw")) {
            contentJson = node.get("contentRaw").asText();
        } else {
            contentJson = "{}";
        }
        long createdAt = node.get("createdAt").asLong();
        long updatedAt = node.get("updatedAt").asLong();
        VerificationReport report = node.has("verification") ? fromReportJson((ObjectNode) node.get("verification")) : null;
        String approvedDigest = node.has("approvedDigest") ? node.get("approvedDigest").asText(null) : null;
        return new StoredAsset(id, type, requirementId, state, provenance, contentJson, report,
                createdAt, updatedAt, approvedDigest);
    }

    /** 手动序列化验证报告（避免对 VerificationReport 施加 Jackson 绑定契约）。 */
    private ObjectNode toReportJson(VerificationReport report) {
        ObjectNode v = mapper.createObjectNode();
        v.put("status", report.status().name());
        v.put("stability", report.stability());
        v.put("evidence", report.evidence());
        ArrayNode items = mapper.createArrayNode();
        for (VerificationReport.VerificationItem it : report.items()) {
            ObjectNode itn = mapper.createObjectNode();
            itn.put("ruleId", it.ruleId());
            itn.put("passed", it.passed());
            itn.put("detail", it.detail());
            items.add(itn);
        }
        v.set("items", items);
        return v;
    }

    /** 手动反序列化验证报告（与 {@link #toReportJson} 互逆）。 */
    private VerificationReport fromReportJson(ObjectNode v) {
        VerificationReport.Status status = VerificationReport.Status.valueOf(v.get("status").asText());
        double stability = v.get("stability").asDouble();
        String evidence = v.has("evidence") ? v.get("evidence").asText("") : "";
        List<VerificationReport.VerificationItem> items = new ArrayList<>();
        if (v.has("items")) {
            for (var el : v.get("items")) {
                ObjectNode itn = (ObjectNode) el;
                items.add(new VerificationReport.VerificationItem(
                        itn.get("ruleId").asText(),
                        itn.get("passed").asBoolean(),
                        itn.has("detail") ? itn.get("detail").asText("") : ""));
            }
        }
        return new VerificationReport(status, stability, items, evidence);
    }

    private void writeIndex() throws IOException {
        ObjectNode index = mapper.createObjectNode();
        index.put("seq", seq.get());
        ArrayNode assets = mapper.createArrayNode();
        ObjectNode byRequirement = mapper.createObjectNode();
        for (StoredAsset a : store.values()) {
            ObjectNode meta = mapper.createObjectNode();
            meta.put("id", a.id().toString());
            meta.put("file", fileNameFor(a.id()));
            meta.put("type", a.type().name());
            meta.put("requirementId", a.requirementId());
            meta.put("state", a.state().name());
            meta.put("updatedAt", a.updatedAt());
            assets.add(meta);
            ArrayNode list = byRequirement.has(a.requirementId())
                    ? (ArrayNode) byRequirement.get(a.requirementId()) : null;
            if (list == null) {
                list = mapper.createArrayNode();
                byRequirement.set(a.requirementId(), list);
            }
            list.add(a.id().toString());
        }
        index.set("assets", assets);
        index.set("byRequirement", byRequirement);
        atomicWrite(root.resolve(INDEX_FILE), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(index));
    }

    private void loadIndex() throws IOException {
        Path indexFile = root.resolve(INDEX_FILE);
        if (!Files.exists(indexFile)) {
            return;
        }
        ObjectNode index = (ObjectNode) mapper.readTree(Files.readString(indexFile, StandardCharsets.UTF_8));
        if (index.has("seq")) {
            seq.set(index.get("seq").asLong(1));
        }
        Path assetsDir = root.resolve(ASSETS_DIR);
        if (index.has("assets")) {
            for (var el : index.get("assets")) {
                ObjectNode meta = (ObjectNode) el;
                Path assetFile = assetsDir.resolve(meta.get("file").asText());
                if (!Files.exists(assetFile)) {
                    LOG.warn("Index references missing asset file {}, skip", assetFile);
                    continue;
                }
                ObjectNode node = (ObjectNode) mapper.readTree(Files.readString(assetFile, StandardCharsets.UTF_8));
                StoredAsset loaded = fromJson(node);
                store.put(loaded.id(), loaded);
            }
        }
    }

    private void atomicWrite(Path target, String json) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
