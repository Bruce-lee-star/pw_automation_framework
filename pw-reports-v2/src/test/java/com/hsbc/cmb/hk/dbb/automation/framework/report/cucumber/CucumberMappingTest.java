package com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import io.cucumber.plugin.event.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Status 全枚举映射 + outcomeId 构造回归（R7）。
 */
class CucumberMappingTest {

    @Nested
    @DisplayName("Status 映射（全枚举，防 cucumber 升级漏项）")
    class StatusMapping {

        @Test
        void allStatusesMapped() {
            assertThat(StatusMapper.toTestResult(Status.PASSED)).isEqualTo(TestResult.SUCCESS);
            assertThat(StatusMapper.toTestResult(Status.FAILED)).isEqualTo(TestResult.FAILURE);
            assertThat(StatusMapper.toTestResult(Status.SKIPPED)).isEqualTo(TestResult.SKIPPED);
            assertThat(StatusMapper.toTestResult(Status.PENDING)).isEqualTo(TestResult.PENDING);
            assertThat(StatusMapper.toTestResult(Status.UNDEFINED)).isEqualTo(TestResult.UNDEFINED);
            // AMBIGUOUS 按失败上报（步骤定义歧义），附注由插件挂到步骤
            assertThat(StatusMapper.toTestResult(Status.AMBIGUOUS)).isEqualTo(TestResult.FAILURE);
        }

        @Test
        void unusedProducesNoSession() {
            assertThat(StatusMapper.toTestResult(Status.UNUSED)).isNull();
            assertThat(StatusMapper.toTestResult(null)).isNull();
        }

        @Test
        void ambiguousNotePresent() {
            assertThat(StatusMapper.AMBIGUOUS_NOTE).contains("ambiguous");
        }

        @Test
        void everyStatusEnumCoveredByMapper() {
            // 编译期不报错但运行期防呆：cucumber 加新枚举时本测试推动映射补齐
            for (Status status : Status.values()) {
                assertThat(StatusMapper.toTestResult(status))
                        .as("status %s must have an explicit mapping decision (null counts for UNUSED)", status)
                        .satisfiesAnyOf(
                                r -> assertThat(r).isNotNull(),
                                r -> assertThat(status).isEqualTo(Status.UNUSED));
            }
        }
    }

    @Nested
    @DisplayName("outcomeId 构造")
    class OutcomeId {

        @Test
        void baseIdFormat() {
            String id = OutcomeIdFactory.baseId("file:///features/login.feature", 12, "User logs in with valid creds");
            assertThat(id).isEqualTo("login-12-user-logs-in-with-valid-creds");
        }

        @Test
        void featureBaseNameFromUri() {
            assertThat(OutcomeIdFactory.featureBaseName("classpath:com/x/order-checkout.feature"))
                    .isEqualTo("order-checkout");
            assertThat(OutcomeIdFactory.featureBaseName("C:\\feats\\a\\b.feature"))
                    .isEqualTo("b");
            assertThat(OutcomeIdFactory.featureBaseName(null)).isEqualTo("feature");
            assertThat(OutcomeIdFactory.featureBaseName("")).isEqualTo("feature");
        }

        @Test
        void slugCollapsesSpecialChars() {
            assertThat(OutcomeIdFactory.slug("  Hello,   World!! 123  "))
                    .isEqualTo("hello-world-123");
        }

        @Test
        void slugLengthBounded() {
            String longName = "a".repeat(200);
            assertThat(OutcomeIdFactory.slug(longName)).hasSize(60);
        }

        @Test
        void slugNullOrBlankSafe() {
            assertThat(OutcomeIdFactory.slug(null)).isEqualTo("unnamed");
            assertThat(OutcomeIdFactory.slug("   ")).isEqualTo("unnamed");
            assertThat(OutcomeIdFactory.slug("!!!")).isEqualTo("unnamed");
        }

        @Test
        void outlineExamplesGetDistinctIdsViaLine() {
            // scenario outline 同名示例靠 line 区分（冲突后缀由总线负责）
            String id1 = OutcomeIdFactory.baseId("f.feature", 10, "eat cukes");
            String id2 = OutcomeIdFactory.baseId("f.feature", 14, "eat cukes");
            assertThat(id1).isNotEqualTo(id2);
        }
    }
}
