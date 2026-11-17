package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 定位脚本的<b>单一来源装配器</b>。
 *
 * <p>背景：定位逻辑此前在注入脚本（{@code codegen-inject.js}）与 CDP 提取脚本
 * （{@code cdp-extract-fn.js}）各存一份。两份副本的漂移不会有编译错误、也不会让任何测试红，
 * 只会让「注入侧按 A 判、CDP 侧按 B 判」——而实际拾取走 CDP，于是改注入侧等于白改
 * （已真实发生过：{@code roleOf} 的 input 分支只在一侧补齐）。
 *
 * <p>现在的结构：
 * <ul>
 *   <li>{@code codegen/locator-core.js} —— 公共定位逻辑（唯一来源，55 个函数 + 14 个常量）；</li>
 *   <li>{@code codegen/codegen-inject.js} —— 注入侧模板，含占位符 {@value #PLACEHOLDER}；</li>
 *   <li>{@code codegen/cdp-extract-fn.js} —— CDP 侧模板，同上；</li>
 *   <li>本类在<b>类加载期</b>把核心拼进两个模板，得到可直接求值的完整脚本。</li>
 * </ul>
 *
 * <p><b>为什么必须校验而不是直接字符串替换</b>：拼接失败的表现是「脚本静默不执行、拾取无反应」，
 * 比逻辑错误更难定位。因此这里做三重硬校验，任一不满足即抛 {@link IllegalStateException}
 * （类初始化失败 → {@link ExceptionInInitializerError}，不会被上层 {@code catch (Exception)}
 * 静默吞掉）：占位符存在且唯一、替换后长度守恒、产物必须含关键标记。
 */
public final class LocatorCoreScript {

    /** 模板中的核心占位符（两端唯一）。 */
    static final String PLACEHOLDER = "/*__LOCATOR_CORE__*/";

    /** 注入侧产物必须包含的标记：核心函数 + 事件入口 + 全局导出。缺一即说明拼装被破坏。 */
    private static final String[] INJECT_REQUIRED = {
            "function roleOf(",
            "function descendantAnchorsOf(",
            "function pickTarget(",
            "window.addEventListener('click'",
            "window.addEventListener('contextmenu'",
            "window.__CODEGEN__"
    };

    /** CDP 侧产物必须包含的标记：核心函数 + 该侧独有的入口。 */
    private static final String[] CDP_REQUIRED = {
            "function roleOf(",
            "function descendantAnchorsOf(",
            "function factsOf(",
            "function ancestorChain("
    };

    private static final String CORE = load("codegen/locator-core.js");
    private static final String INJECT =
            assemble("inject", load("codegen/codegen-inject.js"), CORE, INJECT_REQUIRED);
    private static final String CDP =
            assemble("cdp", load("codegen/cdp-extract-fn.js"), CORE, CDP_REQUIRED);

    private LocatorCoreScript() {
    }

    /** 注入到被测页的完整脚本（{@code addInitScript} / {@code evaluate} 直接可用）。 */
    public static String inject() {
        return INJECT;
    }

    /** CDP 侧自包含函数（{@code function () { ... }} 形式的表达式，供 {@code Runtime.evaluate}）。 */
    public static String cdp() {
        return CDP;
    }

    /**
     * 把核心拼进模板并校验结构。
     *
     * @param side      用于错误信息的标识（{@code inject} / {@code cdp}）
     * @param template  含占位符的模板
     * @param core      公共核心
     * @param required  产物必须包含的标记
     * @return 可直接求值的完整脚本
     * @throws IllegalStateException 占位符缺失/重复、替换后仍有占位符、长度不守恒或缺少关键标记
     */
    static String assemble(String side, String template, String core, String[] required) {
        int at = template.indexOf(PLACEHOLDER);
        if (at < 0) {
            throw new IllegalStateException("[codegen] " + side
                    + " template is missing the locator-core placeholder: " + PLACEHOLDER);
        }
        if (template.indexOf(PLACEHOLDER, at + PLACEHOLDER.length()) >= 0) {
            throw new IllegalStateException("[codegen] " + side
                    + " template contains more than one locator-core placeholder");
        }
        String out = template.replace(PLACEHOLDER, core);
        if (out.contains(PLACEHOLDER)) {
            throw new IllegalStateException("[codegen] " + side + " assembly left an unresolved placeholder");
        }
        // 长度守恒：注入的核心必须与核心文件完全一致（防截断、防重复拼接、防替换成空串）
        int expected = template.length() - PLACEHOLDER.length() + core.length();
        if (out.length() != expected) {
            throw new IllegalStateException("[codegen] " + side + " assembly length mismatch: expected "
                    + expected + " but was " + out.length());
        }
        for (String marker : required) {
            if (!out.contains(marker)) {
                throw new IllegalStateException("[codegen] " + side
                        + " assembly is missing required marker: " + marker);
            }
        }
        return out;
    }

    /** 读取 classpath 资源；缺失即失败（绝不返回空串，否则调用方会静默跳过注入）。 */
    private static String load(String path) {
        ClassLoader cl = LocatorCoreScript.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("[codegen] script resource not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("[codegen] failed to read script resource: " + path, e);
        }
    }
}
