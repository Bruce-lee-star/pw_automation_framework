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
         * 元素的 {@code src} 属性（img/script/iframe 等）。对图片类元素是天然定位依据：
         * 文件名（URL 末段）稳定且通常唯一——{@code img[src$="Google_play_badge_EN.png"]} 远比
         * 深层 {@code nth-of-type} 结构链健壮。值是否随机/带查询串由 {@code SelectorStability} 判定。
         */
        public final String src;
        /**
         * 可定位的语义属性（{@code data-*} + {@code aria-label}，属性名 → 值）。
         *
         * <p>{@code data-i18n} / {@code data-qa} / {@code data-test} / {@code data-automation-id} 由业务书写，
         * 值稳定且语义清晰，通常一个属性就能唯一定位——比 class token 与结构路径都可靠。
         * 值是否随机（含 hash/序号）由 {@code SelectorStability} 判定后决定精确或部分匹配。
         */
        public final Map<String, String> attrs;
        /**
         * 元素当前的勾选态（仅 checkbox / radio 有意义；其余元素恒 {@code false}）。
         *
         * <p>生成侧据此区分 {@code check()} 与 {@code uncheck()}：录制时用户「取消勾选」，
         * {@code checked=false}，产物必须是 {@code uncheck()} 而不是恒定的 {@code check()}。
         */
        public final boolean checked;
        /**
         * 祖先元素内部的<b>后代锚点</b>（可直接用于 CSS 的段，如 {@code [data-i18n="title_triage"]}、
         * {@code [data-testid="row-7"]}），最多 6 个、深度 6。
         *
         * <p>用途：结构相同、文案相同的双胞胎（「Continue」在隐藏 modal 与未激活分页里各一份）靠祖先
         * 自身属性区分不了，但祖先内部往往有唯一子锚点 ⇒ 生成
         * {@code div.page:has([data-i18n="title_triage"]) [data-i18n="continue"]}。
         * {@code :has()} 是原生 CSS（Chromium 105+），{@code querySelectorAll} 与 Playwright 引擎都支持。
         */
        public final List<String> childAnchors;
        /**
         * 元素内部「状态播报区」（{@code aria-live} / {@code role=status|alert|log}）的文本，
         * 多个以空格拼接；没有则为空串。
         *
         * <p><b>为什么单独采集</b>：这类节点是给读屏器播报「正在加载 / 出错」的公告，不是控件标签，
         * 却会混进浏览器算出的可访问名（HSBC 的按钮里常驻 {@code "Loading, please wait."}，
         * AX 名成了 {@code "Loading, please wait. Continue"}）。页面侧算名时已跳过它们，
         * 这里把播报文本一并带出，供 Java 侧校验「AX 名 = 播报文本 + 稳定标签」后收窄名字
         * （见 {@code PickNamePolicy#stableName}）——否则产物会得到 {@code name="Loading, please wait. Continue"}
         * 与字段名 {@code loadingPleaseWaitContinueBtn}。
         */
        public final String statusText;

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
            this(role, name, tag, id, testid, type, placeholder, alt, title, value,
                    text, label, nth, cssSelector, shadowHosts, className, xpath, nameAttr, attrs, "");
        }

        public Fact(String role, String name, String tag, String id, String testid, String type,
                    String placeholder, String alt, String title, String value,
                    String text, String label, int nth, String cssSelector, List<String> shadowHosts,
                    String className, String xpath, String nameAttr, Map<String, String> attrs, String src) {
            this(role, name, tag, id, testid, type, placeholder, alt, title, value, text, label, nth,
                    cssSelector, shadowHosts, className, xpath, nameAttr, attrs, src, false);
        }

        public Fact(String role, String name, String tag, String id, String testid, String type,
                    String placeholder, String alt, String title, String value,
                    String text, String label, int nth, String cssSelector, List<String> shadowHosts,
                    String className, String xpath, String nameAttr, Map<String, String> attrs,
                    String src, boolean checked) {
            this(role, name, tag, id, testid, type, placeholder, alt, title, value, text, label, nth,
                    cssSelector, shadowHosts, className, xpath, nameAttr, attrs, src, checked, List.of());
        }

        public Fact(String role, String name, String tag, String id, String testid, String type,
                    String placeholder, String alt, String title, String value,
                    String text, String label, int nth, String cssSelector, List<String> shadowHosts,
                    String className, String xpath, String nameAttr, Map<String, String> attrs,
                    String src, boolean checked, List<String> childAnchors) {
            this(role, name, tag, id, testid, type, placeholder, alt, title, value, text, label, nth,
                    cssSelector, shadowHosts, className, xpath, nameAttr, attrs, src, checked,
                    childAnchors, "");
        }

        /**
         * 全字段构造（含状态播报区文本）。
         *
         * @param statusText 元素内部 {@code aria-live} / {@code role=status|alert|log} 节点文本；{@code null} 视为空
         */
        public Fact(String role, String name, String tag, String id, String testid, String type,
                    String placeholder, String alt, String title, String value,
                    String text, String label, int nth, String cssSelector, List<String> shadowHosts,
                    String className, String xpath, String nameAttr, Map<String, String> attrs,
                    String src, boolean checked, List<String> childAnchors, String statusText) {
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
            this.src = src == null ? "" : src;
            this.attrs = (attrs == null || attrs.isEmpty())
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
            this.checked = checked;
            this.childAnchors = (childAnchors == null || childAnchors.isEmpty())
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(childAnchors));
            this.statusText = statusText == null ? "" : statusText.trim();
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
    /**
     * 稳健 XPath 兜底候选（借父级 / 子级属性锚定，由注入脚本 {@code codegen-inject.js#robustXPaths} 生成，
     * 每个都已在校验文档内恰好命中 1 个元素）。仅当 CSS 候选（含 descendantAnchorSel / 祖先作用域前缀）全部
     * 失效时才被 {@code CodegenScorer} 采用——比脆弱的绝对路径（{@code /html/body/div[9]/…}）更稳。
     */
    public final List<String> robustXpaths;
    /**
     * 「这是一组元素」的整组提示；{@code null} = 目标不是集合项（或容器无法唯一定位）。
     *
     * <p>由注入脚本在元素所属文档内判定（见 {@link ListHint}），不是一个"猜测"：
     * 只有「目标角色是集合项 + 容器可唯一定位 + 容器子节点全是同类项」时才给出，
     * 三者缺一都会让 {@code get(i)} 的序号与运行期不一致（点错项），故一律不给。
     */
    public final ListHint list;

    public CodegenFacts(Fact self, List<Fact> ancestors, List<String> robustXpaths) {
        this(self, ancestors, robustXpaths, null);
    }

    public CodegenFacts(Fact self, List<Fact> ancestors, List<String> robustXpaths, ListHint list) {
        this.self = self;
        this.ancestors = ancestors;
        this.robustXpaths = (robustXpaths == null || robustXpaths.isEmpty())
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(robustXpaths));
        this.list = list;
    }

    /**
     * 列表项成组提示（一次拾取 ⇒ 一个 {@code PageElementList}）。
     *
     * <p><b>现场（2026-11-15）</b>：HSBC 重置密码的密保问题下拉里，10 个 {@code li[role=option]}
     * 的可访问名各不相同（"Name a memorable city or country" / "…television character"…）。
     * 旧的组判定以 {@code role|name} 为基座，名字不同 ⇒ 永远成不了组，于是用户点一个选项只得到
     * {@code @Element("#security-question-1_option_4") public PageElement page;}——
     * 既看不到整组，也无法 {@code get(i)} 按题号取项。
     *
     * <p>三条字段的语义：
     * <ul>
     *   <li>{@code selector} —— 作用域 + {@code tag[role=...]}（如
     *       {@code ul[aria-labelledby="label_sq1"] > li[role="option"]}），命中<b>整组</b>；</li>
     *   <li>{@code count} —— 整组成员数（{@code >=2} 才有意义）；</li>
     *   <li>{@code index} —— <b>用户实际拾取的那一项</b>在组内的 0 基序号，Step 里据此生成
     *       {@code get(index)}，保证回放点的还是同一个选项。</li>
     * </ul>
     *
     * @param selector 整组定位器（相对元素自身文档 / shadow root）
     * @param count    整组成员数
     * @param index    被拾取项在组内的 0 基序号
     */
    public record ListHint(String selector, int count, int index) {

        /**
         * 是否可用于生成一组元素。
         *
         * <p>{@code selector} 非空 + 至少 2 项 + 序号落在组内，三条同时成立才可信：
         * 缺一条就退化为单元素（旧行为），绝不生成"序号对不上"的 {@code get(n)}。
         */
        public boolean usable() {
            return selector != null && !selector.isBlank() && count >= 2 && index >= 0 && index < count;
        }
    }

    /**
     * 从注入脚本 {@code getInfo} 的 JSON 载荷解析（{@code {self:{...}, ancestors:[...], list:{...}}}）。
     *
     * <p>{@code list} 为可选字段：旧版注入脚本没有它 ⇒ {@code null} ⇒ 行为与之前完全一致。
     */
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
        return new CodegenFacts(self, ancestors, strList(value, "robustXpaths"), listHint(value));
    }

    /** 解析可选的 {@code list} 提示；缺字段 / 类型不符一律 {@code null}（视作"不是集合项"）。 */
    private static ListHint listHint(JsonObject value) {
        if (!value.has("list") || !value.get("list").isJsonObject()) return null;
        JsonObject o = value.getAsJsonObject("list");
        String selector = str(o, "selector", "");
        if (selector.isBlank()) return null;
        return new ListHint(selector, intOf(o, "count", 0), intOf(o, "index", -1));
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
                mapOf(o, "attrs"),
                str(o, "src", ""),
                boolOf(o, "checked", false),
                strList(o, "childAnchors"),
                str(o, "statusText", ""));
    }

    /** 布尔取值：缺失 / 非布尔一律回落默认值（旧版注入脚本没有该字段）。 */
    private static boolean boolOf(JsonObject o, String k, boolean def) {
        if (!o.has(k) || o.get(k).isJsonNull()) return def;
        JsonElement v = o.get(k);
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) return def;
        return v.getAsBoolean();
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
     * <p>空字符串项<b>原样保留</b>：链上任一层失效即说明事实不可信，必须由定位侧判定"不可用"
     * （本次拾取按失败处理，<b>没有坐标兜底</b>），静默丢掉中间层会去错误的 shadow root 里找元素。
     */
    private static List<String> strList(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) return List.of();
        JsonElement el = o.get(k);
        if (!el.isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (item == null || item.isJsonNull()) continue;
            // 与 str() 同源：数组元素若是对象/数组，getAsString() 会抛 IllegalStateException，
            // 进而让整条拾取被 extract 的 catch 吞掉。类型不符不能抛，也不能"丢掉一层"
            // （shadow 链丢层会错指向别的 shadow root），按本方法既有契约记为「失效层」空串，
            // 由定位侧判不可用（无坐标兜底）。
            if (!item.isJsonPrimitive()) {
                out.add("");
                continue;
            }
            String v = item.getAsString();
            if (v != null) out.add(v);
        }
        return out;
    }

    /**
     * 字符串取值：缺失 / {@code null} / <b>非原始值</b>一律回落默认值。
     *
     * <p>必须显式判 {@code isJsonPrimitive()}：{@code JsonElement#getAsString()} 对 JSON 数组/对象会抛
     * {@code IllegalStateException}，而本方法的调用方 {@code fromJson} 被 {@code extract} 的
     * {@code catch (Exception)} 包着——一次类型异常会让<b>整条拾取静默失败</b>（用户表现为"点了没反应"）。
     * 同文件的 {@link #boolOf} / {@link #mapOf} 早已做类型判定，此处保持一致。
     */
    private static String str(JsonObject o, String k, String def) {
        if (!o.has(k) || o.get(k).isJsonNull()) return def;
        JsonElement el = o.get(k);
        if (!el.isJsonPrimitive()) return def;
        String v = el.getAsString();
        return v == null ? def : v;
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
