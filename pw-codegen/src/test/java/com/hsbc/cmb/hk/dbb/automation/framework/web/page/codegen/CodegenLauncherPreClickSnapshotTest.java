package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp.PreClickSnapshot;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 桥接载荷 → 点击前快照：只认字符串字段，缺字段一律「无证据」。
 *
 * <p>捕获阶段的事实（{@code factsOf}）是唯一能证明「按钮点一下就变成 loading」的证据来源；
 * 这里若凭空造值，就会把正常元素的名字也一起改掉。
 */
public class CodegenLauncherPreClickSnapshotTest {

    @Test
    public void readsNameAndTextFromCapturePhaseInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", "Continue");
        info.put("text", "Loading, please wait. Continue");

        PreClickSnapshot pre = CodegenLauncher.preClickSnapshot(info);

        assertEquals("Continue", pre.name());
        assertEquals("Loading, please wait. Continue", pre.text());
    }

    @Test
    public void missingInfoMeansNoEvidence() {
        assertNull(CodegenLauncher.preClickSnapshot(null));
    }

    @Test
    public void nonStringOrBlankFieldsDegradeToNoEvidence() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", 42);
        info.put("text", "   ");

        PreClickSnapshot pre = CodegenLauncher.preClickSnapshot(info);

        assertEquals("非字符串名不可采信", null, pre.name());
        assertEquals("空白文本等于没有证据", null, pre.text());
        assertEquals(true, pre.isEmpty());
    }
}
