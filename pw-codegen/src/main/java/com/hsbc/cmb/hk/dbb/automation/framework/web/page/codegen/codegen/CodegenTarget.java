package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import com.microsoft.playwright.Page;

/**
 * 一次拾取会话的<b>生成目标</b>：包名 + 由被测页 URL 派生的全部类名 / 变量名。
 *
 * <h2>为什么类名只能由 URL 派生</h2>
 * <p>调用方只提供「被测页 + NLS 文件」，<b>不传 {@code pageClassName}</b>。理由：
 * <ul>
 *   <li><b>可重复</b>：同一页面多次拾取得到同一个类名，产物可 diff / 可评审，不会因各人随手命名
 *       （{@code LoginPage} / {@code Login} / {@code SignInPage}）产生大量等价重复类；</li>
 *   <li><b>可溯源</b>：产物类名 ↔ 页面 URL 一一对应，评审者拿到 {@code LogonPage} 就知道它描述
 *       {@code /portalserver/logon}；</li>
 *   <li><b>三个 Tab 天然一致</b>：Page / Step / Assert 的类名与变量名由同一处派生，
 *       不存在"Tab1 叫 A、Tab2 叫 B"的错位。</li>
 * </ul>
 *
 * <h2>派生规则</h2>
 * <pre>
 *   URL  https://host/portalserver/logon?lang=zh
 *   ├─ packageName      com.example.pages
 *   ├─ pageClassName    LogonPage            （末段 PascalCase + Page 后缀）
 *   ├─ stepPackageName  com.example.pages.steps        stepClassName  LogonSteps
 *   ├─ assertPackageName com.example.pages.assertions  assertClassName LogonAssertions
 *   └─ pageVar          logonPage            （Step/Assert 里的页面字段）
 * </pre>
 *
 * <p>子包名与 v2 对齐（{@code .steps} / {@code .assertions}），Step/Assert 类名由 pageClassName
 * 去掉 {@code Page} 后缀后加角色后缀得到（v2 用固定 {@code SampleSteps}，此处随页面派生）。
 *
 * <p>类名由<b>拾取发生时的 URL</b>派生（每次拾取都把当时页面的 URL 冻结在 {@code Pick} 上）。
 * 因此同一标签页内发生导航（A→B）会产出两个不同的 Page 类（{@code APage} / {@code BPage}），
 * 旧类不会被「Stop 时重读当前 URL」吞掉——这正是「按 URL 拆分页面类」的核心：
 * 元素永远落在它<b>被拾取那一刻</b>所属 URL 对应的 Page 类，不受后续导航影响。
 * {@link #ofPage(Page)} 仅读取当前 URL（用于日志 / 单页快速场景），生成路径统一走 {@link #of(String, String)}。
 */
public final class CodegenTarget {

    /** 生成 Page 类的默认包名（不传包名时的取值）。 */
    public static final String DEFAULT_PACKAGE = "com.example.pages";

    /** 步骤类子包（对齐 v2 {@code RoleElementStepGenerator}）。 */
    public static final String STEPS_SUBPACKAGE = ".steps";
    /** 断言类子包（对齐 v2 {@code RoleElementAssertionGenerator}）。 */
    public static final String ASSERTIONS_SUBPACKAGE = ".assertions";

    private static final String PAGE_SUFFIX = "Page";

    /** 页面类所在包（Step/Assert 的父包，其各自落 {@code .steps} / {@code .assertions} 子包）。 */
    public final String packageName;
    /** 派生所依据的 URL（可能为 null，此时类名走兜底 {@code GeneratedPage}）。 */
    public final String url;
    public final String pageClassName;
    public final String stepPackageName;
    public final String stepClassName;
    public final String assertPackageName;
    public final String assertClassName;
    /** Step / Assert 类里持有页面对象的字段名。 */
    public final String pageVar;

    private CodegenTarget(String packageName, String url) {
        this(packageName, url, null);
    }

    /** 同 {@link #of(String, String)}，但允许显式指定页面类名（用于多页面碰撞去重）。 */
    private CodegenTarget(String packageName, String url, String pageClassNameOverride) {
        this.packageName = packageName;
        this.url = url;
        this.pageClassName = (pageClassNameOverride != null && !pageClassNameOverride.isBlank())
                ? pageClassNameOverride : pageClassNameFromUrl(url);
        String base = baseOf(this.pageClassName);
        this.stepPackageName = packageName + STEPS_SUBPACKAGE;
        this.stepClassName = base + "Steps";
        this.assertPackageName = packageName + ASSERTIONS_SUBPACKAGE;
        this.assertClassName = base + "Assertions";
        this.pageVar = lowerFirst(this.pageClassName);
    }

    /**
     * @param packageName 页面类包名；null/空白取 {@link #DEFAULT_PACKAGE}
     * @param url         被测页 URL（可 null，走兜底类名）
     */
    public static CodegenTarget of(String packageName, String url) {
        String pkg = (packageName == null || packageName.isBlank()) ? DEFAULT_PACKAGE : packageName.trim();
        return new CodegenTarget(pkg, url);
    }

    /** 以被测页<b>当前</b> URL 派生目标（页面导航后再次调用会得到新类名）。 */
    public static CodegenTarget ofPage(Page page) {
        return of(DEFAULT_PACKAGE, urlOf(page));
    }

    /**
     * 显式页面类名的目标（不依赖 URL）。用于多页面场景：当多个被测页 URL 派生出相同类名时，
     * 由调用方在碰撞去重后传入去重后的类名（如 {@code LogonPage} / {@code LogonPage_2}），
     * 三套类名/变量名仍由本处统一派生，保证 Page/Step/Assert 一致。
     */
    public static CodegenTarget ofNamed(String packageName, String pageClassName) {
        String pkg = (packageName == null || packageName.isBlank()) ? DEFAULT_PACKAGE : packageName.trim();
        return new CodegenTarget(pkg, null, pageClassName);
    }

    /**
     * 由页面 URL 派生页面类名：取 path 末段 → 去查询/锚点/扩展名 → PascalCase → 补 {@code Page} 后缀。
     *
     * <p>包级私有以便单测；URL 不可解析时兜底 {@code GeneratedPage}，且<b>恒为合法 Java 标识符</b>
     * （前导数字 / 纯符号等边缘均被清洗），保证产物一定能编译。
     */
    public static String pageClassNameFromUrl(String url) {
        if (url == null || url.isBlank()) return "GeneratedPage";
        String path = url.trim();
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);

        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = (slash >= 0) ? path.substring(slash + 1) : "";
        }
        String last = "";
        String[] segs = path.split("/");
        for (int i = segs.length - 1; i >= 0; i--) {
            String s = segs[i].trim();
            if (!s.isEmpty()) { last = s; break; }
        }
        if (last.isEmpty()) last = "index";
        int dot = last.lastIndexOf('.');
        if (dot > 0) last = last.substring(0, dot);

        StringBuilder sb = new StringBuilder();
        boolean upperNext = true;
        for (int i = 0; i < last.length(); i++) {
            char c = last.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(upperNext ? Character.toUpperCase(c) : c);
                upperNext = false;
            } else {
                upperNext = true;
            }
        }
        String name = sb.toString();
        if (name.isEmpty()) return "GeneratedPage";
        if (!Character.isJavaIdentifierStart(name.charAt(0))) name = PAGE_SUFFIX + name;
        return name.endsWith(PAGE_SUFFIX) ? name : name + PAGE_SUFFIX;
    }

    /** {@code LogonPage → Logon}（不足以避免 {@code Page} 自身被截成空串时不截）。 */
    private static String baseOf(String pageClassName) {
        if (pageClassName != null && pageClassName.length() > PAGE_SUFFIX.length()
                && pageClassName.endsWith(PAGE_SUFFIX)) {
            return pageClassName.substring(0, pageClassName.length() - PAGE_SUFFIX.length());
        }
        return pageClassName;
    }

    private static String lowerFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String urlOf(Page page) {
        if (page == null) return null;
        try {
            return page.url();
        } catch (Exception ignore) {
            return null; // 页面已关闭/无法取 URL：走兜底类名
        }
    }

    @Override
    public String toString() {
        return packageName + "." + pageClassName + " (from " + url + ")";
    }
}
