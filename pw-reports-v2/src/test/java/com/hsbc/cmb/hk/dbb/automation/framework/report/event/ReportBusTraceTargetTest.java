package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.integration.ReportingBridgeImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * trace 落点契约（{@link ReportBus#traceTarget} / {@link ReportBus#attachTrace}）——
 * 框架经 {@code traceTarget()} 把 trace <b>直接写到 per-case</b>，全程只有一份：
 * 已就位则仅登记 tracePath（不搬动）；外来路径才落入 per-case（报告目录内移动消副本、目录外拷贝）。
 */
class ReportBusTraceTargetTest {

    @Test
    @DisplayName("traceTarget 指向 per-case trace.zip；文件已就位时 attachTrace 仅登记不搬动")
    void traceTargetAndRegisterInPlace() throws Exception {
        Path dir = Files.createTempDirectory("pw-trace-target");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());
        StepHandle h = bus.startCase("c1", "feature", "scenario", List.of("t"), "feature.feature");

        Path target = bus.traceTarget(h);
        assertThat(target).isEqualTo(dir.resolve("c1").resolve("trace.zip"));

        //  框架已按 traceTarget 直接写入 → attachTrace 只登记，不拷贝/移动（trace 全程一份）
        Files.createDirectories(target.getParent());
        Files.write(target, "zip-bytes".getBytes());
        bus.attachTrace(h, target);

        assertThat(Files.exists(target)).isTrue();
        assertThat(new String(Files.readAllBytes(target))).isEqualTo("zip-bytes");
        assertThat(bus.outcomeSnapshots().get(0).getTracePath()).isEqualTo("c1/trace.zip");
    }

    @Test
    @DisplayName("外来路径兜底：报告目录外拷贝（原件保留），报告目录内移动（消副本）")
    void foreignTraceIsCopiedOrMoved() throws Exception {
        Path dir = Files.createTempDirectory("pw-trace-target2");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());

        //  报告目录外 → 拷贝（框架侧原件保留）
        Path outside = Files.createTempDirectory("pw-trace-outside").resolve("trace.zip");
        Files.write(outside, "abc".getBytes());
        bus.attachTrace(bus.startCase("c1", "feature", "scenario", List.of("t"), "feature.feature"), outside);
        assertThat(Files.exists(outside)).isTrue();
        assertThat(Files.exists(dir.resolve("c1").resolve("trace.zip"))).isTrue();

        //  报告目录内（traces/）→ 移动（不留副本）
        Path inside = dir.resolve("traces").resolve("trace-x.zip");
        Files.createDirectories(inside.getParent());
        Files.write(inside, "abc".getBytes());
        bus.attachTrace(bus.startCase("c2", "feature", "scenario2", List.of("t"), "feature.feature"), inside);
        assertThat(Files.exists(inside)).isFalse();
        assertThat(Files.exists(dir.resolve("c2").resolve("trace.zip"))).isTrue();
    }

    @Test
    @DisplayName("traceTargetByOutcomeId 绕开句柄按用例 id 解析到 per-case（收尾线程去兜底根基）")
    void traceTargetByOutcomeIdResolvesPerCase() throws Exception {
        Path dir = Files.createTempDirectory("pw-trace-byoid");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());
        bus.startCase("c1", "feature", "scenario", List.of("t"), "feature.feature");

        //  不再依赖 ThreadLocal 句柄，仅凭用例 id 即可定位 per-case 落点
        Path target = bus.traceTargetByOutcomeId("c1");
        assertThat(target).isEqualTo(dir.resolve("c1").resolve("trace.zip"));

        //  未知 / 空 id → 回落 null（框架走自有 traces/ 证据目录）
        assertThat(bus.traceTargetByOutcomeId("no-such")).isNull();
        assertThat(bus.traceTargetByOutcomeId(null)).isNull();
    }

    @Test
    @DisplayName("videoTargetByOutcomeId 绕开句柄按用例 id 解析到 per-case/records（收尾线程去兜底根基）")
    void videoTargetByOutcomeIdResolvesPerCase() throws Exception {
        Path dir = Files.createTempDirectory("pw-video-byoid");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());
        bus.startCase("c1", "feature", "scenario", List.of("t"), "feature.feature");

        Path dir1 = bus.videoTargetByOutcomeId("c1");
        assertThat(dir1).isEqualTo(dir.resolve("c1").resolve("records"));
        assertThat(Files.isDirectory(dir1)).isTrue();

        assertThat(bus.videoTargetByOutcomeId("no-such")).isNull();
    }

    @Test
    @DisplayName("bridge.traceTarget()/videoTarget() 在用例活动期解析到 per-case（彻底去兜底：不返回 null）")
    void bridgeTraceTargetResolvesPerCaseWhenActive() throws Exception {
        Path dir = Files.createTempDirectory("pw-bridge-target");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());
        ReportBus previous = ReportBus.getDefault();
        ReportBus.installDefault(bus);
        try {
            ReportingBridgeImpl bridge = new ReportingBridgeImpl();
            bus.startCase("c1", "feature", "scenario", List.of("t"), "feature.feature");

            //  收尾线程（CloseGuard worker）即便无 ThreadLocal 句柄，也应凭活动用例 id 解析到 per-case，
            //  而非返回 null 落到全局 traces/ 再搬——这正是彻底去兜底要消除的副本逻辑。
            assertThat(bridge.traceTarget()).isEqualTo(dir.resolve("c1").resolve("trace.zip"));
            assertThat(bridge.videoTarget()).isEqualTo(dir.resolve("c1").resolve("records"));
        } finally {
            ReportBus.installDefault(previous);
        }
    }
}
