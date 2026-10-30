package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * {@code pickTarget} 同源一致性保护：{@code codegen-inject.js}（注入被测页）与
 * {@link CodegenCdpNodeExtractor#SELF_CONTAINED_GET_INFO_FN}（自包含、供跨源 iframe 拾取）里的
 * {@code pickTarget} 逻辑必须逐字一致（改一处须同步另一处，见 CodegenCdpNodeExtractor 类注释）。
 *
 * <p>该测试不含浏览器，只做"逻辑体一致"的静态比对——剥离注释与空白后，两处函数体必须相同，
 * 否则视为漂移并失败，倒逼开发者同步修改。
 */
public class CodegenPickTargetSyncTest {

    @Test
    public void injectJsAndSelfContainedPickTargetAreInSync() throws Exception {
        String injectTarget = extractPickTarget(loadResource("/codegen/codegen-inject.js"));
        String embeddedTarget = extractPickTarget(loadSelfContained());

        assertNotNull("inject.js 应包含 pickTarget", injectTarget);
        assertNotNull("SELF_CONTAINED_GET_INFO_FN 应包含 pickTarget", embeddedTarget);
        assertEquals("pickTarget 两处实现必须一致（剥离注释/空白后）；改一处须同步另一处",
                injectTarget, embeddedTarget);
    }

    /** 从 classpath 读取注入脚本资源。 */
    private static String loadResource(String path) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                CodegenPickTargetSyncTest.class.getResourceAsStream(path), StandardCharsets.UTF_8))) {
            return r.lines().collect(Collectors.joining("\n"));
        }
    }

    /** 反射读取 {@link CodegenCdpNodeExtractor} 的私有常量 {@code SELF_CONTAINED_GET_INFO_FN}。 */
    private static String loadSelfContained() throws Exception {
        Field f = CodegenCdpNodeExtractor.class.getDeclaredField("SELF_CONTAINED_GET_INFO_FN");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    /**
     * 抽取 {@code function pickTarget(e) { ... }} 的函数体（含签名），再做"去注释 + 规范化空白"。
     *
     * <p>去注释：先剥 {@code /* *\/}（含多行），再剥 {@code //} 行注释；再做空白折叠。
     * 这样只比对可执行逻辑，忽略两处不同的注释/排版（注入版 JSDoc 详尽、嵌入版紧凑）。
     */
    private static String extractPickTarget(String js) {
        return extractFunction(js, "pickTarget");
    }

    /**
     * 抽取 {@code function NAME(...){ ... }} 的函数体（含签名），再做"去注释 + 规范化空白"。
     *
     * <p>去注释：先剥 {@code /* *\/}（含多行），再剥 {@code //} 行注释；再做空白折叠。
     * 这样只比对可执行逻辑，忽略两处不同的注释/排版（注入版 JSDoc 详尽、嵌入版紧凑）。
     */
    private static String extractFunction(String js, String name) {
        if (js == null) return null;
        int start = js.indexOf("function " + name + "(");
        if (start < 0) return null;
        int brace = js.indexOf('{', start);
        int depth = 0, i = brace;
        for (; i < js.length(); i++) {
            char c = js.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) break;
            }
        }
        String body = js.substring(start, i + 1);
        // 去 /* */ 块注释（含多行）
        body = body.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        // 去 // 行注释
        body = body.replaceAll("//[^\\n]*", "");
        // 规范空白
        return body.replaceAll("\\s+", " ").trim();
    }
}
