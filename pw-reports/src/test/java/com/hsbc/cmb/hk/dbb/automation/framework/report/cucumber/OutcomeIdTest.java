package com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * outcomeId 构造与冲突后缀（Tier2 #10 稳定性部分）：
 * baseId 的 SHA-256 命名规则、slug helper、并行/重试同 baseId 自动 -2/-3 后缀。
 *
 * <p>outline 多轮与 Serenity 后缀形态一致性依赖真实 Serenity 基线比对，
 * 属迁移 Phase 0 门禁范畴（见 ReportConsistencyGate），本测试只守 pw-reports 侧
 * 的稳定性不变量。
 */
class OutcomeIdTest {

    @Test
    @DisplayName("用例目录 ID：本地秒级时间前缀 + SHA-256 hex")
    void baseIdHasTimestampPrefix() {
        ZonedDateTime timestamp = ZonedDateTime.of(2026, 11, 1, 11, 2, 3, 0,
                ZoneId.of("Asia/Shanghai"));
        String id = OutcomeIdFactory.baseId("file:///features/login.feature", 12,
                "User logs in with valid creds", timestamp);
        assertThat(id).isEqualTo("20261101-110203-68cc79b4716900b47917a4f89587c721e03b00341289e78395e7910cf974e03b");
    }

    @Test
    @DisplayName("长用例名通过固定长度摘要控制目录 ID")
    void directoryIdIsBounded() {
        String id = OutcomeIdFactory.timestampedId("feature-" + "scenario-".repeat(20),
                ZonedDateTime.of(2026, 11, 1, 11, 2, 3, 0, ZoneId.of("Asia/Shanghai")));
        assertThat(id).matches("20261101-110203-[0-9a-f]{64}");
        assertThat(id).hasSize(80);
    }

    @Test
    @DisplayName("Cucumber 用例名隐藏在摘要中，目录 ID 不暴露 feature 和行号")
    void generatedBaseIdIsBounded() {
        String id = OutcomeIdFactory.baseId("features/" + "feature".repeat(20) + ".feature",
                12, "very-long-scenario-".repeat(20));
        assertThat(id).matches("\\d{8}-\\d{6}-[0-9a-f]{64}");
        assertThat(id).hasSize(80);
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
