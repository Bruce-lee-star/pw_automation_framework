package com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * outcomeId 构造与冲突后缀（Tier2 #10 稳定性部分）：
 * baseId 确定性、slug 规则、并行/重试同 baseId 自动 -2/-3 后缀。
 *
 * <p>outline 多轮与 Serenity 后缀形态一致性依赖真实 Serenity 基线比对，
 * 属迁移 Phase 0 门禁范畴（见 ReportConsistencyGate），本测试只守 pw-reports 侧
 * 的稳定性不变量。
 */
class OutcomeIdTest {

    @Test
    @DisplayName("baseId 确定性：同输入恒得同 id")
    void baseIdStable() {
        String id1 = OutcomeIdFactory.baseId("file:///proj/features/login.feature", 12, "User logs in with valid creds");
        String id2 = OutcomeIdFactory.baseId("file:///proj/features/login.feature", 12, "User logs in with valid creds");
        assertThat(id1).isEqualTo(id2).isEqualTo("login-12-user-logs-in-with-valid-creds");
    }

    @Test
    @DisplayName("slug：小写、折叠非字母数字、feature 文件名去目录与扩展名")
    void slugRules() {
        assertThat(OutcomeIdFactory.slug("User Logs In!!")).isEqualTo("user-logs-in");
        assertThat(OutcomeIdFactory.featureBaseName("C:\\proj\\features\\login.feature")).isEqualTo("login");
        assertThat(OutcomeIdFactory.featureBaseName("features/login.feature")).isEqualTo("login");
        assertThat(OutcomeIdFactory.featureBaseName("file:///a/b/c.Login.feature")).isEqualTo("c.Login");
    }

    @Test
    @DisplayName("冲突 id（同名 outline / 重试重发）自动 -2/-3 后缀，且不改变首例")
    void conflictSuffix() {
        ReportBus bus = new ReportBus(PwReportsConfig.builder().build());
        bus.startRun();
        try {
            var h1 = bus.startCase("dup-1-scenario", "f", "s", List.of());
            var h2 = bus.startCase("dup-1-scenario", "f", "s", List.of());
            var h3 = bus.startCase("dup-1-scenario", "f", "s", List.of());
            assertThat(h1.outcomeId()).isEqualTo("dup-1-scenario");
            assertThat(h2.outcomeId()).isEqualTo("dup-1-scenario-2");
            assertThat(h3.outcomeId()).isEqualTo("dup-1-scenario-3");
        } finally {
            bus.finishRun();
        }
    }
}
