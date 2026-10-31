package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 V2 CDP 抓取层 {@link RolePickerCdpSession} 的 inspect 配置构造。
 *
 * <p>回归守护：Chromium 在 {@code Overlay.setInspectMode} 的 {@code mode != "none"} 时强制要求
 * {@code highlightConfig} 参数，缺失即抛 "highlight configuration parameter is missing"。
 * 该配置此前只发了 {@code mode}，导致 CDP inspect 模式每次失败、降级回 JS 注入。
 */
public class RolePickerCdpSessionTest {

    @Test
    public void highlightConfig_isNonEmptyAndCarriesContentColor() {
        JsonObject cfg = RolePickerCdpSession.highlightConfig();
        assertNotNull("highlightConfig must not be null", cfg);
        // Chromium 强制要求至少 contentColor（RGBA）；空对象仍会触发缺失报错。
        assertTrue("highlightConfig must carry contentColor", cfg.has("contentColor"));
        JsonObject content = cfg.getAsJsonObject("contentColor");
        assertEquals(111, content.get("r").getAsInt());
        assertEquals(168, content.get("g").getAsInt());
        assertEquals(220, content.get("b").getAsInt());
        assertEquals(0.6, content.get("a").getAsDouble(), 0.0001);
        // 关键不变量：不能退化为空对象，否则仍报 "highlight configuration parameter is missing"。
        assertTrue("highlightConfig must not be empty", cfg.entrySet().size() >= 3);
    }

    @Test
    public void highlightConfig_hasConsistentGridAndShapeColors() {
        JsonObject cfg = RolePickerCdpSession.highlightConfig();
        assertTrue(cfg.has("cssGridColor"));
        assertTrue(cfg.has("shapeColor"));
        assertEquals(0.6, cfg.getAsJsonObject("cssGridColor").get("a").getAsDouble(), 0.0001);
    }
}
