package com.hsbc.cmb.hk.dbb.automation.framework.report.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置解析门禁：默认值、系统属性覆盖、非法值回退、旧键忽略、开关由值推导。
 *
 * <p>配置面刻意压到 4 项决策（enabled/output/screenshot/push.url）+ 2 项低频覆盖
 * （scrub.extra-patterns/limits），本类把「压到什么程度、怎么回退」
 * 钉死，防止后续新增旋钮时无声破坏兼容约定。</p>
 */
class ConfigParseTest {

    /** 测试期间设置/清理系统属性，保证用例互不污染。 */
    private static void withProps(String... kv) {
        try {
            for (int i = 0; i < kv.length; i += 2) {
                System.setProperty(kv[i], kv[i + 1]);
            }
        } finally {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (int i = 0; i < kv.length; i += 2) {
                    System.clearProperty(kv[i]);
                }
            }));
        }
    }

    @Nested
    @DisplayName("默认值（零配置即可跑）")
    class Defaults {

        @Test
        @DisplayName("四项决策全取默认：启用、target/reports、FOR_FAILURES、不推送")
        void defaultsAreUsableOutOfBox() {
            PwReportsConfig c = PwReportsConfig.builder().build();
            assertThat(c.isEnabled()).isTrue();
            assertThat(c.getOutputDir()).isEqualTo(Paths.get("target/reports"));
            assertThat(c.getScreenshotMode()).isEqualTo(ScreenshotMode.FOR_FAILURES);
            assertThat(c.isPushEnabled()).isFalse();
            assertThat(c.getPushUrl()).isNull();
        }

        @Test
        @DisplayName("三项低频覆盖也取默认：限额档位、无额外脱敏、历史 30 条")
        void secondaryDefaults() {
            PwReportsConfig c = PwReportsConfig.builder().build();
            assertThat(c.getLimits()).isEqualTo(PwReportsConfig.Limits.DEFAULT);
            assertThat(c.getMaxEvidenceLines()).isEqualTo(50);
            assertThat(c.getMaxEvidenceLineBytes()).isEqualTo(2048);
            assertThat(c.getMaxApiOperations()).isEqualTo(500);
            assertThat(c.getExtraScrubPatterns()).isEmpty();
        }
    }

    @Nested
    @DisplayName("冗余开关已删除：由值推导而非独立 enabled")
    class RedundantSwitchesRemoved {

        @Test
        @DisplayName("push：非空 url 即启用，空白/null/缺省即关闭（无需 push.enabled）")
        void pushEnabledIsDerivedFromUrl() {
            assertThat(PwReportsConfig.builder().pushUrl("https://collector/reports").build().isPushEnabled())
                    .isTrue();
            assertThat(PwReportsConfig.builder().pushUrl("").build().isPushEnabled())
                    .as("空白 url 视为未配置").isFalse();
            assertThat(PwReportsConfig.builder().pushUrl(null).build().isPushEnabled())
                    .isFalse();
        }

    }

    @Nested
    @DisplayName("Limits 档位解析（位置参数，缺项沿用默认）")
    class LimitsParsing {

        @Test
        @DisplayName("三值全给 → 逐位生效")
        void allThreeValues() {
            PwReportsConfig.Limits l = PwReportsConfig.Limits.parse("80,4096,1000");
            assertThat(l.evidenceLines()).isEqualTo(80);
            assertThat(l.evidenceBytes()).isEqualTo(4096);
            assertThat(l.apiOperations()).isEqualTo(1000);
        }

        @Test
        @DisplayName("缺项沿用默认（\"80\" 只改行数，其余不动）")
        void missingPartsFallBackToDefault() {
            PwReportsConfig.Limits l = PwReportsConfig.Limits.parse("80");
            assertThat(l.evidenceLines()).isEqualTo(80);
            assertThat(l.evidenceBytes()).isEqualTo(PwReportsConfig.Limits.DEFAULT.evidenceBytes());
            assertThat(l.apiOperations()).isEqualTo(PwReportsConfig.Limits.DEFAULT.apiOperations());
        }

        @Test
        @DisplayName("非法输入整体回退默认（非数字/非正/个数超限/空）——绝不半途生效半个值")
        void invalidInputFallsBackWholesale() {
            assertThat(PwReportsConfig.Limits.parse("abc")).isEqualTo(PwReportsConfig.Limits.DEFAULT);
            assertThat(PwReportsConfig.Limits.parse("80,0,1000")).isEqualTo(PwReportsConfig.Limits.DEFAULT);
            assertThat(PwReportsConfig.Limits.parse("-1,4096,1000")).isEqualTo(PwReportsConfig.Limits.DEFAULT);
            assertThat(PwReportsConfig.Limits.parse("1,2,3,4")).isEqualTo(PwReportsConfig.Limits.DEFAULT);
            assertThat(PwReportsConfig.Limits.parse("")).isEqualTo(PwReportsConfig.Limits.DEFAULT);
            assertThat(PwReportsConfig.Limits.parse(null)).isEqualTo(PwReportsConfig.Limits.DEFAULT);
        }

        @Test
        @DisplayName("空片段跳过（\"80,,1000\" 中间空 = 用默认）")
        void blankSegmentUsesDefault() {
            PwReportsConfig.Limits l = PwReportsConfig.Limits.parse("80,,1000");
            assertThat(l.evidenceLines()).isEqualTo(80);
            assertThat(l.evidenceBytes()).isEqualTo(PwReportsConfig.Limits.DEFAULT.evidenceBytes());
            assertThat(l.apiOperations()).isEqualTo(1000);
        }
    }

    @Nested
    @DisplayName("截图策略解析（别名兼容 + 向少截一侧失败）")
    class ScreenshotParsing {

        @Test
        @DisplayName("历史别名映射到规范四值（与框架 ScreenshotStrategy 同词汇）")
        void aliasesMap() {
            assertThat(ScreenshotMode.parse("AFTER_FAILING_STEP", null)).isEqualTo(ScreenshotMode.FOR_FAILURES);
            assertThat(ScreenshotMode.parse("ON_FAILURE", null)).isEqualTo(ScreenshotMode.FOR_FAILURES);
            assertThat(ScreenshotMode.parse("FOR_EACH_STEP", null)).isEqualTo(ScreenshotMode.AFTER_EACH_STEP);
            assertThat(ScreenshotMode.parse("BEFORE_AND_AFTER_EACH_STEP", null))
                    .isEqualTo(ScreenshotMode.BEFORE_AND_AFTER_EACH_STEP);
            assertThat(ScreenshotMode.parse("off", null)).isEqualTo(ScreenshotMode.DISABLED);
            assertThat(ScreenshotMode.parse("none", null)).isEqualTo(ScreenshotMode.DISABLED);
        }

        @Test
        @DisplayName("无法识别 → DISABLED（向少截一侧失败）+ 告警回调，绝不静默按默认放行")
        void unknownFallsBackToDisabledAndWarns() {
            StringBuilder warned = new StringBuilder();
            ScreenshotMode m = ScreenshotMode.parse("EVERYTHING", msg -> warned.append(msg));
            assertThat(m).isEqualTo(ScreenshotMode.DISABLED);
            assertThat(warned).as("必须告警，不能静默").isNotEmpty();
        }
    }

    @Nested
    @DisplayName("系统属性覆盖与旧键忽略")
    class SystemProperties {

        @Test
        @DisplayName("新键生效：enabled/output/screenshot/limits/push.url")
        void newKeysAreHonoured() {
            withProps(
                    "pw.report.enabled", "false",
                    "pw.report.output", "target/custom-report",
                    "pw.report.screenshot", "AFTER_EACH_STEP",
                    "pw.report.limits", "10,256,20",
                    "pw.report.push.url", "https://collector/reports");
            try {
                PwReportsConfig c = PwReportsConfig.load();
                assertThat(c.isEnabled()).isFalse();
                assertThat(c.getOutputDir()).isEqualTo(Paths.get("target/custom-report"));
                assertThat(c.getScreenshotMode()).isEqualTo(ScreenshotMode.AFTER_EACH_STEP);
                assertThat(c.getLimits()).isEqualTo(new PwReportsConfig.Limits(10, 256, 20));
                assertThat(c.isPushEnabled()).isTrue();
            } finally {
                for (String k : Set.of("pw.report.enabled", "pw.report.output", "pw.report.screenshot",
                        "pw.report.limits", "pw.report.push.url")) {
                    System.clearProperty(k);
                }
            }
        }

        @Test
        @DisplayName("旧平铺旋钮被忽略而非报错（升级不崩；不认识就静默）")
        void legacyKeysAreIgnoredNotFatal() {
            withProps(
                    "pw.report.push.enabled", "true",
                    "pw.report.history.enabled", "false",
                    "pw.report.screenshot.mode", "AFTER_EACH_STEP",
                    "pw.report.screenshot.max-bytes", "999",
                    "pw.report.screenshot.mask-inputs", "false",
                    "pw.report.max-evidence-lines", "999");
            try {
                PwReportsConfig c = PwReportsConfig.load();   // 不得抛异常
                assertThat(c.isPushEnabled()).as("旧 push.enabled 不再被识别").isFalse();
                assertThat(c.getScreenshotMode()).isEqualTo(ScreenshotMode.FOR_FAILURES);
                assertThat(c.getMaxEvidenceLines()).isEqualTo(50);
            } finally {
                for (String k : Set.of("pw.report.push.enabled", "pw.report.history.enabled",
                        "pw.report.screenshot.mode", "pw.report.screenshot.max-bytes",
                        "pw.report.screenshot.mask-inputs", "pw.report.max-evidence-lines")) {
                    System.clearProperty(k);
                }
            }
        }

        @Test
        @DisplayName("非法数字回退默认而非 0（否则截断/限额会变成「什么都不留」）")
        void invalidNumberFallsBackToDefault() {
            withProps("pw.report.limits", "not-a-number");
            try {
                assertThat(PwReportsConfig.load().getLimits()).isEqualTo(PwReportsConfig.Limits.DEFAULT);
            } finally {
                System.clearProperty("pw.report.limits");
            }
        }

        @Test
        @DisplayName("空白属性视为未设置（-Dfoo= 不应把默认打成空）")
        void blankPropertyIsTreatedAsUnset() {
            withProps("pw.report.output", "   ");
            try {
                assertThat(PwReportsConfig.load().getOutputDir())
                        .isEqualTo(Paths.get("target/reports"));
            } finally {
                System.clearProperty("pw.report.output");
            }
        }
    }

    @Nested
    @DisplayName("额外脱敏 pattern 解析")
    class ExtraPatterns {

        @Test
        @DisplayName("逗号分隔多 pattern，空片段跳过，非法 regex 跳过不炸")
        void parsesAndSkipsInvalid() {
            withProps("pw.report.scrub.extra-patterns", "ac-\\d{6}, ,[unclosed");
            try {
                assertThat(PwReportsConfig.load().getExtraScrubPatterns())
                        .as("合法的一个，非法/空被跳过").hasSize(1);
                assertThat(PwReportsConfig.load().getExtraScrubPatterns().get(0).pattern())
                        .isEqualTo("ac-\\d{6}");
            } finally {
                System.clearProperty("pw.report.scrub.extra-patterns");
            }
        }

        @Test
        @DisplayName("未配置时为空列表（非 null，避免消费侧 NPE）")
        void emptyByDefault() {
            assertThat(PwReportsConfig.builder().build().getExtraScrubPatterns()).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("不可变性与校验")
    class ImmutabilityAndValidation {

        @Test
        @DisplayName("extraScrubPatterns 防御性拷贝（外部改动不影响已构建的配置）")
        void patternsAreDefensivelyCopied() {
            java.util.List<Pattern> mutable = new java.util.ArrayList<>();
            mutable.add(Pattern.compile("x-\\d+"));
            PwReportsConfig c = PwReportsConfig.builder().extraScrubPatterns(mutable).build();
            mutable.clear();
            assertThat(c.getExtraScrubPatterns()).hasSize(1);
        }

        @Test
        @DisplayName("Limits 构造器拒绝非正值（记录构造即刻失败，不留到运行时）")
        void limitsRejectNonPositive() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new PwReportsConfig.Limits(0, 2048, 500));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new PwReportsConfig.Limits(50, 2048, -1));
        }
    }
}
