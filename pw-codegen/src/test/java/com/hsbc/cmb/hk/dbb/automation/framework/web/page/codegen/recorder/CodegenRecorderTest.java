package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.recorder;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/** 验证录制器：保序、首次出现去重、空字段键忽略。 */
public class CodegenRecorderTest {

    @Test
    public void recordsInOrderAndDedupesByFirstAppearance() {
        CodegenRecorder r = new CodegenRecorder();
        r.record("k1", "click", "");
        r.record("k2", "fill", "a");
        r.record("k1", "click", "");   // 重复字段键：去重
        r.record("k3", "click", "");
        r.record(null, "click", "");    // 未匹配：忽略
        r.record("", "click", "");      // 空键：忽略

        List<String> keys = r.orderedFieldKeys();
        Assert.assertEquals(List.of("k1", "k2", "k3"), keys);
        // snapshot 保留全部非忽略记录（k1 被记录两次），去重仅作用于有序字段键
        Assert.assertEquals(4, r.snapshot().size());
        Assert.assertEquals("k2", r.snapshot().get(1).fieldKey);
        Assert.assertEquals("a", r.snapshot().get(1).value);
    }

    @Test
    public void emptyRecorder_hasNoKeys() {
        Assert.assertTrue(new CodegenRecorder().orderedFieldKeys().isEmpty());
    }
}
