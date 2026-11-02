package com.hsbc.cmb.hk.dbb.automation.framework.report.step;

import com.hsbc.cmb.hk.dbb.automation.framework.report.annotation.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不可代理 {@code @Step} 方法告警清单门禁。
 *
 * <p>final / static / private 的 {@code @Step} 方法<b>不报错、只是没被拦</b>——是典型的
 * 「以为拦了其实没拦」静默缺口。这份 warn 清单是唯一信号，故必须用测试钉死：</p>
 */
class UnproxyableStepWarningTest {

    // ── fixture：覆盖四类可/不可代理组合 ──

    public static class MixedSteps {

        @Step
        public void normal() {
        }

        @Step
        public final void finalMethod() {
        }

        @Step
        public static void staticMethod() {
        }

        @Step
        private void privateMethod() {
        }

        public void notAStep() {
        }
    }

    public static class CleanSteps {

        @Step
        public void fine() {
        }
    }

    public static class OwnFqnSteps {

        @Step
        public final void ownFinal() {
        }
    }

    public static class ParentWithFinal {

        @Step
        public final void inheritedFinal() {
        }
    }

    public static class ChildOfFinal extends ParentWithFinal {

        @Step
        public void ownNormal() {
        }
    }

    @Test
    @DisplayName("final/static/private 的 @Step 方法各出一条告警，普通方法与非 @Step 方法不进清单")
    void listsAllUnproxyableKinds() {
        List<String> warnings = PwStepFactory.unproxyableStepWarnings(MixedSteps.class);
        assertThat(warnings).hasSize(3);
        assertThat(warnings).anyMatch(w -> w.contains("finalMethod") && w.contains("is final"));
        assertThat(warnings).anyMatch(w -> w.contains("staticMethod") && w.contains("is static/private"));
        assertThat(warnings).anyMatch(w -> w.contains("privateMethod") && w.contains("is static/private"));
        assertThat(warnings).noneMatch(w -> w.contains("normal()"));
        assertThat(warnings).noneMatch(w -> w.contains("notAStep"));
    }

    @Test
    @DisplayName("全部可代理 → 告警清单为空（不制造噪音）")
    void cleanClassProducesNoWarnings() {
        assertThat(PwStepFactory.unproxyableStepWarnings(CleanSteps.class)).isEmpty();
    }

    @Test
    @DisplayName("框架 @Step 的 final 方法同样纳入清单")
    void ownAnnotationAlsoListed() {
        List<String> warnings = PwStepFactory.unproxyableStepWarnings(OwnFqnSteps.class);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("ownFinal").contains("is final");
    }

    @Test
    @DisplayName("父类的 final @Step 也被扫描到（walk 超类链，不是只看本类）")
    void walksSuperclassChain() {
        List<String> warnings = PwStepFactory.unproxyableStepWarnings(ChildOfFinal.class);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("inheritedFinal");
        assertThat(warnings.get(0)).as("文案用被扫描类名").contains(ChildOfFinal.class.getName());
    }

    @Test
    @DisplayName("每条告警都点明「未被拦截」（这是给排障人看的关键信息）")
    void warningsStateNotIntercepted() {
        assertThat(PwStepFactory.unproxyableStepWarnings(MixedSteps.class))
                .allSatisfy(w -> assertThat(w).contains("NOT intercepted"));
    }

    @Test
    @DisplayName("清单可重复计算且稳定（幂等，无隐藏状态）")
    void warningListIsStable() {
        assertThat(PwStepFactory.unproxyableStepWarnings(MixedSteps.class))
                .isEqualTo(PwStepFactory.unproxyableStepWarnings(MixedSteps.class));
    }
}
