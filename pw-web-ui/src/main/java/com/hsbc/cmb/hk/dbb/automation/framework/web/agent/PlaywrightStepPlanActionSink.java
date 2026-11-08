package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.execution.StepPlanActionSink;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.navigation.SemanticElementResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.navigation.SemanticMatch;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservationProvider;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@link StepPlanActionSink} 的首个<b>生产</b>实现：把 BDD 步骤里的 {@code String target}
 * 落到真实 Playwright 页面，且支持<b>零元素定位输入</b>。
 *
 * <p><b>双模 target（向后兼容）</b>：</p>
 * <ul>
 *   <li>{@code role=button;name=Submit} 等 canonical 定位符 → 直通 {@link LocatorResolver}，
 *       既有生成 case 一条不改；</li>
 *   <li>其余任意业务描述 / 菜单文案（{@code Payments}、{@code 转账}、NLS key）→
 *       <b>当次实时观察页面</b>，经 {@link SemanticElementResolver} 解析成 Role-First 定位符。</li>
 * </ul>
 *
 * <p><b>逐级点击</b>：连续多个 {@code clickOn("菜单文案")} 每次都重新观察，天然构成
 * 「按菜单文案点一级 → 页面变化 → 再观察 → 点下一级」；{@link #navigateByMenu(List)} 只是
 * 这段序列的便捷封装。框架不持有任何选择器或功能路由表，路径完全由运行时页面决定。</p>
 *
 * <p><b>Page 必须惰性取得</b>：登录、{@code storageState} 注入、场景/feature 收尾、
 * {@code scheduleContextRebuild}、{@code restartBrowser} 都会关闭并重建 Context/Page。
 * 因此本类持有 {@code Supplier<Page>}（标准装配为 {@code PlaywrightManager::getPage}），
 * <b>每个动作都重新取当前 Page</b>，并在同一动作内用同一 Page 完成「观察 + 定位 + 执行」，
 * 杜绝"click 时拿的是上一个场景的 Page"。</p>
 *
 * <p><b>安全边界</b>：导航 URL 必须由调用方显式传入 {@code urlPolicy}（
 * {@link #denyAll()} / {@link #allowingHosts(String...)}），缺省不隐式放行任意地址；
 * 语义解析歧义时由 {@link SemanticElementResolver} fail-closed 抛异常，绝不猜元素。</p>
 *
 * <p><b>线程模型</b>：本实例由 {@code StepPlanActionSinks} 的 ThreadLocal 绑定到单场景线程，
 * 不引入跨场景共享可变状态；{@code popupPage} 用 volatile 保证 popup 切换的可见性，
 * 且每场景会创建新 sink 自动复位。日志经本类 logger 输出，保持生产溯源一致。</p>
 */
public final class PlaywrightStepPlanActionSink implements StepPlanActionSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaywrightStepPlanActionSink.class);
    private static final Pattern CANONICAL =
            Pattern.compile("^(role|testid|id|text|css)=", Pattern.CASE_INSENSITIVE);
    /** 单条菜单路径的最大级数，防止需求解析异常导致无界点击。 */
    public static final int MAX_MENU_DEPTH = 12;

    private final Supplier<Page> pageSupplier;
    private final Function<Page, PageObservationProvider> observationFactory;
    private final NlsCatalog nls;
    private final Predicate<String> urlPolicy;
    private final SemanticElementResolver resolver;
    private final String pageRef;
    private volatile Page popupPage;

    /**
     * 标准装配（惰性 Page）：使用框架自带观察源与默认解析器。
     *
     * @param pageSupplier 当前 Page 的惰性来源，通常为 {@code PlaywrightManager::getPage}；不可在构造期取 Page
     * @param nls          多语言目录，单语言环境可传 null
     * @param urlPolicy    导航白名单策略，必须显式提供（不可为 null）
     */
    public PlaywrightStepPlanActionSink(Supplier<Page> pageSupplier, NlsCatalog nls,
                                        Predicate<String> urlPolicy) {
        this(pageSupplier, p -> new PlaywrightPageObservationProvider(p, nls), nls, urlPolicy,
                new SemanticElementResolver(), "semantic");
    }

    /** 兼容装配：固定 Page（适用于测试或确认不会重建 Context 的短流程）。 */
    public PlaywrightStepPlanActionSink(Page page, NlsCatalog nls, Predicate<String> urlPolicy) {
        this(fixed(page), p -> new PlaywrightPageObservationProvider(p, nls), nls, urlPolicy,
                new SemanticElementResolver(), "semantic");
    }

    /** 完整注入装配（惰性 Page + 自定义观察源，供 SPI 与测试使用）。 */
    public PlaywrightStepPlanActionSink(Supplier<Page> pageSupplier,
                                        Function<Page, PageObservationProvider> observationFactory,
                                        NlsCatalog nls,
                                        Predicate<String> urlPolicy,
                                        SemanticElementResolver resolver,
                                        String pageRef) {
        this.pageSupplier = Objects.requireNonNull(pageSupplier, "pageSupplier");
        this.observationFactory = Objects.requireNonNull(observationFactory, "observationFactory");
        this.nls = nls;
        this.urlPolicy = Objects.requireNonNull(urlPolicy,
                "urlPolicy 必须显式提供：使用 denyAll() 或 allowingHosts(...)");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.pageRef = pageRef == null || pageRef.isBlank() ? "semantic" : pageRef;
    }

    /** 完整注入装配（固定 Page，保留给既有调用方与单测）。 */
    public PlaywrightStepPlanActionSink(Page page,
                                        Function<Page, PageObservationProvider> observationFactory,
                                        NlsCatalog nls,
                                        Predicate<String> urlPolicy,
                                        SemanticElementResolver resolver,
                                        String pageRef) {
        this(fixed(page), observationFactory, nls, urlPolicy, resolver, pageRef);
    }

    /** 默认拒绝一切导航（fail-closed）。 */
    public static Predicate<String> denyAll() {
        return url -> false;
    }

    /** 只允许指定 host 及其子域；非法 URL / 无 host（如 about:blank）一律拒绝。 */
    public static Predicate<String> allowingHosts(String... hosts) {
        Set<String> allowed = Arrays.stream(hosts)
                .filter(Objects::nonNull)
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return url -> {
            if (url == null) {
                return false;
            }
            try {
                String host = URI.create(url).getHost();
                if (host == null) {
                    return false;
                }
                String normalized = host.toLowerCase(Locale.ROOT);
                return allowed.contains(normalized)
                        || allowed.stream().anyMatch(h -> normalized.endsWith("." + h));
            } catch (IllegalArgumentException e) {
                return false;
            }
        };
    }

    /**
     * 按菜单文案序列逐级点击进入功能页；每一级都会重新观察当前页面，
     * 因此菜单展开、异步加载、路由跳转后的新文案都能被下一级看到。
     *
     * @param menuLabels 需求解析出的菜单文案序列（一级一个，最多 {@link #MAX_MENU_DEPTH} 级）
     */
    public void navigateByMenu(List<String> menuLabels) {
        Objects.requireNonNull(menuLabels, "menuLabels");
        if (menuLabels.isEmpty()) {
            throw new IllegalArgumentException("menuLabels 不可为空序列");
        }
        if (menuLabels.size() > MAX_MENU_DEPTH) {
            throw new IllegalArgumentException(
                    "菜单级数 " + menuLabels.size() + " 超过上限 " + MAX_MENU_DEPTH);
        }
        for (int i = 0; i < menuLabels.size(); i++) {
            String label = menuLabels.get(i);
            LOGGER.info("[semantic-nav] menu step {}/{}: [{}]", i + 1, menuLabels.size(), label);
            clickOn(label);
        }
    }

    @Override
    public void navigateTo(String url) {
        String target = Objects.requireNonNull(url, "url");
        if (!urlPolicy.test(target)) {
            throw new IllegalArgumentException(
                    "Navigation blocked by urlPolicy (fail-closed): " + target);
        }
        LOGGER.info("[semantic-nav] navigate {}", target);
        currentPage().navigate(target);
    }

    @Override
    public void clickOn(String target) {
        locate(target, SemanticElementResolver.PreferredRoles.CLICK).click();
    }

    @Override
    public void fillWith(String target, String value) {
        locate(target, SemanticElementResolver.PreferredRoles.INPUT).fill(value);
    }

    @Override
    public void selectIn(String target, String option) {
        locate(target, SemanticElementResolver.PreferredRoles.SELECT).selectOption(option);
    }

    @Override
    public void check(String target) {
        locate(target, SemanticElementResolver.PreferredRoles.TOGGLE).check();
    }

    @Override
    public void uncheck(String target) {
        locate(target, SemanticElementResolver.PreferredRoles.TOGGLE).uncheck();
    }

    @Override
    public void waitFor(String target) {
        locate(target, SemanticElementResolver.PreferredRoles.ANY).waitFor();
    }

    /** {@code Locator.waitFor()} 默认 state=visible，无需额外状态参数。 */
    @Override
    public void expectVisible(String target) {
        locate(target, SemanticElementResolver.PreferredRoles.ANY).waitFor();
    }

    /**
     * 业务文案模式：断言当前观察中存在可见且文案匹配的元素；
     * canonical 模式：断言该定位符指向的元素存在且文本非空。
     */
    @Override
    public void expectText(String target) {
        String text = Objects.requireNonNull(target, "target");
        if (CANONICAL.matcher(text.trim()).find()) {
            String actual = locate(text, SemanticElementResolver.PreferredRoles.ANY).textContent();
            if (actual == null || actual.isBlank()) {
                throw new AssertionError("Expected non-empty text on [" + text + "]");
            }
            return;
        }
        PageObservation snapshot = observe(currentPage());
        boolean present = resolver.rank(snapshot, text, SemanticElementResolver.PreferredRoles.ANY)
                .stream().anyMatch(m -> m.score() >= SemanticElementResolver.MIN_ACCEPTED_SCORE);
        if (!present) {
            throw new AssertionError("Expected text/description not visible on page: [" + text + "]");
        }
        LOGGER.info("[semantic-nav] expectText matched [{}]", text);
    }

    /**
     * 切到当前 Context 最近打开的页面。popup override 在该 sink 生命周期内有效；
     * 若页面已被关闭则自动回落 {@code pageSupplier}（如场景内 Context 重建），此时需重新调用本方法。
     */
    @Override
    public void switchToPopup() {
        Page current = currentPage();
        List<Page> pages = current.context().pages();
        if (pages.isEmpty()) {
            throw new IllegalStateException("No page available for popup switch");
        }
        Page popup = pages.get(pages.size() - 1);
        if (popup == current) {
            throw new IllegalStateException("No new popup detected on current context");
        }
        popupPage = popup;
        LOGGER.info("[semantic-nav] switched to popup; pageRef={}", pageRef);
    }

    private Locator locate(String target, Set<String> preferredRoles) {
        String value = Objects.requireNonNull(target, "target");
        Page current = currentPage();
        LocatorSpec spec;
        if (CANONICAL.matcher(value.trim()).find()) {
            spec = LocatorSpec.parse(value.trim());
        } else {
            PageObservation snapshot = observe(current);
            SemanticMatch match = resolver.resolve(snapshot, value, preferredRoles);
            LOGGER.info("[semantic-nav] [{}] -> {}", value, match.describe());
            spec = match.locator();
        }
        return LocatorResolver.resolve(current, spec, nls);
    }

    /** 每个动作都按当前 Page 重建观察源，保证 Context/Page 重建后不会复用旧观察。 */
    private PageObservation observe(Page current) {
        PageObservationProvider provider = Objects.requireNonNull(
                observationFactory.apply(current), "observationFactory returned null");
        return Objects.requireNonNull(provider.observe(pageRef), "observation returned null");
    }

    private Page currentPage() {
        Page popup = popupPage;
        if (popup != null) {
            if (!popup.isClosed()) {
                return popup;
            }
            popupPage = null;
        }
        return Objects.requireNonNull(pageSupplier.get(), "pageSupplier returned null Page");
    }

    private static Supplier<Page> fixed(Page page) {
        Objects.requireNonNull(page, "page");
        return () -> page;
    }
}
