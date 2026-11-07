package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 有界无障碍快照（设计 §4.2 SnapshotTree 的精简值类型）。
 *
 * <p>从 Playwright 无障碍树（{@code page.accessibility().snapshot()}）枚举，<b>仅携带白名单字段</b>
 * （角色、可访问名、可见/启用、脱敏文本），对应 R1/R7 数据最小化与 R6（不接外部脚本）。</p>
 *
 * <p>{@link ElementState#ref()} 即 {@link LocatorSpec} 的 canonical 串（角色优先），
 * 既是快照索引键，也是命令目标，可在 pw-core 与实现层无损传递。</p>
 *
 * <p>快照是一棵<b>有界树</b>：{@link ElementState} 携带 {@code depth} 与 {@code children}（深度/节点数
 * 上限由 {@link SnapshotBounds} 约束），每个节点同时持有 per-node {@code frameId}（跨 Frame 归属的下沉标记）。
 * {@link #snapshotDigest()} 给出与采集时间/序号无关的<b>稳定结构指纹</b>，供联动/影响分析做幂等与 diff。</p>
 */
public final class Snapshot {
    private final String pageRef;                       // 页面指纹
    private final List<ElementState> roots;             // 树根（顶层节点）
    private final Map<String, ElementState> byRef;      // ref(canonical) -> 状态（全树扁平）
    private final SnapshotEnvelope envelope;            // 协议信封（可为 null：旧式精简快照）
    private final String digest;                        // 稳定结构指纹（构造期计算）

    /** 旧构造器：无协议信封（测试用假通道 / 历史调用方），语义与 v3.7 之前完全一致。 */
    public Snapshot(String pageRef, List<ElementState> roots) {
        this(pageRef, roots, null);
    }

    /**
     * 带协议信封的构造器（生产采集路径）。
     *
     * @param roots    快照树根（节点可携带 children；若传入平铺列表则退化为单层树，向后兼容）
     * @param envelope 观测版本/资源边界/状态；{@code null} 表示未声明协议（不触发验证侧 fail-closed 门禁）
     */
    public Snapshot(String pageRef, List<ElementState> roots, SnapshotEnvelope envelope) {
        this.pageRef = pageRef;
        this.roots = roots == null ? List.of() : List.copyOf(roots);
        this.envelope = envelope;
        this.byRef = flatten(this.roots).stream()
                .collect(Collectors.toMap(ElementState::ref, Function.identity(), (a, b) -> a));
        this.digest = computeDigest(this.pageRef, this.roots);
    }

    /** 深度优先先序扁平化（根 → 各子树），用于 byRef 索引与 elements() 遍历。 */
    private static List<ElementState> flatten(List<ElementState> nodes) {
        List<ElementState> out = new ArrayList<>();
        for (ElementState n : nodes) {
            out.add(n);
            out.addAll(flatten(n.children()));
        }
        return out;
    }

    public String pageRef() { return pageRef; }

    /** 快照协议信封；旧式精简快照返回 {@code null}。 */
    public SnapshotEnvelope envelope() { return envelope; }

    /** 该快照是否明确为完整观测（无信封的历史快照按「未声明」处理，返回 true 以保持向后兼容）。 */
    public boolean isComplete() {
        return envelope == null || envelope.isComplete();
    }

    /** 快照树根（顶层节点）。 */
    public List<ElementState> roots() { return roots; }

    /** 全部节点（深度优先先序，含根与所有后代），供遍历/断言。 */
    public List<ElementState> elements() {
        return flatten(roots);
    }

    /** 按 ref（LocatorSpec canonical）查元素状态（联动断言主入口）。 */
    public ElementState byRef(String ref) { return byRef.get(ref); }

    /** 按 ref 查元素状态（联动断言主入口，传入定位符）。 */
    public ElementState byRef(LocatorSpec spec) { return byRef.get(spec.canonical()); }

    /**
     * 稳定结构指纹（SHA-256 十六进制，小写）。
     *
     * <p>仅依赖 {@link #pageRef()} 与元素树的内容（ref/role/name/visible/enabled/text/frameId/depth/
     * unsupported 及后代），<b>排除采集时间、快照序号、字节/节点计数等观测元数据</b>，
     * 故同一页面两次完整快照必然得到相同指纹；任一结构字段变动则指纹变化。</p>
     */
    public String snapshotDigest() { return digest; }

    private static String computeDigest(String pageRef, List<ElementState> roots) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append("pageRef=").append(pageRef).append('\n');
            for (ElementState root : roots) {
                appendNode(sb, root);
            }
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void appendNode(StringBuilder sb, ElementState n) {
        sb.append(n.ref()).append('|').append(n.role()).append('|').append(n.name()).append('|')
                .append(n.visible()).append('|').append(n.enabled()).append('|')
                .append(n.text()).append('|').append(n.frameId()).append('|')
                .append(n.depth()).append('|').append(n.unsupported()).append('\n');
        for (ElementState c : n.children()) {
            appendNode(sb, c);
        }
    }

    /** 单个元素的可观测状态（快照树的节点）。 */
    public static final class ElementState {
        private final String ref;       // 稳定引用（来自 SnapshotTree ref）
        private final String role;      // 角色（button/input/...）
        private final String name;      // 可访问名（脱敏）
        private final boolean visible;
        private final boolean enabled;
        private final String text;      // 可见文本（脱敏）
        private final int depth;        // 树深度（根=1）
        private final String frameId;   // per-node 帧归属（下沉自 SnapshotEnvelope.frameId）
        private final List<ElementState> children;  // 子节点（有界）
        private final boolean unsupported;          // per-node 观测不支持标记（如跨域 Frame 子树）
        private final String unsupportedReason;

        /** 旧构造器（兼容既有调用方与测试）：单层、主帧、未标记不支持。 */
        public ElementState(String ref, String role, String name, boolean visible, boolean enabled, String text) {
            this(ref, role, name, visible, enabled, text, 0, "main", List.of(), false, null);
        }

        /** 完整构造器（含树与 per-node 标记）。 */
        public ElementState(String ref, String role, String name, boolean visible, boolean enabled, String text,
                           int depth, String frameId, List<ElementState> children,
                           boolean unsupported, String unsupportedReason) {
            this.ref = ref;
            this.role = role;
            this.name = name;
            this.visible = visible;
            this.enabled = enabled;
            this.text = text;
            this.depth = depth;
            this.frameId = frameId == null ? "main" : frameId;
            this.children = children == null ? List.of() : List.copyOf(children);
            this.unsupported = unsupported;
            this.unsupportedReason = unsupportedReason;
        }

        public String ref() { return ref; }
        /** 还原为定位符（角色优先）。 */
        public LocatorSpec locator() { return LocatorSpec.parse(ref); }
        public String role() { return role; }
        public String name() { return name; }
        public boolean visible() { return visible; }
        public boolean enabled() { return enabled; }
        public String text() { return text; }
        public int depth() { return depth; }
        public String frameId() { return frameId; }
        public List<ElementState> children() { return children; }
        public boolean unsupported() { return unsupported; }
        public String unsupportedReason() { return unsupportedReason; }
    }
}
