package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.AriaRoles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注入脚本回传的<b>富元素事实</b>（角色 / 可访问名 / 属性 / 文本 / 同级序号）+ 上溯父级链。
 *
 * <p>打分选择、唯一性校验、父级嵌套降级全部在 {@code CodegenScorer} 中基于本模型完成（纯 Java、可单测、
 * 不依赖浏览器）。本类只负责从 {@code codegen-inject.js#getInfo} 的 JSON 载荷反序列化。
 */
public final class CodegenFacts {

    /** 单个元素的事实快照（与注入脚本 factsOf 输出字段一一对应）。 */
    public static final class Fact {
        public final String role;
        public final String name;
        public final String tag;
        public final String id;
        public final String testid;
        public final String type;
        public final String placeholder;
        public final String alt;
        public final String title;
        public final String value;
        public final String text;
        public final String label;
        public final int nth;
        public final String cssSelector;
        /**
         * 元素所在的 open shadow 宿主链（outermost-first，每层选择器相对其自身文档 / 外层 shadow root）。
         * 主文档元素为空列表。
         *
         * <p>用途：{@code document.querySelector} <b>不穿透 shadow 边界</b>，CDP 侧按选择器反查元素时必须
         * 拼成链式表达式；生成代码侧无此约束（Playwright 的 CSS 引擎自动穿透 open shadow root）。
         */
        public final List<String> shadowHosts;
        /**
         * 元素的 {@code class} 属性原文。class 常混打包 hash 与工具类，定位时只取其中的语义 token
         * （见 {@code SelectorStability#stableTokens}）。
         */
        public final String className;
        /**
         * 元素在<b>普通文档树</b>里的绝对 XPath（{@code /html/body/div[2]/form[1]/input[3]}）。
         * shadow root 内的元素为空串——XPath 无法表达穿透 shadow 边界的路径。
         */
        public final String xpath;
        /** 表单 {@code name} 属性：与语言无关，是 input 类元素最稳定的定位依据之一。 */
        public final String nameAttr;
        /**
         * 可定位的语义属性（{@code data-*} + {@code aria-label}，属性名 → 值）。
         *
         * <p>{@code data-i18n} / {@code data-qa} / {@code data-test} / {@code data-automation-id} 由业务书写，
         * 值稳定且语义清晰，通常一个属性就能唯一定位——比 class token 与结构路径都可靠。
         * 值是否随机（含 hash/序号）由 {@code SelectorStability} 判定后决定精确或部分匹配。
         */
        public final Map<String, String> attrs;

        public Fact(String role, String name, String tag, String id, String testid, String type,
                    String placeholder, String alt, String title, String value,
                    String text, String label, int nth, String cssSelector) {
            this(role, name, tag, id, testid, type, placeholder, alt, title, value,
                    text, label, nth, cssSelector, List.of());
        }

        public Fact(String role, String name, String tag, String id, String testid, String type,
                    String placeholder, String alt, String title, String value,
                    String text, String label, int nth, String cssSelector, List<String> shadowHosts) {
            this(role, name, tag, id, testid, type, placeholder, alt, title, value,
                    text, label, nth, cssSelector, shadowHosts, "", "", "");
        }

        public Fact(String role, String name, String tag, String id, String testid, String type,
                    String placeholder, String alt, String title, String value,
                    String text, String label, int nth, String cssSelector, List<String> shadowHosts,
                    String className, String xpath, String nameAttr) {
            this(role, name, tag, id, testid, type, placeholder, alt, title, value,
                    text, label, nth, cssSelector, shadowHosts, className, xpath, nameAttr, Map.of());
        }

        public Fact(String role, String name, String tag, String id, String testid, String type,
                    String placeholder, String alt, String title, String value,
                    String text, String label, int nth, String cssSelector, List<String> shadowHosts,
                    String className, String xpath, String nameAttr, Map<String, String> attrs) {
            this.role = role;
            this.name = name;
            this.tag = tag;
            this.id = id;
            this.testid = testid;
            this.type = type;
            this.placeholder = placeholder;
            this.alt = alt;
            this.title = title;
            this.value = value;
            this.text = text;
            this.label = label;
            this.nth = nth;
            this.cssSelector = cssSelector;
            // 防御性拷贝且不可变：事实对象会被多线程（拾取执行器 / 生成线程 / 事件泵）读取。
            this.shadowHosts = (shadowHosts == null || shadowHosts.isEmpty())
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(shadowHosts));
            this.className = className == null ? "" : className;
            this.xpath = xpath == null ? "" : xpath;
            this.nameAttr = nameAttr == null ? "" : nameAttr;
            this.attrs = (attrs == null || attrs.isEmpty())
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
        }

        /**
         * 该事实是否"角色可定位"：role 能映射到确切的 Playwright {@code AriaRole} 常量（且非 generic/none/
         * presentation），并且有可读 name。
         *
         * <p>判定统一收敛到 {@link AriaRoles}：历史上的"role 非 generic 即可定位"会把 {@code label} 这类
         * 枚举里不存在的角色放行，最终被生成器静默降级成 {@code AriaRole.GENERIC} —— 产物能编译但运行期定位不到。
         */
        public boolean roleLocatable() {
            return name != null && !name.isBlank() && AriaRoles.isLocatable(role);
        }
    }

    public final Fact self;
    public final List<Fact> ancestors;

    public CodegenFacts(Fact self, List<Fact> ancestors) {
        this.self = self;
        this.ancestors = ancestors;
    }

    /** 从注入脚本 {@code getInfo} 的 JSON 载荷解析（{@code {self:{...}, ancestors:[...]}}）。 */
    public static CodegenFacts fromJson(JsonObject value) {
        if (value == null || !value.has("self") || value.get("self").isJsonNull()) {
            return null;
        }
        Fact self = factFrom(value.getAsJsonObject("self"));
        List<Fact> ancestors = new ArrayList<>();
        if (value.has("ancestors") && value.get("ancestors").isJsonArray()) {
            for (var el : value.getAsJsonArray("ancestors")) {
                if (el.isJsonObject()) ancestors.add(factFrom(el.getAsJsonObject()));
            }
        }
        return new CodegenFacts(self, ancestors);
    }

    private static Fact factFrom(JsonObject o) {
        return new Fact(
                str(o, "role", "generic"),
                str(o, "name", ""),
                str(o, "tag", ""),
                str(o, "id", ""),
                str(o, "testid", ""),
                str(o, "type", ""),
                str(o, "placeholder", ""),
                str(o, "alt", ""),
                str(o, "title", ""),
                str(o, "value", ""),
                str(o, "text", ""),
                str(o, "label", ""),
                intOf(o, "nth", -1),
                str(o, "cssSelector", ""),
                strList(o, "shadowHosts"),
                str(o, "className", ""),
                str(o, "xpath", ""),
                str(o, "nameAttr", ""),
                mapOf(o, "attrs"));
    }

    /**
     * 属性映射取值：缺失 / 非对象 / 非原始值一律跳过。
     *
     * <p>旧版注入脚本没有 {@code attrs} 字段 ⇒ 退化为空映射（不产生任何属性候选），行为与改造前一致。
     */
    private static Map<String, String> mapOf(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull() || !o.get(k).isJsonObject()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(k).entrySet()) {
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) continue;
            out.put(e.getKey(), v.getAsString());
        }
        return out;
    }

    /**
     * 字符串数组取值：缺失 / 非数组返回空链（旧版注入脚本没有该字段即退化为普通查找）。
     *
     * <p>空字符串项<b>原样保留</b>：链上任一层失效即说明事实不可信，必须由定位侧判定"不可用并回退坐标"，
     * 静默丢掉中间层会去错误的 shadow root 里找元素。
     */
    private static List<String> strList(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) return List.of();
        JsonElement el = o.get(k);
        if (!el.isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (item == null || item.isJsonNull()) continue;
            String v = item.getAsString();
            if (v != null) out.add(v);
        }
        return out;
    }

    private static String str(JsonObject o, String k, String def) {
        if (o.has(k) && !o.get(k).isJsonNull()) {
            String v = o.get(k).getAsString();
            return v == null ? def : v;
        }
        return def;
    }

    private static int intOf(JsonObject o, String k, int def) {
        if (o.has(k) && !o.get(k).isJsonNull()) {
            try {
                return o.get(k).getAsInt();
            } catch (Exception ignore) {
                return def;
            }
        }
        return def;
    }
}
