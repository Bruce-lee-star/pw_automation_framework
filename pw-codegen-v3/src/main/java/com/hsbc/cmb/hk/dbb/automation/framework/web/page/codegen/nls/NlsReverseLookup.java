package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.nls;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * NLS 反查：把可访问名反查成 {@code key}，使生成产物用 {@code @RoleElement(key = "...")} 而非硬编码文案。
 *
 * <p><b>必须提前通过 {@link NLSUtils#setLanguage(String)} 设置语言</b>：nls 文件是
 * {@code {语言Key: { key: value } }} 的<b>两级</b>结构（见 {@link NLSUtils} 约定），
 * 只有先被告知当前语言，才能取到正确的 key→value 表做反查。
 * 未设置即取值会抛 {@link IllegalStateException}（与 {@link NLSUtils#get} 的强制约定一致），
 * 避免"静默查不到 key、产物退化成一堆 name=" 这类难以定位的问题。
 *
 * <p><b>为何不能再自己用 Gson 扁平解析</b>：早期实现把 nls json 当 {@code {key: value}}
 * 单层结构读取，而实际文件首层是语言（"zh"/"en"），取到的 value 是对象而非字符串 ⇒ 索引恒为空、
 * NLS 反查从未命中。现统一经 {@link NLSUtils#rawTables(String)} 取「语言 → key → value」二维表，
 * 再按当前语言取扁平表，与框架其余地方的 NLS 语义完全一致。
 *
 * <p>匹配用的是 {@link NLSUtils#visibleText(String)} 归一化后的文案：nls 值里常内嵌 {@code <b>/<img>}
 * 标签与 {@code &nbsp;} 等实体，而拾取到的是浏览器<b>渲染后的可见文本</b>，直接用原始值必然匹配不上。
 *
 * <p>支持一个或多个 nls 文件（多文件按声明顺序，命中即止）。
 */
public final class NlsReverseLookup {

    private static final Logger log = LoggerFactory.getLogger(NlsReverseLookup.class);

    /** 可反查的可访问名最大长度；超过即视为容器聚合出的整页文案，不做子串回退（见 {@link #lookup(String)}）。 */
    private static final int MAX_REVERSE_NAME = 120;

    private final String[] nlsFiles;
    /** value→key 索引；用 LinkedHashMap 保留文件声明顺序，使"先到先得"确定可测。 */
    private final Map<String, String> valueToKey = new LinkedHashMap<>();
    /** 当前语言包里存在的 key 集合：用于校验元素自带的 key（如 {@code data-i18n}）是否合法。 */
    private final Set<String> knownKeys = new HashSet<>();
    private String loadedLang;

    public NlsReverseLookup(String[] nlsFiles) {
        this.nlsFiles = (nlsFiles == null) ? new String[0] : nlsFiles.clone();
    }

    /**
     * 是否启用 NLS 反查（配置了 nls 文件即视为<b>多语言环境</b>）。
     *
     * <p>多语言环境下"匹配不到 key"意味着没有跨语言稳定的标识，此时必须禁用按文案定位
     * （{@code name}/{@code placeholder}/{@code alt}/{@code title}），改用 id/name/testid/class/结构/XPath。
     */
    public boolean enabled() {
        return nlsFiles.length > 0;
    }

    /** 反查 name；无 nls 文件或未命中时返回 null（调用方回落为 name= 定位）。 */
    public String lookup(String name) {
        if (name == null || name.isBlank() || nlsFiles.length == 0) return null;
        ensureLoaded();
        String trimmed = name.trim();
        String hit = valueToKey.get(trimmed);
        if (hit == null) hit = valueToKey.get(NLSUtils.visibleText(trimmed));
        if (hit != null) return hit;
        // 退化兜底：NLS 值常内嵌 HTML（如 {@code <b>Hello</b> World}），其可见文本是元素的超集，
        // 元素拾取的可见名只是其中一段 ⇒ 精确查不到 key。用 containsString 反查：
        // 某 value（去标签后）包含该名、或该名包含某 value，都返回其 key（与断言阶段 containsString 一致）。
        // 多命中取文件中第一个（LinkedHashMap 保序）；单字符噪声不纳入，避免误命中。
        if (trimmed.length() < 2) return null;
        // 长度护栏：可访问名超过阈值几乎必然是容器元素（main/document/generic）聚合出的整页文案
        // （含 <style> CSS 块，动辄上千字符）。对这类名做子串反查只会误命中某个恰好是其子串的 NLS 值
        // （如 bank_logo），生成永远解析不到的 role 选择器 —— 直接判为「不可反查」，回落 CSS 更稳。
        if (trimmed.length() > MAX_REVERSE_NAME) return null;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> en : valueToKey.entrySet()) {
            String v = en.getKey();
            if (v == null) continue;
            String vl = v.toLowerCase(Locale.ROOT);
            if (vl.contains(lower) || lower.contains(vl)) {
                return en.getValue();
            }
        }
        return null;
    }

    /**
     * 优先采纳元素自带的 key（如 {@code data-i18n} 字面就是 NLS key），消歧「同一可见文案命中多个 key」。
     *
     * <p><b>为什么这是"最精确"的 key</b>：{@link #lookup(String)} 只靠可见文案反查，当多个 key 的值相同
     * （如 {@code login.title} 与 {@code signin.title} 都是 "Log on"）时只能"文件顺序先到先得"，选到的 key
     * 未必是开发者给该元素的那个。而元素身上的 {@code data-i18n} 是开发显式绑定的 key，字面即 key，无歧义。
     *
     * <p>仅当 {@code explicitKey} 确实存在于当前语言包时才采纳；否则（属性缺失、或它其实是 value 而非 key）
     * 回落到 {@link #lookup(String)} 的文案反查，保持既有行为。无 nls 文件时直接返回 null。
     */
    public String lookup(String name, String explicitKey) {
        if (nlsFiles.length == 0) return null;
        ensureLoaded();
        if (explicitKey != null && !explicitKey.isBlank() && knownKeys.contains(explicitKey)) {
            return explicitKey;
        }
        return lookup(name);
    }

    /** 当前语言。 */
    public String language() {
        return loadedLang;
    }

    /**
     * 按【当前语言】构建 value→key 索引；语言切换会重新构建（同一语言只构建一次）。
     *
     * @throws IllegalStateException 语言未设置 —— 强制调用方先用 {@link NLSUtils#setLanguage} 告知框架
     */
    private void ensureLoaded() {
        String lang = NLSUtils.getLanguage();
        if (lang == null || lang.isBlank()) {
            throw new IllegalStateException("[codegen][nls] current language is not set — "
                    + "call NLSUtils.setLanguage(\"zh\"/\"en\"/...) before starting the inspector, "
                    + "because NLS bundles are keyed by language: { lang: { key: value } }.");
        }
        if (lang.equals(loadedLang)) return;
        valueToKey.clear();
        knownKeys.clear();
        for (String f : nlsFiles) load(f, lang);
        loadedLang = lang;
    }

    private void load(String file, String lang) {
        try {
            Map<String, Map<String, String>> byLang = NLSUtils.rawTables(file);
            Map<String, String> table = byLang.get(lang);
            if (table == null) {
                log.warn("[codegen][nls] no table for lang='{}' in file {} (available: {})",
                        lang, file, byLang.keySet());
                return;
            }
            int n = 0;
            for (Map.Entry<String, String> en : table.entrySet()) {
                String value = en.getValue();
                if (value == null) continue;
                knownKeys.add(en.getKey());
                // 先登记"可见文本"（最可能被拾取到的形态），再登记原始值兜底。
                String visible = NLSUtils.visibleText(value);
                if (!visible.isEmpty() && valueToKey.putIfAbsent(visible, en.getKey()) == null) n++;
                if (!value.trim().isEmpty() && valueToKey.putIfAbsent(value.trim(), en.getKey()) == null) n++;
            }
            log.info("[codegen][nls] indexed {} entries from {} (lang={})", n, file, lang);
        } catch (Exception ex) {
            log.warn("[codegen][nls] failed to load {} for lang={}: {}", file, lang, ex.getMessage());
        }
    }
}
