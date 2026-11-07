package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorRoleVocabulary;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedSkeleton;
import com.hsbc.cmb.hk.dbb.automation.framework.web.agent.LocatorResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenTarget;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.PageClassGenerator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.StepPlanCodegenAdapter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.recorder.CodegenRecorder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.recorder.RecordedSkeletonConverter;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * T11 跨模块契约：{@code LocatorSpec.canonical()} 的 ROLE 表示 ⇄ codegen 生成的
 * {@code @RoleElement(role = AriaRole.X)} ⇄ 执行期 {@code page.getByRole(AriaRole.X, opts)}。
 *
 * <p>放在 pw-codegen 测试里，因为只有这个模块的 classpath 同时含 pw-core（定位表示）、
 * pw-web-ui（唯一翻译实现）与 Playwright（真实枚举）。四条可证伪的护栏：</p>
 * <ol>
 *   <li>冻结基线文件 ⇄ core 冻结词表 ⇄ 真实 {@code AriaRole.values()} 三方严格相等
 *       （Playwright 升级增删常量即刻变红，必须显式更新基线，不能放宽断言）；</li>
 *   <li>全量枚举经 canonical 往返后，执行侧捕获到的 {@code AriaRole} 与之逐个相同；</li>
 *   <li>别名/连字符/大小写形式在「生成侧注解文本」与「执行侧实参」上得到<b>同一个</b>常量
 *       （历史上 {@code image} / {@code menuitem-checkbox} 生成侧成功、执行侧抛异常）；</li>
 *   <li>写入侧（录制骨架）与路径 C（BDD 资产）只承载规范化 canonical，且可被解析回同一常量。</li>
 * </ol>
 */
public class LocatorSpecGetByRoleContractTest {

    private static final String BASELINE_RESOURCE = "/aria-role-constants-playwright-1.62.0.txt";
    private static final CodegenTarget TARGET =
            CodegenTarget.of("com.example.pages", "https://host/portalserver/login");

    @Test
    public void frozenBaselineMatchesCoreVocabularyAndActualEnum() throws Exception {
        Set<String> baseline = new LinkedHashSet<>();
        try (InputStream in = LocatorSpecGetByRoleContractTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            assertNotNull("冻结基线资源缺失: " + BASELINE_RESOURCE, in);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        baseline.add(trimmed);
                    }
                }
            }
        }
        assertTrue("冻结基线不能是空集（防 vacuous pass）", baseline.size() > 50);
        assertEquals("资源名必须与 core 声明的 Playwright 版本一致",
                "1.62.0", LocatorRoleVocabulary.BASELINE_PLAYWRIGHT_VERSION);
        assertEquals("core 冻结词表与基线文件漂移：升级 Playwright 时须同步更新两处并复核别名表",
                baseline, new LinkedHashSet<>(LocatorRoleVocabulary.constants()));

        Set<String> actual = Arrays.stream(AriaRole.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals("真实 AriaRole 枚举与冻结基线不一致：Playwright 版本漂移或词表被误改",
                baseline, actual);
    }

    @Test
    public void everyAriaRoleRoundTripsFromCanonicalToExecution() {
        for (AriaRole expected : AriaRole.values()) {
            String canonicalRole = expected.name().toLowerCase(Locale.ROOT);
            String canonical = LocatorSpec.role(canonicalRole, "X").canonical();
            LocatorSpec parsed = LocatorSpec.parse(canonical);
            assertEquals("canonical 必须无损往返: " + expected, canonical, parsed.canonical());
            assertEquals(expected.name(), LocatorRoleVocabulary.requireAriaConstant(parsed.role()));

            RoleCapture capture = new RoleCapture();
            LocatorResolver.resolve(capture.page(), parsed, null);
            assertEquals("执行侧 getByRole 角色漂移: " + expected, expected, capture.role);
            assertEquals("可访问名维度也必须原样传递: " + expected, "X", capture.name);
        }
    }

    @Test
    public void generatedAnnotationAndExecutionResolveToSameConstantForAliases() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("image", "IMG");
        cases.put("menuitem-checkbox", "MENUITEMCHECKBOX");
        cases.put("Radio", "RADIO");
        cases.put("button", "BUTTON");

        for (Map.Entry<String, String> c : cases.entrySet()) {
            String rawRole = c.getKey();
            String expectedConstant = c.getValue();
            String name = "Label-" + expectedConstant;

            // 生成侧：@RoleElement(role = AriaRole.X, name = "...")
            String generated = PageClassGenerator.generate(TARGET,
                    List.of(roleEntry(rawRole, name)), new String[]{});
            assertTrue("生成侧注解使用了错误的常量: rawRole=" + rawRole + "\n" + generated,
                    generated.contains("@RoleElement(role = AriaRole." + expectedConstant + ","));

            // 执行侧：历史资产里可能原样保留 CDP AX 词，必须归一到同一常量
            RoleCapture capture = new RoleCapture();
            LocatorResolver.resolve(capture.page(), LocatorSpec.role(rawRole, name), null);
            assertSame("执行侧与生成侧对同一元素得到不同常量: rawRole=" + rawRole,
                    AriaRole.valueOf(expectedConstant), capture.role);
            assertEquals(name, capture.name);
        }
    }

    @Test
    public void recordingWritesCanonicalRolesThatExecuteOnTheSameConstant() {
        CodegenEntry image = recordedEntry("logo", "image", "Logo", "#logo");
        RecordedSkeleton skeleton = RecordedSkeletonConverter.convert(
                new RecordedSkeletonConverter.Source(
                        "example.com", "https://example.com/logon",
                        List.of(new CodegenRecorder.Action("logo", "click", null, 1)),
                        Map.of("logo", image)));

        String target = skeleton.steps().get(0).target();
        assertEquals("写入侧必须写规范常量名，而非 CDP AX 原始词 image",
                "role=img;name=Logo", target);

        RoleCapture capture = new RoleCapture();
        LocatorResolver.resolve(capture.page(), LocatorSpec.parse(target), null);
        assertSame(AriaRole.IMG, capture.role);
    }

    @Test
    public void pathCArtifactsCarryCanonicalTargetVerbatim() {
        String canonical = "role=img;name=Logo";
        StepPlan plan = new StepPlan(1, "locator-role-contract",
                List.of(Command.click(LocatorSpec.parse(canonical))),
                ParamScope.EMPTY, List.of(ParamScope.EMPTY));

        String feature = StepPlanCodegenAdapter.toArtifacts(plan).featureContent;
        Matcher matcher = Pattern.compile("\"(role=[^\"]+)\"").matcher(feature);
        assertTrue("路径 C 必须把 canonical 原样写进 BDD 的 {string} 字面值: \n" + feature,
                matcher.find());
        assertEquals(canonical, matcher.group(1));

        RoleCapture capture = new RoleCapture();
        LocatorResolver.resolve(capture.page(), LocatorSpec.parse(matcher.group(1)), null);
        assertSame(AriaRole.IMG, capture.role);
    }

    @Test
    public void unknownRoleIsRejectedOnExecutionSideWithoutGuessing() {
        RoleCapture capture = new RoleCapture();
        assertThrows(IllegalArgumentException.class,
                () -> LocatorResolver.resolve(capture.page(), LocatorSpec.role("label", "X"), null));
        assertEquals("未知角色不得发起任何 getByRole 调用", 0, capture.calls);
    }

    @Test
    public void noSecondVocabularyIsAllowedInCodegenOrCore() {
        assertEquals("AriaRoles 必须是对 core 规范的薄委托，不得再持有第二份词表/别名表",
                0, AriaRoles.class.getDeclaredFields().length);

        for (java.lang.reflect.Field f : LocatorRoleVocabulary.class.getDeclaredFields()) {
            assertFalse("core 定位规范不得持有 Playwright 类型: " + f.getName(),
                    f.getType().getName().startsWith("com.microsoft.playwright"));
        }
        for (Method m : LocatorRoleVocabulary.class.getDeclaredMethods()) {
            assertFalse("core 定位规范不得返回 Playwright 类型: " + m.getName(),
                    m.getReturnType().getName().startsWith("com.microsoft.playwright"));
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse("core 定位规范不得接收 Playwright 类型: " + m.getName(),
                        p.getName().startsWith("com.microsoft.playwright"));
            }
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static CodegenEntry roleEntry(String role, String name) {
        String css = "#" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return new CodegenEntry(role + "|" + name, role, name, "ROLE", 100,
                "role=" + role, css, null, 0);
    }

    private static CodegenEntry recordedEntry(String sigKey, String role, String name, String css) {
        return new CodegenEntry(sigKey, role, name, "role", 90, css, css, null, -1);
    }

    /** JDK 动态代理捕获 getByRole 的真实实参；pw-codegen 无 Mockito，故不引入新测试依赖。 */
    private static final class RoleCapture implements InvocationHandler {
        private AriaRole role;
        private Object name;
        private int calls;

        private Page page() {
            return (Page) Proxy.newProxyInstance(Page.class.getClassLoader(),
                    new Class<?>[]{Page.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getByRole":
                    if (args != null && args.length == 2 && args[0] instanceof AriaRole) {
                        role = (AriaRole) args[0];
                        calls++;
                        if (args[1] instanceof Page.GetByRoleOptions) {
                            name = ((Page.GetByRoleOptions) args[1]).name;
                        }
                    }
                    return null;
                case "toString":
                    return "RoleCapturePage";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    Class<?> type = method.getReturnType();
                    return type.isPrimitive() && type != void.class
                            ? Array.get(Array.newInstance(type, 1), 0) : null;
            }
        }
    }
}
