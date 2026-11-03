package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JsonFileAssetRepositoryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static AssetDraft sampleDraft(String requirementId) {
        String content = "{\"elements\":[{\"ref\":\"link:foo\",\"role\":\"link\"}],\"count\":1}";
        return new AssetDraft(AssetType.LINKAGE_GRAPH, requirementId, content, Provenance.manual("page:sha256:abc"));
    }

    @Test
    public void saveProducesDraftAndNestedContentOnDisk() throws Exception {
        Path root = tmp.newFolder().toPath();
        JsonFileAssetRepository repo = new JsonFileAssetRepository(root);

        AssetId id = repo.save(sampleDraft("REQ-101"));

        StoredAsset a = repo.get(id);
        assertNotNull(a);
        assertEquals(AssetState.DRAFT, a.state());
        assertEquals("REQ-101", a.requirementId());
        // contentJson 原样保留（嵌套对象已落盘，读取时 content.toString() 仍合法 JSON）
        assertTrue(a.contentJson().contains("\"ref\":\"link:foo\""));

        // 磁盘：assets/*.json + _index.json 均存在
        Path index = root.resolve("_index.json");
        assertTrue("index file written", Files.exists(index));
        Path assetsDir = root.resolve("assets");
        assertTrue("assets dir written", Files.exists(assetsDir));
        assertTrue("at least one asset file written",
                Files.list(assetsDir).anyMatch(p -> p.toString().endsWith(".json")));

        // 资产本体 JSON 的 content 是嵌套对象，而非转义字符串
        ObjectMapper m = new ObjectMapper();
        Path assetFile = Files.list(assetsDir).filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
        JsonNode node = m.readTree(Files.readString(assetFile, StandardCharsets.UTF_8));
        assertTrue("content is nested object", node.get("content").isObject());
        assertEquals("link:foo", node.get("content").get("elements").get(0).get("ref").asText());
        assertEquals("DRAFT", node.get("state").asText());
    }

    @Test
    public void fullStateMachineLifecyclePersists() throws Exception {
        Path root = tmp.newFolder().toPath();
        JsonFileAssetRepository repo = new JsonFileAssetRepository(root);

        AssetId id = repo.save(sampleDraft("REQ-1"));
        assertEquals(AssetState.DRAFT, repo.get(id).state());

        repo.submit(id, "analyst");
        assertEquals(AssetState.PENDING_REVIEW, repo.get(id).state());

        repo.approve(id, "lead");
        assertEquals(AssetState.APPROVED, repo.get(id).state());

        repo.activate(id);
        assertEquals(AssetState.ACTIVE, repo.get(id).state());

        repo.deprecate(id, "locator drift");
        assertEquals(AssetState.DEPRECATED, repo.get(id).state());
    }

    @Test
    public void illegalTransitionRejected() throws Exception {
        Path root = tmp.newFolder().toPath();
        JsonFileAssetRepository repo = new JsonFileAssetRepository(root);
        AssetId id = repo.save(sampleDraft("REQ-2"));

        // DRAFT 不能直接 ACTIVATE
        try {
            repo.activate(id);
            org.junit.Assert.fail("DRAFT -> ACTIVE must be illegal");
        } catch (IllegalStateException expected) {
            // ok
        }
        assertEquals(AssetState.DRAFT, repo.get(id).state());
    }

    @Test
    public void queryByRequirementReturnsMappedAssets() throws Exception {
        Path root = tmp.newFolder().toPath();
        JsonFileAssetRepository repo = new JsonFileAssetRepository(root);
        AssetId a1 = repo.save(sampleDraft("REQ-A"));
        repo.save(sampleDraft("REQ-B"));

        List<StoredAsset> found = repo.queryByRequirement("REQ-A");
        assertEquals(1, found.size());
        assertEquals(a1, found.get(0).id());

        assertTrue(repo.queryByRequirement("REQ-MISSING").isEmpty());
    }

    @Test
    public void reloadFromDiskRebuildsStateAndIndex() throws Exception {
        Path root = tmp.newFolder().toPath();
        JsonFileAssetRepository first = new JsonFileAssetRepository(root);
        AssetId id = first.save(sampleDraft("REQ-RL"));
        first.submit(id, "analyst");
        first.approve(id, "lead");
        first.activate(id);
        // 再加一个不同需求，确认序列与多资产都能重建
        AssetId id2 = first.save(sampleDraft("REQ-RL2"));

        // 新实例加载同一目录：状态/内容/序号全部重建
        JsonFileAssetRepository reloaded = new JsonFileAssetRepository(root);
        StoredAsset ra = reloaded.get(id);
        assertNotNull(ra);
        assertEquals(AssetState.ACTIVE, ra.state());
        assertTrue(ra.contentJson().contains("link:foo"));
        assertEquals(AssetState.DRAFT, reloaded.get(id2).state());
        assertEquals(1, reloaded.queryByRequirement("REQ-RL").size());
        assertEquals(1, reloaded.queryByRequirement("REQ-RL2").size());

        // 新建资产沿用序列，不与已存 id 冲突
        AssetId id3 = reloaded.save(sampleDraft("REQ-RL3"));
        assertFalse("new id must not collide with reloaded ids",
                id3.equals(id) || id3.equals(id2));
        assertNotNull(reloaded.get(id3));
    }

    @Test
    public void unknownAssetThrows() throws Exception {
        Path root = tmp.newFolder().toPath();
        JsonFileAssetRepository repo = new JsonFileAssetRepository(root);
        try {
            repo.submit(new AssetId("asset:ghost:999"), "x");
            org.junit.Assert.fail("unknown asset must throw");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertNull(repo.get(new AssetId("asset:ghost:999")));
    }
}
