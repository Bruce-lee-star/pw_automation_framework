package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.BasePage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

/**
 * WEB-P1-5 种子测试：元素门面 {@code PageElement}（无浏览器，Mockito 隔离 BasePage/Page/Locator）。
 * 覆盖两种构造的入参守卫、选择器/页面访问器、以及底层 Locator 的三条解析路径
 * （动态 supplier / 页面选择器 / 组合定位 nth）。
 */
public class PageElementTest {

    /** JUnit 4 临时目录：原 JUnit5 的 {@code @TempDir} 方法参数注入在 JUnit4 无对应，改为 @Rule + 字段。 */
    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();
    private Path tempDir;

    @Before
    public void initTempDir() {
        tempDir = tempFolder.getRoot().toPath();
    }

    private static BasePage bpWith(Page page) {
        BasePage bp = mock(BasePage.class);
        when(bp.getPage()).thenReturn(page);
        return bp;
    }

    // ---------- 入参守卫 ----------

    @Test
    public void supplierConstructor_rejectsNullSupplier() {
        BasePage bp = bpWith(mock(Page.class));
        assertThrows(IllegalArgumentException.class, () -> new PageElement(null, "desc", bp));
    }

    @Test
    public void supplierConstructor_rejectsBlankDescription() {
        BasePage bp = bpWith(mock(Page.class));
        assertThrows(IllegalArgumentException.class, () -> new PageElement(() -> mock(Locator.class), "  ", bp));
    }

    @Test
    public void supplierConstructor_rejectsNullPage() {
        assertThrows(IllegalArgumentException.class, () -> new PageElement(() -> mock(Locator.class), "desc", null));
    }

    @Test
    public void selectorConstructor_rejectsBlankSelector() {
        BasePage bp = bpWith(mock(Page.class));
        assertThrows(IllegalArgumentException.class, () -> new PageElement("  ", bp));
    }

    @Test
    public void selectorConstructor_rejectsNullPage() {
        assertThrows(IllegalArgumentException.class, () -> new PageElement("#sel", null));
    }

    // ---------- 访问器 ----------

    @Test
    public void getters_exposeSelectorAndOwningPage() {
        BasePage bp = bpWith(mock(Page.class));
        PageElement el = new PageElement("#login", bp);

        assertEquals("#login", el.getSelector());
        assertSame(bp, el.getPage());
    }

    // ---------- Locator 解析路径 ----------

    @Test
    public void locatorInternal_prefersDynamicSupplier() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = new PageElement(() -> loc, "role=button", bp);

        assertSame("动态 supplier 优先，保证语言/页面切换后自动重解析", loc, el.locatorInternal());
        verify(bp).getPage();
    }

    @Test
    public void locatorInternal_fallsBackToPageSelectorResolution() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        when(bp.locatorInternal("#login")).thenReturn(loc);
        PageElement el = new PageElement("#login", bp);

        assertSame(loc, el.locatorInternal());
        verify(bp).locatorInternal("#login");
    }

    // ---------- 组合定位 ----------

    @Test
    public void nth_returnsNewElementWithIndexedSelector_andResolvesLazily() {
        BasePage bp = bpWith(mock(Page.class));
        PageElement el = new PageElement("#link", bp);

        PageElement second = el.nth(2);

        assertNotSame("nth 应返回新元素，不原地修改", el, second);
        assertTrue("新元素选择器须带索引便于诊断：" + second.getSelector(), second.getSelector().contains("nth=2"));
    }

    // ---------- Role 定位（2026-10-31 新增：元素级向下按 ARIA role 定位） ----------

    @Test
    public void role_returnsNewElement_withRoleSelector_andResolvesViaGetByRole() {
        BasePage bp = bpWith(mock(Page.class));
        Locator base = mock(Locator.class);
        PageElement el = new PageElement(() -> base, "#dialog", bp);
        Locator child = mock(Locator.class);
        when(base.getByRole(AriaRole.BUTTON)).thenReturn(child);

        PageElement found = el.role(AriaRole.BUTTON);

        assertNotSame("role 应返回新元素，不原地修改", el, found);
        assertTrue("新元素选择器须带 role 便于诊断：" + found.getSelector(), found.getSelector().contains("role=" + AriaRole.BUTTON));
        assertSame("底层定位器应动态解析到 getByRole 结果", child, found.locatorInternal());
        verify(base).getByRole(AriaRole.BUTTON);
    }

    @Test
    public void role_withName_passesNameOption_andDescriptorsRoleAndName() {
        BasePage bp = bpWith(mock(Page.class));
        Locator base = mock(Locator.class);
        PageElement el = new PageElement(() -> base, "#form", bp);
        Locator child = mock(Locator.class);
        when(base.getByRole(eq(AriaRole.LINK), any(Locator.GetByRoleOptions.class))).thenReturn(child);

        PageElement found = el.role(AriaRole.LINK, "忘记密码");

        assertTrue("描述须含 role 与 name：" + found.getSelector(), found.getSelector().contains("role=" + AriaRole.LINK) && found.getSelector().contains("忘记密码"));
        assertSame("底层定位器应动态解析到 getByRole 结果", child, found.locatorInternal());
        var captor = org.mockito.ArgumentCaptor.forClass(Locator.GetByRoleOptions.class);
        verify(base).getByRole(org.mockito.ArgumentMatchers.eq(AriaRole.LINK), captor.capture());
        assertEquals("name 应透传进选项", "忘记密码", captor.getValue().name);
    }

    @Test
    public void role_withExact_setsExactFlag() {
        BasePage bp = bpWith(mock(Page.class));
        Locator base = mock(Locator.class);
        PageElement el = new PageElement(() -> base, "#nav", bp);
        when(base.getByRole(eq(AriaRole.BUTTON), any(Locator.GetByRoleOptions.class))).thenReturn(mock(Locator.class));

        PageElement found = el.role(AriaRole.BUTTON, "提交", true);

        found.locatorInternal(); // 触发惰性 supplier 解析，验证透传
        var captor = org.mockito.ArgumentCaptor.forClass(Locator.GetByRoleOptions.class);
        verify(base).getByRole(org.mockito.ArgumentMatchers.eq(AriaRole.BUTTON), captor.capture());
        assertEquals("exact 应透传为布尔 true", Boolean.TRUE, captor.getValue().exact);
    }

    @Test
    public void role_withPattern_usesRegexName() {
        BasePage bp = bpWith(mock(Page.class));
        Locator base = mock(Locator.class);
        PageElement el = new PageElement(() -> base, "#list", bp);
        when(base.getByRole(eq(AriaRole.OPTION), any(Locator.GetByRoleOptions.class))).thenReturn(mock(Locator.class));

        PageElement found = el.role(AriaRole.OPTION, Pattern.compile("保存.*"));

        found.locatorInternal(); // 触发惰性 supplier 解析，验证透传
        var captor = org.mockito.ArgumentCaptor.forClass(Locator.GetByRoleOptions.class);
        verify(base).getByRole(org.mockito.ArgumentMatchers.eq(AriaRole.OPTION), captor.capture());
        assertEquals("正则应透传进 name pattern", "保存.*", captor.getValue().name.toString());
    }

    @Test
    public void role_rejectsNullRole() {
        BasePage bp = bpWith(mock(Page.class));
        PageElement el = new PageElement(() -> mock(Locator.class), "#x", bp);

        assertThrows(NullPointerException.class, () -> el.role(null));
        assertThrows(NullPointerException.class, () -> el.role(null, "提交"));
        assertThrows(NullPointerException.class, () -> el.role(AriaRole.BUTTON, (String) null));
        assertThrows(NullPointerException.class, () -> el.role(AriaRole.BUTTON, (Pattern) null));
    }

    @Test
    public void role_withNullOptions_fallsBackToPlainRole() {
        BasePage bp = bpWith(mock(Page.class));
        Locator base = mock(Locator.class);
        PageElement el = new PageElement(() -> base, "#x", bp);
        when(base.getByRole(AriaRole.BUTTON)).thenReturn(mock(Locator.class));

        PageElement found = el.role(AriaRole.BUTTON, (Locator.GetByRoleOptions) null);

        found.locatorInternal(); // 触发惰性 supplier 解析，验证回落 to getByRole(role)
        verify(base).getByRole(AriaRole.BUTTON);
        assertTrue(found.getSelector().contains("role=" + AriaRole.BUTTON));
    }

    // ---------- 相对定位族：text/label/placeholder/testId/title/altText（2026-10-31 新增） ----------

    @Test
    public void text_returnsNewElement_andResolvesViaGetByText() {
        BasePage bp = bpWith(mock(Page.class));
        Locator base = mock(Locator.class);
        PageElement el = new PageElement(() -> base, "#dialog", bp);
        Locator child = mock(Locator.class);
        when(base.getByText("确定")).thenReturn(child);

        PageElement found = el.text("确定");

        assertNotSame("text 应返回新元素，不原地修改", el, found);
        assertTrue("描述须含文本：" + found.getSelector(), found.getSelector().contains("text=确定"));
        assertSame("底层定位器应动态解析到 getByText 结果", child, found.locatorInternal());
        verify(base).getByText("确定");
    }

    @Test
    public void text_withExact_setsExactFlag() {
        BasePage bp = bpWith(mock(Page.class));
        Locator base = mock(Locator.class);
        PageElement el = new PageElement(() -> base, "#nav", bp);
        when(base.getByText(any(String.class), any(Locator.GetByTextOptions.class))).thenReturn(mock(Locator.class));

        PageElement found = el.text("提交订单", true);
        found.locatorInternal();

        var captor = org.mockito.ArgumentCaptor.forClass(Locator.GetByTextOptions.class);
        verify(base).getByText(org.mockito.ArgumentMatchers.eq("提交订单"), captor.capture());
        assertEquals("exact 应透传", Boolean.TRUE, captor.getValue().exact);
        assertTrue("描述须带 exact 标记：" + found.getSelector(), found.getSelector().contains("exact"));
    }

    @Test
    public void text_withPattern_usesRegex() {
        BasePage bp = bpWith(mock(Page.class));
        Locator base = mock(Locator.class);
        PageElement el = new PageElement(() -> base, "#list", bp);
        when(base.getByText(any(Pattern.class))).thenReturn(mock(Locator.class));

        el.text(Pattern.compile("保存.*")).locatorInternal();

        // Pattern 无 equals 语义，用 captor 取真实 pattern 字符串断言
        var captor = org.mockito.ArgumentCaptor.forClass(Pattern.class);
        verify(base).getByText(captor.capture());
        assertEquals("正则应透传", "保存.*", captor.getValue().pattern());
    }

    @Test
    public void label_returnsNewElement_viaGetByLabel() {
        BasePage bp = bpWith(mock(Page.class));
        Locator base = mock(Locator.class);
        PageElement el = new PageElement(() -> base, "#form", bp);
        Locator child = mock(Locator.class);
        when(base.getByLabel("用户名")).thenReturn(child);

        PageElement found = el.label("用户名");

        assertSame(child, found.locatorInternal());
        verify(base).getByLabel("用户名");
    }

    @Test
    public void placeholder_returnsNewElement_viaGetByPlaceholder() {
        BasePage bp = bpWith(mock(Page.class));
        Locator base = mock(Locator.class);
        PageElement el = new PageElement(() -> base, "#search-box", bp);
        when(base.getByPlaceholder("请输入关键字")).thenReturn(mock(Locator.class));

        PageElement found = el.placeholder("请输入关键字");

        assertTrue("描述须含 placeholder：" + found.getSelector(), found.getSelector().contains("placeholder=请输入关键字"));
        found.locatorInternal();
        verify(base).getByPlaceholder("请输入关键字");
    }

    @Test
    public void testId_returnsNewElement_viaGetByTestId() {
        BasePage bp = bpWith(mock(Page.class));
        Locator base = mock(Locator.class);
        PageElement el = new PageElement(() -> base, "#card", bp);
        when(base.getByTestId("submit-btn")).thenReturn(mock(Locator.class));

        PageElement found = el.testId("submit-btn");

        assertTrue("描述须含 testId：" + found.getSelector(), found.getSelector().contains("testId=submit-btn"));
        found.locatorInternal();
        verify(base).getByTestId("submit-btn");
    }

    @Test
    public void title_returnsNewElement_viaGetByTitle() {
        BasePage bp = bpWith(mock(Page.class));
        Locator base = mock(Locator.class);
        PageElement el = new PageElement(() -> base, "#tip", bp);
        when(base.getByTitle("帮助")).thenReturn(mock(Locator.class));

        PageElement found = el.title("帮助");

        assertTrue("描述须含 title：" + found.getSelector(), found.getSelector().contains("title=帮助"));
        found.locatorInternal();
        verify(base).getByTitle("帮助");
    }

    @Test
    public void altText_returnsNewElement_viaGetByAltText() {
        BasePage bp = bpWith(mock(Page.class));
        Locator base = mock(Locator.class);
        PageElement el = new PageElement(() -> base, "#banner", bp);
        when(base.getByAltText("宣传图")).thenReturn(mock(Locator.class));

        PageElement found = el.altText("宣传图");

        assertTrue("描述须含 altText：" + found.getSelector(), found.getSelector().contains("altText=宣传图"));
        found.locatorInternal();
        verify(base).getByAltText("宣传图");
    }

    @Test
    public void relativeLocators_rejectNullArgs() {
        BasePage bp = bpWith(mock(Page.class));
        PageElement el = new PageElement(() -> mock(Locator.class), "#x", bp);

        assertThrows(NullPointerException.class, () -> el.text((String) null));
        assertThrows(NullPointerException.class, () -> el.text((Pattern) null));
        assertThrows(NullPointerException.class, () -> el.label(null));
        assertThrows(NullPointerException.class, () -> el.placeholder((String) null));
        assertThrows(NullPointerException.class, () -> el.placeholder((Pattern) null));
        assertThrows(NullPointerException.class, () -> el.testId(null));
        assertThrows(NullPointerException.class, () -> el.title(null));
        assertThrows(NullPointerException.class, () -> el.altText(null));
    }

    // ---------- isClickable（2026-10-31 新增：可见且可用 = 可点击，快速探测 + 等待兜底） ----------

    @Test
    public void isClickable_returnsTrue_whenVisibleAndEnabled() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        when(loc.isVisible()).thenReturn(true);
        when(loc.isEnabled()).thenReturn(true);
        PageElement el = new PageElement(() -> loc, "role=button name=提交", bp);

        assertTrue("可见且可用应判可点击", el.isClickable());
        verify(loc, never()).waitFor(any(Locator.WaitForOptions.class));
    }

    @Test
    public void isClickable_returnsFalse_whenHidden_andWaitTimesOut() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        when(loc.isVisible()).thenReturn(false);
        doThrow(new TimeoutError("timeout")).when(loc).waitFor(any(Locator.WaitForOptions.class));
        PageElement el = new PageElement(() -> loc, "#hidden-btn", bp);

        assertFalse("隐藏且等待超时应判不可点击", el.isClickable());
    }

    @Test
    public void isClickable_returnsFalse_whenVisibleButDisabled() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        when(loc.isVisible()).thenReturn(true);
        when(loc.isEnabled()).thenReturn(false);
        when(loc.isEnabled(any(Locator.IsEnabledOptions.class))).thenReturn(false);
        PageElement el = new PageElement(() -> loc, "#disabled-btn", bp);

        assertFalse("可见但禁用应判不可点击", el.isClickable());
    }

    @Test
    public void isClickable_returnsTrue_afterWaitWhenBecomesEnabled() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        when(loc.isVisible()).thenReturn(false); // 首查不可见 → 走等待兜底
        when(loc.isEnabled(any(Locator.IsEnabledOptions.class))).thenReturn(true);
        PageElement el = new PageElement(() -> loc, "#late-btn", bp);

        assertTrue("等待后可用应判可点击", el.isClickable());
        verify(loc).waitFor(any(Locator.WaitForOptions.class));
    }

    // ---------- 上传文件解析：resources 首选 / 用户路径兜底 / 多文件 ----------

    private static PageElement elWithLocator(BasePage bp, Locator loc) {
        when(bp.locatorInternal("#file")).thenReturn(loc);
        return new PageElement("#file", bp);
    }

    @Test
    public void uploadFile_prefersClasspathResource_whenPresent() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = elWithLocator(bp, loc);

        el.uploadFile("test-upload/sample.txt");

        ArgumentCaptor<Path[]> captor = ArgumentCaptor.forClass(Path[].class);
        verify(loc).setInputFiles(captor.capture(), any(Locator.SetInputFilesOptions.class));
        Path[] resolved = captor.getValue();
        assertEquals("应解析出单个文件", 1, resolved.length);
        assertTrue("解析出的文件应真实存在", Files.exists(resolved[0]));
        assertTrue("应优先取自 resources(classpath)，而非用户字面路径：" + resolved[0], resolved[0].toString().replace('\\', '/').endsWith("test-upload/sample.txt"));
    }

    @Test
    public void uploadFile_normalizesBackslashSeparator_forClasspathResource() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = elWithLocator(bp, loc);

        // 资源名用 Windows 风格反斜杠；classpath 资源名恒为 '/'，框架须归一化后在任意系统命中
        el.uploadFile("test-upload\\sample.txt");

        ArgumentCaptor<Path[]> captor = ArgumentCaptor.forClass(Path[].class);
        verify(loc).setInputFiles(captor.capture(), any(Locator.SetInputFilesOptions.class));
        Path[] resolved = captor.getValue();
        assertEquals(1, resolved.length);
        assertTrue("反斜杠资源名应归一化并从 resources 命中：" + resolved[0], resolved[0].toString().replace('\\', '/').endsWith("test-upload/sample.txt"));
    }

    @Test
    public void uploadFile_fallsBackToUserSpecifiedPath_whenResourceMissing() throws IOException {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = elWithLocator(bp, loc);

        Path userFile = tempDir.resolve("user-upload.txt");
        Files.writeString(userFile, "hello");

        el.uploadFile(userFile.toAbsolutePath().toString());

        ArgumentCaptor<Path[]> captor = ArgumentCaptor.forClass(Path[].class);
        verify(loc).setInputFiles(captor.capture(), any(Locator.SetInputFilesOptions.class));
        Path[] resolved = captor.getValue();
        assertEquals(1, resolved.length);
        assertEquals("resources 未命中时应退回用户指定路径", userFile.toAbsolutePath().toString(), resolved[0].toAbsolutePath().toString());
    }

    @Test
    public void uploadFile_normalizesSeparator_forUserSpecifiedPath() throws IOException {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = elWithLocator(bp, loc);

        // 用户指定路径用正斜杠书写；框架按当前系统分隔符归一化后仍能命中（跨系统）
        Path userFile = tempDir.resolve("sub").resolve("user3.txt");
        Path userDir = userFile.getParent();   // 无父目录时为 null（SpotBugs NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE）
        if (userDir == null) {
            throw new IllegalStateException("unexpected: no parent dir for " + userFile);
        }
        Files.createDirectories(userDir);
        Files.writeString(userFile, "x");
        String forwardSlashPath = userFile.toString().replace('\\', '/');

        el.uploadFile(forwardSlashPath);

        ArgumentCaptor<Path[]> captor = ArgumentCaptor.forClass(Path[].class);
        verify(loc).setInputFiles(captor.capture(), any(Locator.SetInputFilesOptions.class));
        Path[] resolved = captor.getValue();
        assertEquals(1, resolved.length);
        assertEquals("用户指定路径应经系统分隔符归一化后命中：" + resolved[0], userFile.toAbsolutePath().toString(), resolved[0].toAbsolutePath().toString());
    }

    @Test
    public void uploadFile_throwsWhenNeitherResourceNorUserPathExists() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = elWithLocator(bp, loc);

        ElementOperationException ex = assertThrows(ElementOperationException.class,
                () -> el.uploadFile("no/such/resource.txt"));
        assertTrue("两端都找不到应抛明确异常：" + ex.getMessage(), ex.getMessage().contains("上传文件不存在"));
        verify(loc, never()).setInputFiles(any(Path[].class), any(Locator.SetInputFilesOptions.class));
    }

    @Test
    public void uploadFile_resolvesMixedResourcesAndUserPaths_forMultiFile() throws IOException {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = elWithLocator(bp, loc);

        Path userFile = tempDir.resolve("user2.txt");
        Files.writeString(userFile, "x");

        el.uploadFile("test-upload/sample.txt", userFile.toAbsolutePath().toString());

        ArgumentCaptor<Path[]> captor = ArgumentCaptor.forClass(Path[].class);
        verify(loc).setInputFiles(captor.capture(), any(Locator.SetInputFilesOptions.class));
        Path[] resolved = captor.getValue();
        assertEquals("多文件应逐个解析为 2 个路径", 2, resolved.length);
        assertTrue("第一个文件应取自 resources", resolved[0].toString().replace('\\', '/').endsWith("test-upload/sample.txt"));
        assertEquals("第二个文件（用户路径）应真实存在并按字面路径解析", userFile.toAbsolutePath().toString(), resolved[1].toAbsolutePath().toString());
    }
}
