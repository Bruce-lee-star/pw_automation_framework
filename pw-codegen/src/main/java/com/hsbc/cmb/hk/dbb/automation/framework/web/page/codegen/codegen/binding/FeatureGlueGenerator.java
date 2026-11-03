package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFileWriter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenMarkers;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenTemplates;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P2「需求 → 代码绑定」生成器：把一份<b>业务场景绑定计划</b>编译为可跑的
 * {@code .feature} + 极薄 Cucumber Glue 类。
 *
 * <h2>为什么它不属于录制工具</h2>
 * <p>录制（pw-codegen 的 Tab1/Tab2/Tab3）只产出 {@code Page Object} / {@code Steps(@Step)} /
 * {@code Assert}；<b>Feature 与 Glue 来自业务需求</b>。本生成器因此<b>不接入</b>
 * {@code MultiPageCodegen.build()}，是一条独立入口：输入需求侧的场景描述，输出 BDD 资产。
 *
 * <h2>硬契约（生成物能否编译/能否被 Cucumber 匹配，全押在这两条）</h2>
 * <ul>
 *   <li><b>注解文本 = Cucumber 表达式</b>：Glue 的 {@code @Given("I log on as {string}")} 必须逐字等于
 *       feature 里的短语骨架（占位符保留为 {@code {string}}/{@code {int}}，不能换成字面值）；</li>
 *   <li><b>feature 行 = 表达式 + 字面值</b>：{@code Given I log on as "alice"} 中的 {@code "alice"}
 *       由同位置的 {@link ParamType} 决定是否加引号；Glue 方法体的参数类型由此推导。</li>
 * </ul>
 * <p>两条契约都由本类在生成期校验（占位符数量、类型、字面值合法性），不把错误留到编译期。
 *
 * <h2>Glue 只做委托</h2>
 * <p>生成的方法体恒为一行 {@code steps.xxx(...)}（或录制步骤的 {@code steps.stepN()}），
 * <b>不出现定位器、不出现业务分支</b>——业务流在录制的 Steps 层，Glue 仅是"短语 → 方法"映射。
 *
 * <p>用法：
 * <pre>{@code
 * FeatureGluePlan plan = FeatureGluePlan.named("Role element login")
 *         .gluePackage("com.example.glue")
 *         .steps("com.example.steps", "LoginSteps")
 *         .tags("regression", "login")
 *         .scenario(Scenario.named("Maker logs on")
 *                 .step(BindingStep.given("I open the login page").delegate("step1"))
 *                 .step(BindingStep.when("I log on as {string} with role {string}")
 *                         .param("username", ParamType.STRING)
 *                         .param("role", ParamType.STRING)
 *                         .args("alice", "maker")
 *                         .delegateForwarding("logon")));
 * FeatureGlueResult r = FeatureGlueGenerator.generate(plan);
 * r.writeTo(Path.of("src/test/resources/features"), Path.of("src/test/java/com/example/glue"));
 * }</pre>
 */
public final class FeatureGlueGenerator {

    /** Glue 类默认包名（未显式指定时）。 */
    public static final String DEFAULT_GLUE_PACKAGE = "com.example.pages.glue";

    /** Cucumber 表达式占位符：仅识别内置参数类型，其余花括号视为普通文本。 */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{(string|word|int|long|double|float|bigdecimal|byte|short|boolean)\\}");

    private static final Pattern NON_IDENTIFIER = Pattern.compile("[^A-Za-z0-9]+");
    private static final Pattern CAMEL_HUMP = Pattern.compile("([a-z0-9])([A-Z])");

    /** Glue 注解的固定输出顺序（保证同一批输入产物字节稳定）。 */
    private static final List<String> KEYWORD_ORDER = List.of("Given", "When", "Then", "And", "But");

    private FeatureGlueGenerator() {}

    /**
     * 生成 Feature + Glue 两份产物（纯内存，不落盘）。
     *
     * @throws IllegalArgumentException 计划不完整，或占位符/参数/字面值三者对不上
     */
    public static FeatureGlueResult generate(FeatureGluePlan plan) {
        Objects.requireNonNull(plan, "plan 不可为空");
        if (isBlank(plan.featureName)) {
            throw new IllegalArgumentException("featureName 不可为空");
        }
        if (isBlank(plan.stepsClassName)) {
            throw new IllegalArgumentException("stepsClassName 不可为空：Glue 必须委托录制产出的 Steps 类");
        }
        if (plan.scenarios.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个 Scenario");
        }

        String featureName = plan.featureName.trim();
        String featureFileName = isBlank(plan.featureFileName)
                ? featureFileNameOf(featureName) : plan.featureFileName.trim();
        String gluePackage = isBlank(plan.gluePackageName)
                ? DEFAULT_GLUE_PACKAGE : plan.gluePackageName.trim();
        String glueClassName = isBlank(plan.glueClassName)
                ? glueClassNameOf(featureName) : plan.glueClassName.trim();

        List<Map<String, Object>> scenarioModels = new ArrayList<>();
        List<Map<String, Object>> methodModels = new ArrayList<>();
        Set<String> usedMethodNames = new HashSet<>();
        Set<String> usedKeywords = new LinkedHashSet<>();
        Set<String> extraImports = new LinkedHashSet<>();
        int seq = 0;

        for (Scenario scenario : plan.scenarios) {
            if (isBlank(scenario.name)) {
                throw new IllegalArgumentException("Scenario 名称不可为空");
            }
            if (scenario.steps.isEmpty()) {
                throw new IllegalArgumentException("Scenario [" + scenario.name + "] 至少需要一个步骤");
            }
            List<Map<String, Object>> stepModels = new ArrayList<>();
            for (BindingStep step : scenario.steps) {
                seq++;
                String keyword = canonicalKeyword(step.keyword);
                usedKeywords.add(keyword);

                String featureText = buildFeatureText(step);
                stepModels.add(model("keyword", keyword, "text", featureText));

                String delegateMethod = isBlank(step.delegateMethod) ? "step" + seq : step.delegateMethod;
                List<String> paramNames = step.params.stream().map(p -> p.name).toList();
                String delegateCall = delegateMethod + "("
                        + (step.forwardAllParams ? String.join(", ", paramNames) : "") + ")";

                String methodName = uniqueMethodName(methodNameOf(step.phrase, keyword), usedMethodNames);
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("annotation", keyword);
                mm.put("phrase", escapeJava(step.phrase));
                mm.put("methodName", methodName);
                mm.put("methodParams", String.join(", ", step.params.stream()
                        .map(p -> p.type.javaType + " " + p.name).toList()));
                mm.put("delegateCall", delegateCall);
                mm.put("absorbedParams", !step.forwardAllParams && !paramNames.isEmpty()
                        ? String.join(", ", paramNames) : "");
                methodModels.add(mm);

                step.params.stream()
                        .map(p -> p.type.javaImport())
                        .filter(Objects::nonNull)
                        .forEach(extraImports::add);
            }
            scenarioModels.add(model("name", scenario.name.trim(), "steps", stepModels));
        }

        List<String> imports = new ArrayList<>();
        KEYWORD_ORDER.stream().filter(usedKeywords::contains)
                .forEach(k -> imports.add("io.cucumber.java.en." + k));
        if (!isBlank(plan.stepsPackageName) && !plan.stepsPackageName.trim().equals(gluePackage)) {
            imports.add(plan.stepsPackageName.trim() + "." + plan.stepsClassName.trim());
        }
        imports.addAll(extraImports);
        StringBuilder importBlock = new StringBuilder();
        for (String imp : imports) {
            importBlock.append("import ").append(imp).append(";\n");
        }
        if (importBlock.length() > 0) {
            importBlock.append('\n');
        }

        Map<String, Object> featureModel = new LinkedHashMap<>();
        featureModel.put("generatedMarker", CodegenMarkers.GENERATED_MARKER);
        featureModel.put("tagLine", plan.tags.stream().map(t -> "@" + t).reduce((a, b) -> a + " " + b).orElse(""));
        featureModel.put("featureName", featureName);
        featureModel.put("scenarios", scenarioModels);

        Map<String, Object> glueModel = new LinkedHashMap<>();
        glueModel.put("generatedMarker", CodegenMarkers.GENERATED_MARKER);
        glueModel.put("gluePackageName", gluePackage);
        glueModel.put("glueClassName", glueClassName);
        glueModel.put("importBlock", importBlock.toString());
        glueModel.put("stepsClassName", plan.stepsClassName.trim());
        glueModel.put("stepsVar", isBlank(plan.stepsVar) ? "steps" : plan.stepsVar.trim());
        glueModel.put("methods", methodModels);

        return new FeatureGlueResult(
                featureFileName,
                CodegenTemplates.render("feature.ftl", featureModel),
                glueClassName + ".java",
                CodegenTemplates.render("glue.ftl", glueModel));
    }

    /** 校验并拼出 feature 里的那一行（关键字 + 字面值替换后的短语）。 */
    private static String buildFeatureText(BindingStep step) {
        if (isBlank(step.phrase)) {
            throw new IllegalArgumentException("步骤短语不可为空");
        }
        Matcher m = PLACEHOLDER.matcher(step.phrase);
        StringBuilder out = new StringBuilder();
        int last = 0;
        int index = 0;
        while (m.find()) {
            if (index >= step.params.size()) {
                throw new IllegalArgumentException("步骤 [" + step.phrase + "] 第 " + (index + 1)
                        + " 个占位符 {" + m.group(1) + "} 缺少参数名声明");
            }
            if (index >= step.literalArgs.size()) {
                throw new IllegalArgumentException("步骤 [" + step.phrase + "] 第 " + (index + 1)
                        + " 个占位符缺少对应的 Feature 字面值");
            }
            ParamType placeholderType = ParamType.fromCucumberName(m.group(1));
            Param declared = step.params.get(index);
            if (declared.type != placeholderType) {
                throw new IllegalArgumentException("步骤 [" + step.phrase + "] 第 " + (index + 1)
                        + " 个占位符类型为 {" + placeholderType.cucumberName + "}，但参数 [" + declared.name
                        + "] 声明为 {" + declared.type.cucumberName + "}");
            }
            out.append(step.phrase, last, m.start());
            out.append(placeholderType.featureLiteral(step.literalArgs.get(index), step.phrase, index + 1));
            last = m.end();
            index++;
        }
        out.append(step.phrase, last, step.phrase.length());
        if (index < step.literalArgs.size()) {
            throw new IllegalArgumentException("步骤 [" + step.phrase + "] 只有 " + index
                    + " 个占位符，却提供了 " + step.literalArgs.size() + " 个字面值");
        }
        if (index < step.params.size()) {
            throw new IllegalArgumentException("步骤 [" + step.phrase + "] 只有 " + index
                    + " 个占位符，却声明了 " + step.params.size() + " 个参数");
        }
        return out.toString();
    }

    private static String canonicalKeyword(String raw) {
        String k = raw == null ? "Given" : raw.trim();
        for (String candidate : KEYWORD_ORDER) {
            if (candidate.equalsIgnoreCase(k)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("不支持的步骤关键字 [" + raw + "]，仅支持 " + KEYWORD_ORDER);
    }

    /** 由短语派生方法名：去掉占位符 → 非标识符转下划线 → 小驼峰式短语变下划线式。 */
    private static String methodNameOf(String phrase, String keyword) {
        String stripped = PLACEHOLDER.matcher(phrase == null ? "" : phrase).replaceAll(" ");
        String name = slug(stripped);
        if (name.isEmpty()) {
            name = keyword.toLowerCase(Locale.ROOT);
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            name = "step_" + name;
        }
        return name;
    }

    private static String uniqueMethodName(String base, Set<String> used) {
        String name = base;
        int n = 2;
        while (!used.add(name)) {
            name = base + "_" + n++;
        }
        return name;
    }

    /** {@code Role element login → role_element_login}（feature 文件名主干）。 */
    static String slug(String raw) {
        String s = NON_IDENTIFIER.matcher(raw == null ? "" : raw).replaceAll("_");
        s = s.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return s.toLowerCase(Locale.ROOT);
    }

    /** {@code Role element login → RoleElementLogin};{@code roleElement_login → RoleElementLogin}。 */
    static String pascal(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String spaced = CAMEL_HUMP.matcher(raw.trim()).replaceAll("$1 $2");
        StringBuilder sb = new StringBuilder();
        for (String token : NON_IDENTIFIER.split(spaced)) {
            if (token.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1));
        }
        String out = sb.toString();
        if (!out.isEmpty() && !Character.isJavaIdentifierStart(out.charAt(0))) {
            out = "G" + out;
        }
        return out;
    }

    static String featureFileNameOf(String featureName) {
        String s = slug(featureName);
        return (s.isEmpty() ? "feature" : s) + ".feature";
    }

    static String glueClassNameOf(String featureName) {
        String p = pascal(featureName);
        return (p.isEmpty() ? "Generated" : p) + "Glue";
    }

    /** Java 字符串字面量转义（模板以 plainText 输出，故只需处理 Java 侧转义）。 */
    private static String escapeJava(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static Map<String, Object> model(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ------------------------------------------------------------------
    // 模型
    // ------------------------------------------------------------------

    /**
     * Cucumber 内置参数类型 → Java 参数类型 + feature 字面值写法。
     *
     * <p>{@code string}/{@code word} 在 feature 里带引号，数值/布尔裸写；这条差异是「同一份输入
     * 既能被 Cucumber 匹配、又能编译」的关键，不能交给调用方随手拼。
     */
    public enum ParamType {
        STRING("string", "String"),
        WORD("word", "String"),
        INT("int", "int"),
        LONG("long", "long"),
        DOUBLE("double", "double"),
        FLOAT("float", "float"),
        BIG_DECIMAL("bigdecimal", "java.math.BigDecimal"),
        BYTE("byte", "byte"),
        SHORT("short", "short"),
        BOOLEAN("boolean", "boolean");

        private final String cucumberName;
        private final String javaType;

        ParamType(String cucumberName, String javaType) {
            this.cucumberName = cucumberName;
            this.javaType = javaType;
        }

        public String cucumberName() {
            return cucumberName;
        }

        public String javaType() {
            return javaType;
        }

        /** 需要显式 import 的类型（{@code java.lang} 之外）。 */
        public String javaImport() {
            return javaType.startsWith("java.") ? javaType : null;
        }

        static ParamType fromCucumberName(String name) {
            for (ParamType t : values()) {
                if (t.cucumberName.equals(name)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("不支持的 Cucumber 参数类型 {" + name + "}");
        }

        /**
         * 解析 JSON / 文本里的类型写法：接受 Cucumber 名（{@code string}/{@code bigdecimal}）
         * 与 Java 风格名（{@code String}/{@code BIG_DECIMAL}），大小写不敏感。
         */
        public static ParamType parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("参数类型不可为空");
            }
            String v = raw.trim();
            for (ParamType t : values()) {
                if (t.cucumberName.equalsIgnoreCase(v) || t.name().equalsIgnoreCase(v)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("不支持的参数类型 [" + raw + "]，可用: "
                    + Arrays.toString(Arrays.stream(values()).map(ParamType::cucumberName).toArray()));
        }

        /** 把字面值写成 feature 里该类型应有的形态（string 加引号，数值裸写并校验）。 */
        String featureLiteral(String raw, String phrase, int position) {
            if (raw == null) {
                throw new IllegalArgumentException("步骤 [" + phrase + "] 第 " + position + " 个字面值不可为 null");
            }
            if (this == STRING || this == WORD) {
                return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
            String v = raw.trim();
            if (v.isEmpty()) {
                throw new IllegalArgumentException("步骤 [" + phrase + "] 第 " + position + " 个字面值不可为空");
            }
            try {
                return switch (this) {
                    case INT -> Long.toString(requireRange(parseLong(v), Integer.MIN_VALUE, Integer.MAX_VALUE, this));
                    case BYTE -> Long.toString(requireRange(parseLong(v), Byte.MIN_VALUE, Byte.MAX_VALUE, this));
                    case SHORT -> Long.toString(requireRange(parseLong(v), Short.MIN_VALUE, Short.MAX_VALUE, this));
                    case LONG -> Long.toString(parseLong(v));
                    case DOUBLE, FLOAT -> Double.toString(Double.parseDouble(v));
                    case BIG_DECIMAL -> new BigDecimal(v).toString();
                    case BOOLEAN -> {
                        if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                            throw new NumberFormatException("不是布尔值");
                        }
                        yield v.toLowerCase(Locale.ROOT);
                    }
                    default -> throw new IllegalStateException("unexpected type " + this);
                };
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("步骤 [" + phrase + "] 第 " + position + " 个字面值 ["
                        + raw + "] 不是合法的 {" + cucumberName + "}", e);
            }
        }

        private static long parseLong(String v) {
            return Long.parseLong(v);
        }

        private static long requireRange(long value, long min, long max, ParamType type) {
            if (value < min || value > max) {
                throw new NumberFormatException("超出 " + type.cucumberName + " 范围");
            }
            return value;
        }
    }

    /** 一个 Glue 方法参数：名字 + 类型（顺序必须与短语中占位符顺序一致）。 */
    public static final class Param {
        private final String name;
        private final ParamType type;

        private Param(String name, ParamType type) {
            if (isBlank(name) || !isJavaIdentifier(name)) {
                throw new IllegalArgumentException("非法参数名 [" + name + "]");
            }
            this.name = name;
            this.type = Objects.requireNonNull(type, "param type");
        }

        public static Param of(String name, ParamType type) {
            return new Param(name, type);
        }

        // --- 同包读取（BindingPlanJson 序列化用；不对外暴露可变集合） ---
        String name() {
            return name;
        }

        ParamType type() {
            return type;
        }

        private static boolean isJavaIdentifier(String s) {
            if (!Character.isJavaIdentifierStart(s.charAt(0))) {
                return false;
            }
            for (int i = 1; i < s.length(); i++) {
                if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 一个绑定步骤：关键字 + 短语（Cucumber 表达式）+ 参数声明 + feature 字面值 + 委托目标。
     *
     * <p>{@link #delegate(String)} 生成 {@code steps.stepN()}（录制步骤默认无参，短语里的参数被"吸收"，
     * 生成注释说明）；{@link #delegateForwarding(String)} 把 Glue 方法参数原序透传给业务方法。
     */
    public static final class BindingStep {
        private final String keyword;
        private final String phrase;
        private final List<Param> params = new ArrayList<>();
        private final List<String> literalArgs = new ArrayList<>();
        private String delegateMethod;
        private boolean forwardAllParams;

        private BindingStep(String keyword, String phrase) {
            this.keyword = keyword;
            this.phrase = phrase;
        }

        public static BindingStep given(String phrase) {
            return new BindingStep("Given", phrase);
        }

        public static BindingStep when(String phrase) {
            return new BindingStep("When", phrase);
        }

        public static BindingStep then(String phrase) {
            return new BindingStep("Then", phrase);
        }

        /** 自定义关键字（{@code And}/{@code But}）。 */
        public static BindingStep of(String keyword, String phrase) {
            return new BindingStep(keyword, phrase);
        }

        /** 声明参数（顺序须与短语中占位符一致）。 */
        public BindingStep param(String name, ParamType type) {
            params.add(Param.of(name, type));
            return this;
        }

        /** 提供 feature 里使用的字面值（顺序同上，数量须等于占位符数）。 */
        public BindingStep args(String... literals) {
            literalArgs.addAll(Arrays.asList(literals));
            return this;
        }

        /** 委托录制的无参步骤（默认方法名为 {@code stepN}）。 */
        public BindingStep delegate(String method) {
            this.delegateMethod = method;
            this.forwardAllParams = false;
            return this;
        }

        /** 委托业务方法并把 Glue 参数原序透传（如 {@code logon(username, role)}）。 */
        public BindingStep delegateForwarding(String method) {
            this.delegateMethod = method;
            this.forwardAllParams = true;
            return this;
        }

        // --- 同包读取（BindingPlanJson 序列化用） ---
        String keyword() {
            return keyword;
        }

        String phrase() {
            return phrase;
        }

        List<Param> paramList() {
            return List.copyOf(params);
        }

        List<String> argList() {
            return List.copyOf(literalArgs);
        }

        String delegateMethod() {
            return delegateMethod;
        }

        boolean forwardsParams() {
            return forwardAllParams;
        }
    }

    /** 一个业务场景（Feature 里的 {@code Scenario}）。 */
    public static final class Scenario {
        private final String name;
        private final List<BindingStep> steps = new ArrayList<>();

        private Scenario(String name) {
            this.name = name;
        }

        public static Scenario named(String name) {
            return new Scenario(name);
        }

        public Scenario step(BindingStep step) {
            steps.add(Objects.requireNonNull(step, "step"));
            return this;
        }

        // --- 同包读取（BindingPlanJson 序列化用） ---
        String name() {
            return name;
        }

        List<BindingStep> stepList() {
            return List.copyOf(steps);
        }
    }

    /** 业务场景绑定计划：一份计划对应一个 Feature + 一个 Glue（委托一个录制 Steps 类）。 */
    public static final class FeatureGluePlan {
        private String featureName;
        private String featureFileName;
        private String gluePackageName = DEFAULT_GLUE_PACKAGE;
        private String glueClassName;
        private String stepsPackageName;
        private String stepsClassName;
        private String stepsVar = "steps";
        private final List<String> tags = new ArrayList<>();
        private final List<Scenario> scenarios = new ArrayList<>();

        public static FeatureGluePlan named(String featureName) {
            FeatureGluePlan p = new FeatureGluePlan();
            p.featureName = featureName;
            return p;
        }

        public FeatureGluePlan featureFileName(String name) {
            this.featureFileName = name;
            return this;
        }

        public FeatureGluePlan gluePackage(String packageName) {
            this.gluePackageName = packageName;
            return this;
        }

        public FeatureGluePlan glueClass(String className) {
            this.glueClassName = className;
            return this;
        }

        /** 委托的录制 Steps 类（包名可空：为空表示与 Glue 同包，不生成 import）。 */
        public FeatureGluePlan steps(String packageName, String className) {
            this.stepsPackageName = packageName;
            this.stepsClassName = className;
            return this;
        }

        public FeatureGluePlan stepsVar(String varName) {
            this.stepsVar = varName;
            return this;
        }

        public FeatureGluePlan tags(String... tags) {
            this.tags.addAll(Arrays.asList(tags));
            return this;
        }

        public FeatureGluePlan scenario(Scenario scenario) {
            scenarios.add(Objects.requireNonNull(scenario, "scenario"));
            return this;
        }

        // --- 同包读取（BindingPlanJson 序列化用） ---
        String featureName() {
            return featureName;
        }

        String featureFileName() {
            return featureFileName;
        }

        String gluePackageName() {
            return gluePackageName;
        }

        String glueClassName() {
            return glueClassName;
        }

        String stepsPackageName() {
            return stepsPackageName;
        }

        String stepsClassName() {
            return stepsClassName;
        }

        String stepsVar() {
            return stepsVar;
        }

        List<String> tagNames() {
            return List.copyOf(tags);
        }

        List<Scenario> scenarioList() {
            return List.copyOf(scenarios);
        }
    }

    /** 生成结果：两份产物内容 + 建议文件名，可一次性落盘。 */
    public static final class FeatureGlueResult {
        public final String featureFileName;
        public final String featureContent;
        public final String glueFileName;
        public final String glueContent;

        FeatureGlueResult(String featureFileName, String featureContent,
                          String glueFileName, String glueContent) {
            this.featureFileName = featureFileName;
            this.featureContent = featureContent;
            this.glueFileName = glueFileName;
            this.glueContent = glueContent;
        }

        /** 产物目标路径 → 内容（不落盘；供流水线做批量 / 事务式写入）。 */
        public Map<Path, String> outputs(Path featureDir, Path glueDir) {
            Objects.requireNonNull(featureDir, "featureDir");
            Objects.requireNonNull(glueDir, "glueDir");
            Map<Path, String> outputs = new LinkedHashMap<>();
            outputs.put(featureDir.resolve(featureFileName), featureContent);
            outputs.put(glueDir.resolve(glueFileName), glueContent);
            return outputs;
        }

        /**
         * 落盘到 {@code featureDir} / {@code glueDir}（目录不存在自动创建）。
         *
         * <p>带生成标记冲突守卫：目标存在但首行无生成标记（= 手写代码）时抛
         * {@link com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenConflictException}，
         * 且<b>两份产物一个都不写</b>，不会留下半截状态。
         */
        public void writeTo(Path featureDir, Path glueDir) throws IOException {
            CodegenFileWriter.writeAllGenerated(outputs(featureDir, glueDir));
        }
    }
}
