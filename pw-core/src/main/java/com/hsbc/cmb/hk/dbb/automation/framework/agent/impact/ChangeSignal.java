package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedSkeleton;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedStep;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「这次变更动了什么」的机器可读描述（变更影响筛选的输入侧）。
 *
 * <p><b>主要来源是变更后的新录制骨架</b>（{@link #fromSkeleton}）：页面地址给出页面信号，
 * 每步 target 里的 {@code role=}/{@code name=}/{@code nameKey=} 给出元素信号
 * （{@link ElementRef} 保留角色，供代码层落点做同名同角色收窄）——不需要人工再填一份清单。
 * 也可由调用方补充关键词/标签（{@link #of}、{@link #ofElementRefs}），用于「骨架里看不出来」的变更
 * （如业务规则调整）。</p>
 *
 * <p><b>信号强度</b>（由 {@link CaseImpactScreener} 决定判定）：
 * 元素信号与标签信号视为<b>强命中</b>（必须维护），页面/关键词信号视为<b>弱命中</b>（需复核）。</p>
 *
 * <p>不可变、线程安全；所有词条在构造时归一化为小写并去空白，匹配时不再做二次转换。</p>
 */
public final class ChangeSignal {

    private static final Pattern NAME_ATTR = Pattern.compile("(?:^|[;,\\s])(name|nameKey)=([^;]+)");
    private static final Pattern ROLE_ATTR = Pattern.compile("(?:^|[;,\\s])role=([^;]+)");

    private final Set<ElementRef> elementRefs;
    private final Set<String> selectors;
    private final Set<String> pages;
    private final Set<String> elements;
    private final Set<String> keywords;
    private final Set<String> tags;

    private ChangeSignal(Set<ElementRef> elementRefs, Set<String> selectors, Set<String> pages,
                         Set<String> keywords, Set<String> tags) {
        this.elementRefs = immutableRefs(elementRefs);
        this.elements = namesOf(this.elementRefs);
        this.selectors = immutableSelectors(selectors);
        this.pages = normalize(pages);
        this.keywords = normalize(keywords);
        this.tags = normalize(tags);
    }

    /**
     * 追加 CSS/XPath 选择器信号（role 定位不了时的兜底通道），返回新实例。
     *
     * <p>选择器保留原始大小写（CSS 属性值/class 可能大小写敏感），仅在比较时归一。
     * 传入 null/空白会被忽略。</p>
     */
    public ChangeSignal withSelectors(Collection<String> extraSelectors) {
        if (extraSelectors == null || extraSelectors.isEmpty()) {
            return this;
        }
        Set<String> merged = new LinkedHashSet<>(selectors);
        merged.addAll(extraSelectors);
        return new ChangeSignal(elementRefs, merged, pages, keywords, tags);
    }

    /** 显式构造（补充信号，或用于骨架之外的变更）；元素只有名字、没有角色。 */
    public static ChangeSignal of(Collection<String> pages, Collection<String> elements,
                                  Collection<String> keywords, Collection<String> tags) {
        Set<ElementRef> refs = new LinkedHashSet<>();
        if (elements != null) {
            for (String element : elements) {
                if (element != null && !element.isBlank()) {
                    refs.add(ElementRef.ofName(element));
                }
            }
        }
        return new ChangeSignal(refs, Set.of(), asSet(pages), asSet(keywords), asSet(tags));
    }

    /**
     * 显式构造（保留元素角色，供代码层落点做同名同角色收窄）。
     *
     * @param pages       页面信号（如 {@code /logon}）
     * @param elementRefs 元素引用（role 可为空）
     * @param keywords    关键词信号
     * @param tags        标签信号（{@code @xxx}）
     */
    public static ChangeSignal ofElementRefs(Collection<String> pages, Collection<ElementRef> elementRefs,
                                             Collection<String> keywords, Collection<String> tags) {
        return new ChangeSignal(elementRefs == null ? Set.of() : new LinkedHashSet<>(elementRefs),
                Set.of(), asSet(pages), asSet(keywords), asSet(tags));
    }

    /**
     * 从变更后的新录制骨架派生信号。
     *
     * <p>页面：{@code pageRef} 的 path（如 {@code /logon}）与其末段（{@code logon}，作为关键词）；
     * 元素：每步 target 的 {@code role=}/{@code name=}/{@code nameKey=}——role 缺失时退化为只有名字。</p>
     */
    public static ChangeSignal fromSkeleton(RecordedSkeleton skeleton) {
        Objects.requireNonNull(skeleton, "skeleton");
        Set<String> pages = new LinkedHashSet<>();
        Set<String> keywords = new LinkedHashSet<>();
        addPageSignals(skeleton.pageRef(), pages, keywords);

        Set<ElementRef> refs = new LinkedHashSet<>();
        for (RecordedStep step : skeleton.steps()) {
            String target = step.target();
            if (target == null || target.isBlank()) {
                continue;
            }
            String role = firstGroup(ROLE_ATTR, target);
            Matcher m = NAME_ATTR.matcher(target);
            while (m.find()) {
                String value = m.group(2).trim();
                if (!value.isEmpty()) {
                    refs.add(new ElementRef(role, value));
                }
            }
        }
        return new ChangeSignal(refs, Set.of(), pages, keywords, Set.of());
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private static void addPageSignals(String pageRef, Set<String> pages, Set<String> keywords) {
        if (pageRef == null || pageRef.isBlank()) {
            return;
        }
        String path = pathOf(pageRef.trim());
        pages.add(path);
        String[] segments = path.split("[/?#]+");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isBlank()) {
                keywords.add(segments[i]);
                break;
            }
        }
    }

    /**
     * 取 URL 的 path（{@code https://host:8443/logon?x=1} → {@code /logon}）。
     *
     * <p>刻意不用 {@code java.net.URI}：非标准地址会抛受检异常，为它写一个空 catch
     * 既过不了 Checkstyle，也把「按原文处理」的语义藏进 catch 里。纯字符串解析无异常路径，
     * 对非 URL（如 {@code /logon}）原样返回。</p>
     */
    private static String pathOf(String ref) {
        int scheme = ref.indexOf("://");
        if (scheme < 0) {
            return ref;
        }
        int slash = ref.indexOf('/', scheme + "://".length());
        if (slash < 0) {
            return ref;
        }
        String path = ref.substring(slash);
        int cut = path.indexOf('?');
        if (cut < 0) {
            cut = path.indexOf('#');
        }
        return cut < 0 ? path : path.substring(0, cut);
    }

    public Set<String> pages() {
        return Collections.unmodifiableSet(pages);
    }

    /** 元素名集合（由 {@link #elementRefs()} 派生，供用例文本匹配使用）。 */
    public Set<String> elements() {
        return Collections.unmodifiableSet(elements);
    }

    /** 元素引用（含角色，供代码层落点匹配使用）。 */
    public Set<ElementRef> elementRefs() {
        return Collections.unmodifiableSet(elementRefs);
    }

    public Set<String> keywords() {
        return Collections.unmodifiableSet(keywords);
    }

    public Set<String> tags() {
        return Collections.unmodifiableSet(tags);
    }

    /** CSS/XPath 选择器信号（role 定位不了时的兜底通道）。 */
    public Set<String> selectors() {
        return Collections.unmodifiableSet(selectors);
    }

    /** 信号是否为空（全空时筛选无意义，应显式报错而不是把全部用例判 NO_CHANGE）。 */
    public boolean isEmpty() {
        return pages.isEmpty() && elements.isEmpty() && selectors.isEmpty()
                && keywords.isEmpty() && tags.isEmpty();
    }

    /** 报告头用的一行摘要。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("pages=").append(pages)
                .append(" elements=").append(describeRefs(elementRefs));
        if (!selectors.isEmpty()) {
            sb.append(" selectors=").append(selectors);
        }
        sb.append(" keywords=").append(keywords).append(" tags=").append(tags);
        return sb.toString();
    }

    private static Set<String> immutableSelectors(Collection<String> values) {
        Set<String> out = new java.util.TreeSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static String describeRefs(Set<ElementRef> refs) {
        Set<String> parts = new LinkedHashSet<>();
        for (ElementRef ref : refs) {
            parts.add(ref.describe());
        }
        return parts.toString();
    }

    private static Set<ElementRef> immutableRefs(Collection<ElementRef> refs) {
        Set<ElementRef> sorted = new TreeSet<>(Comparator.comparing(ElementRef::name).thenComparing(ElementRef::role));
        for (ElementRef ref : refs) {
            if (ref != null) {
                sorted.add(ref);
            }
        }
        return Collections.unmodifiableSet(sorted);
    }

    private static Set<String> namesOf(Set<ElementRef> refs) {
        Set<String> names = new TreeSet<>();
        for (ElementRef ref : refs) {
            names.add(ref.name());
        }
        return Collections.unmodifiableSet(names);
    }

    private static Set<String> asSet(Collection<String> values) {
        return values == null ? Set.of() : new LinkedHashSet<>(values);
    }

    private static Set<String> normalize(Collection<String> values) {
        Set<String> out = new TreeSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(out);
    }
}
