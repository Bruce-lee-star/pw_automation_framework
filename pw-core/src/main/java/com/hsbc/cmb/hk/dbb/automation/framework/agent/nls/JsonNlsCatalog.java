package com.hsbc.cmb.hk.dbb.automation.framework.agent.nls;

import com.fasterxml.jackson.core.type.TypeReference;
import com.hsbc.cmb.hk.dbb.automation.framework.common.context.LanguageState;
import com.hsbc.cmb.hk.dbb.automation.framework.common.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 nls json 的 {@link NlsCatalog} 实现（设计 §4.7）。
 *
 * <p><b>文件格式与 codegen 完全一致</b>（便于直接复用业务已有的 nls 资源，无需转换）：</p>
 * <pre>{@code
 * {
 *   "en": { "login.username": "Username", "login.submit": "Log on" },
 *   "zh": { "login.username": "用户名", "login.submit": "登录" }
 * }
 * }</pre>
 *
 * <p><b>语言标签形态</b>：顶层 key 当作语言标识<b>原样</b>使用，不区分大小写/地区格式——
 * 既支持 {@code "en"}/{@code "zh"} 这种短标签，也支持业务常见的<b>带地区标签</b>
 * {@code "en-US"}/{@code "zh-CN"}/{@code "zh-HK"}（如 {@code NLS_footer.json}）。
 * 唯一约束：调用方传入的语言标识必须<b>精确等于</b> json 顶层 key（如用 {@code "en-US"} 而非 {@code "en"}），
 * 因为目录按该字符串直接索引。key 本身也可以是英文短语（如 {@code "Privacy and Security footer"}），
 * 定位只认 key、文案运行期按语言解析，故短语 key 与短 id key 同等对待。</p>
 *
 * <p><b>语言来源</b>：优先取构造时指定的语言（便于<b>同一份资产跨语言巡检</b>——en 验一遍、zh 再验一遍）；
 * 未指定则取 {@link LanguageState#getLanguage()}（业务切语言时已由框架设置，与 codegen/page object 同一来源）。
 * 二者都没有时立刻抛 {@link IllegalStateException}，而不是"静默查不到 key 退化成字面文案"——后者在多语言下
 * 必然表现为"某种语言全挂"，极难定位。</p>
 *
 * <p><b>多文件</b>：按声明顺序跨文件查找，命中即止（{@code common.nls.json} + {@code login.nls.json} 的常见组织方式），
 * 与 {@code NLSUtils.bind(paths...)} 语义一致。</p>
 *
 * <p><b>路径</b>：classpath 相对路径（{@code nls/login.nls.json}）或文件系统路径均可。</p>
 *
 * <p><b>反查策略</b>（{@link #keyOf(String)}，与 codegen 的 {@code NlsReverseLookup} 对齐）：
 * 精确命中 → 归一化命中 → 模板正则 → 子串兜底（带长度护栏，避免容器聚合出的整页文案误命中）。
 * 索引按语言惰性构建并缓存，线程安全。</p>
 */
public final class JsonNlsCatalog implements NlsCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(JsonNlsCatalog.class);

    /** 可反查名字的最大长度：超过即视为容器聚合出的整页文案，不做子串兜底（与 codegen 一致）。 */
    private static final int MAX_REVERSE_NAME = 120;

    private static final TypeReference<Map<String, Map<String, String>>> LANG_TABLE_TYPE =
            new TypeReference<Map<String, Map<String, String>>>() {
            };

    private final List<String> files;
    private final String languageOverride;
    /** lang -> (key -> value) */
    private final Map<String, Map<String, String>> tables = new ConcurrentHashMap<>();
    /** lang -> (可见文本 -> key)；LinkedHashMap 保证"文件声明顺序先到先得"确定可测 */
    private final Map<String, Map<String, String>> reverseIndex = new ConcurrentHashMap<>();
    /** lang -> 含模板变量的条目（只能按正则反查） */
    private final Map<String, List<Map.Entry<String, String>>> templateEntries = new ConcurrentHashMap<>();

    private JsonNlsCatalog(String languageOverride, List<String> files) {
        this.languageOverride = languageOverride;
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
    }

    /** 使用 {@link LanguageState} 的当前语言。 */
    public static JsonNlsCatalog of(String... files) {
        return new JsonNlsCatalog(null, Arrays.asList(files));
    }

    /** 固定语言（跨语言巡检/单测用，不依赖线程状态）。 */
    public static JsonNlsCatalog ofLanguage(String language, String... files) {
        return new JsonNlsCatalog(language, Arrays.asList(files));
    }

    /** 固定语言（列表形式）。 */
    public static JsonNlsCatalog ofLanguage(String language, List<String> files) {
        return new JsonNlsCatalog(language, files);
    }

    /** 绑定的文件列表（按查找顺序）。 */
    public List<String> files() {
        return files;
    }

    @Override
    public String language() {
        String lang = languageOverride != null ? languageOverride : LanguageState.getLanguage();
        if (lang == null || lang.isBlank()) {
            throw new IllegalStateException("[nls] current language is not set — call "
                    + "NLSUtils.setLanguage(\"zh\"/\"en\"/...) (or LanguageState.setLanguage) before analysis, "
                    + "because nls bundles are keyed by language: { lang: { key: value } }. files=" + files);
        }
        return lang;
    }

    @Override
    public boolean hasKey(String key) {
        return key != null && table().containsKey(key);
    }

    @Override
    public String value(String key) {
        String lang = language();
        String v = table().get(key);
        if (v == null) {
            throw new IllegalStateException("[nls] missing key '" + key + "' for lang=" + lang
                    + " in files " + files + " (known keys=" + table().size() + ")");
        }
        return v;
    }

    @Override
    public String visibleText(String key) {
        return NlsText.visibleText(value(key));
    }

    @Override
    public String keyOf(String visibleName) {
        if (visibleName == null) {
            return null;
        }
        String trimmed = visibleName.trim();
        if (trimmed.isEmpty() || files.isEmpty()) {
            return null;
        }
        Map<String, String> idx = reverseIndex();
        String hit = idx.get(trimmed);
        if (hit == null) {
            hit = idx.get(NlsText.visibleText(trimmed));
        }
        if (hit != null) {
            return hit;
        }
        // 模板值（如 "Welcome {{user}}"）只能按正则反查
        for (Map.Entry<String, String> e : templates()) {
            if (NlsText.pattern(e.getValue()).matcher(trimmed).find()) {
                return e.getKey();
            }
        }
        // 子串兜底：nls 值常内嵌 HTML，其可见文本是元素名字的超集，精确匹配会漏
        if (trimmed.length() < 2 || trimmed.length() > MAX_REVERSE_NAME) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : idx.entrySet()) {
            String v = e.getKey().toLowerCase(Locale.ROOT);
            if (!v.isEmpty() && (v.contains(lower) || lower.contains(v))) {
                return e.getValue();
            }
        }
        return null;
    }

    @Override
    public Set<String> keys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(table().keySet()));
    }

    // ————————————————————————— 内部：加载与索引 —————————————————————————

    private Map<String, String> table() {
        String lang = language();
        return tables.computeIfAbsent(lang, this::loadTable);
    }

    private Map<String, String> reverseIndex() {
        String lang = language();
        table();   // 先确保该语言的表已加载（否则下面构建索引会读到空表）
        return reverseIndex.computeIfAbsent(lang, this::buildReverseIndex);
    }

    private List<Map.Entry<String, String>> templates() {
        String lang = language();
        table();
        return templateEntries.computeIfAbsent(lang, this::buildTemplateEntries);
    }

    /** 多文件按声明顺序合并，先到先得（与 NLSUtils 多文件语义一致）。 */
    private Map<String, String> loadTable(String lang) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String file : files) {
            Map<String, Map<String, String>> byLang = readTables(file);
            Map<String, String> table = byLang.get(lang);
            if (table == null) {
                if (!byLang.isEmpty()) {
                    LOG.warn("[nls] no table for lang='{}' in {} (available: {})", lang, file, byLang.keySet());
                }
                continue;
            }
            int added = 0;
            for (Map.Entry<String, String> e : table.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                if (merged.putIfAbsent(e.getKey(), e.getValue()) == null) {
                    added++;
                }
            }
            LOG.info("[nls] loaded {} keys from {} (lang={})", added, file, lang);
        }
        return merged;
    }

    private Map<String, String> buildReverseIndex(String lang) {
        Map<String, String> idx = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : tables.getOrDefault(lang, Collections.emptyMap()).entrySet()) {
            String raw = e.getValue();
            if (NlsText.isTemplate(raw)) {
                continue;
            }
            String visible = NlsText.visibleText(raw);
            if (!visible.isEmpty()) {
                idx.putIfAbsent(visible, e.getKey());
            }
            if (!raw.trim().isEmpty()) {
                idx.putIfAbsent(raw.trim(), e.getKey());
            }
        }
        LOG.info("[nls] reverse index built: {} entries (lang={})", idx.size(), lang);
        return idx;
    }

    private List<Map.Entry<String, String>> buildTemplateEntries(String lang) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : tables.getOrDefault(lang, Collections.emptyMap()).entrySet()) {
            if (NlsText.isTemplate(e.getValue())) {
                out.add(e);
            }
        }
        return out;
    }

    private static Map<String, Map<String, String>> readTables(String file) {
        try {
            Map<String, Map<String, String>> parsed = JsonUtils.fromJson(readFile(file), LANG_TABLE_TYPE);
            if (parsed == null) {
                LOG.warn("[nls] failed to parse {}", file);
                return Collections.emptyMap();
            }
            return parsed;
        } catch (RuntimeException e) {
            // 单个文件不可用不应中断整条分析链路：记日志后按"该文件无内容"处理，由缺失 key 的显式报错兜底
            LOG.warn("[nls] failed to load {}: {}", file, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** classpath 优先，其次文件系统（与 {@code NLSUtils} 一致）。 */
    private static String readFile(String filePath) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(filePath)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOG.debug("[nls] classpath resource not readable, try filesystem: {}", filePath);
        }
        Path p = Paths.get(filePath);
        if (Files.exists(p)) {
            try {
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("[nls] read failed: " + filePath, e);
            }
        }
        throw new IllegalStateException("[nls] file not found (classpath or filesystem): " + filePath);
    }
}
