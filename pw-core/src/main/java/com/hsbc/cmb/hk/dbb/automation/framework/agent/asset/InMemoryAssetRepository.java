package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link AssetRepository} 的内存实现（设计 §3.1.3 资产层最小可用）。
 *
 * <p>基于 {@link AssetStateMachine.DefaultAssetStateMachine} 驱动状态迁移，所有变更均为受控迁移；
 * 生产可替换为 DB/文件实现，但状态机契约不变。线程安全（ConcurrentHashMap + 同步迁移）。</p>
 */
public final class InMemoryAssetRepository implements AssetRepository {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryAssetRepository.class);

    private final AssetStateMachine stateMachine = new AssetStateMachine.DefaultAssetStateMachine();
    private final Map<AssetId, StoredAsset> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    @Override
    public synchronized AssetId save(AssetDraft draft) {
        long n = seq.getAndIncrement();
        AssetId id = new AssetId("asset:" + draft.type().name().toLowerCase() + ":" + n, 1);
        long now = System.currentTimeMillis();
        StoredAsset asset = new StoredAsset(id, draft.type(), draft.requirementId(), AssetState.DRAFT,
                draft.provenance(), draft.contentJson(), null, now, now);
        store.put(id, asset);
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

    private StoredAsset transition(AssetId id, AssetStateMachine.Event event, String actor) {
        StoredAsset current = store.get(id);
        if (current == null) {
            throw new IllegalArgumentException("Unknown asset: " + id);
        }
        AssetState next = stateMachine.transition(current.state(), event);
        StoredAsset updated = new StoredAsset(id, current.type(), current.requirementId(), next,
                current.provenance(), current.contentJson(), current.report(), current.createdAt(), System.currentTimeMillis());
        store.put(id, updated);
        LOG.info("Asset {} transition {} -> {} by {}", id, current.state(), next, actor);
        return updated;
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
    public synchronized StoredAsset recordVerification(AssetId id, VerificationReport report) {
        StoredAsset current = store.get(id);
        if (current == null) {
            throw new IllegalArgumentException("Unknown asset: " + id);
        }
        StoredAsset updated = new StoredAsset(id, current.type(), current.requirementId(), current.state(),
                current.provenance(), current.contentJson(), report, current.createdAt(), System.currentTimeMillis());
        store.put(id, updated);
        LOG.info("Asset {} verification recorded (status={}, stability={})", id, report.status(), report.stability());
        return updated;
    }

    @Override
    public StoredAsset get(AssetId id) {
        return store.get(id);
    }
}
