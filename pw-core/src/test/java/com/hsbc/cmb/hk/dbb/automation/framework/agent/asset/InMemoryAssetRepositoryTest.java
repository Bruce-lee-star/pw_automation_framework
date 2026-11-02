package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

/** 内存资产仓库：保存恒为 DRAFT + 状态机驱动迁移（设计 §3.1.3 / §4.6）。 */
public class InMemoryAssetRepositoryTest {

    @Test
    public void saveAlwaysDraftThenLifecycle() {
        AssetRepository repo = new InMemoryAssetRepository();
        AssetId id = repo.save(new AssetDraft(AssetType.LINKAGE_GRAPH, "REQ-1", "{}", Provenance.manual("p1")));

        StoredAsset saved = repo.get(id);
        assertNotNull(saved);
        assertEquals(AssetState.DRAFT, saved.state());

        assertEquals(AssetState.PENDING_REVIEW, repo.submit(id, "qa").state());
        assertEquals(AssetState.APPROVED, repo.approve(id, "architect").state());
        assertEquals(AssetState.ACTIVE, repo.activate(id).state());
        assertEquals(AssetState.DEPRECATED, repo.deprecate(id, "page revamped").state());
    }

    @Test
    public void queryByRequirement() {
        AssetRepository repo = new InMemoryAssetRepository();
        AssetId a = repo.save(new AssetDraft(AssetType.ELEMENT_INVENTORY, "REQ-9", "{}", Provenance.manual("p")));
        repo.save(new AssetDraft(AssetType.ELEMENT_INVENTORY, "REQ-OTHER", "{}", Provenance.manual("p")));

        assertEquals(1, repo.queryByRequirement("REQ-9").size());
        assertEquals("REQ-9", repo.get(a).requirementId());
    }

    @Test
    public void unknownAssetThrows() {
        AssetRepository repo = new InMemoryAssetRepository();
        assertThrows(IllegalArgumentException.class, () -> repo.submit(new AssetId("nope"), "x"));
    }
}
