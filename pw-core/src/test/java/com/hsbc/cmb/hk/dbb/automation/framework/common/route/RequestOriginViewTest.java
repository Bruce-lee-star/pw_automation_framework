package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link RequestOriginView} 契约：跨模块归属视图的语义与 null 安全。
 *
 * <p>本类型是 pw-web-ui 唯一可见的归属载体，故"不可归属"必须表达为
 * {@link RequestOriginView#UNKNOWN} 而不是 null —— 业务谓词里直接调用
 * {@code fromOwner(..)} 不应 NPE。
 */
public class RequestOriginViewTest {

    @Test
    public void attributedOriginMatchesOwnerAndEpoch() {
        RequestOriginView origin = new RequestOriginView("page@1a2b", "frame@3c4d", 2L, false, null, 0);

        assertTrue("有 owner 且未降级 ⇒ 可归属", origin.attributed());
        assertTrue(origin.fromOwner("page@1a2b"));
        assertFalse("不同 Page 不得误匹配", origin.fromOwner("page@9999"));
        assertFalse("null 参数恒 false（便于在谓词里直接使用）", origin.fromOwner(null));
        assertEquals(2L, origin.navSeq());
    }

    @Test
    public void unknownAndDegradedAreNeverAttributed() {
        assertFalse("UNKNOWN 不可归属", RequestOriginView.UNKNOWN.attributed());
        assertFalse("DEGRADED 不可归属", RequestOriginView.DEGRADED.attributed());
        assertTrue("DEGRADED 必须保留降级标记（用于诊断，不静默吞掉）",
                RequestOriginView.DEGRADED.degraded());
        assertNull("不可归属时无 owner", RequestOriginView.UNKNOWN.ownerPageId());
        assertEquals("不可归属时代次为 -1", -1L, RequestOriginView.UNKNOWN.navSeq());
    }

    @Test
    public void degradedStillDoesNotMatchAnyOwner() {
        // 降级只影响归属，不代表采集失败 —— 但绝不能被当成"任意 Page 都匹配"
        assertFalse(RequestOriginView.DEGRADED.fromOwner("page@1"));
        assertFalse(RequestOriginView.DEGRADED.fromOwner(null));
    }
}
