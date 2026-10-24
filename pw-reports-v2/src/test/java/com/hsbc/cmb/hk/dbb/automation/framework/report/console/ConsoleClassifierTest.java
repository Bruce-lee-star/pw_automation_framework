package com.hsbc.cmb.hk.dbb.automation.framework.report.console;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 控制台归类门禁——保证 route 注册、网络调用、应用日志、普通输出不再混在一个 “Standard output” 里。
 */
class ConsoleClassifierTest {

    @Test
    @DisplayName("Route 注册行归 route（含时间戳前缀也能识别）")
    void classifiesRouteLines() {
        assertThat(ConsoleClassifier.category("[Route] register GET for 'capability'", false))
                .isEqualTo(ConsoleClassifier.ROUTE);
        assertThat(ConsoleClassifier.category(
                "12:00:01.123 [main] INFO c.RouteDsl - [Route] register GET route for 'x'", false))
                .isEqualTo(ConsoleClassifier.ROUTE);
    }

    @Test
    @DisplayName("网络调用归 net：NET 前缀与 HTTP 方法行")
    void classifiesNetworkLines() {
        assertThat(ConsoleClassifier.category("NET GET https://api/x -> 200 (12 ms)", false))
                .isEqualTo(ConsoleClassifier.NETWORK);
        assertThat(ConsoleClassifier.category("POST https://api/x", false))
                .isEqualTo(ConsoleClassifier.NETWORK);
    }

    @Test
    @DisplayName("应用日志不再单列（无 Application log 组）：级别前缀与时间戳并入 out")
    void classifiesLogLinesAsConsole() {
        assertThat(ConsoleClassifier.category("INFO c.Foo - started", false)).isEqualTo(ConsoleClassifier.CONSOLE);
        assertThat(ConsoleClassifier.category("12:00:01.123 [main] WARN c.Bar - slow", false))
                .isEqualTo(ConsoleClassifier.CONSOLE);
    }

    @Test
    @DisplayName("普通 stdout 归 out；stderr 默认归 err，但 route/net 保持语义分类")
    void classifiesConsoleAndError() {
        assertThat(ConsoleClassifier.category("hello world", false)).isEqualTo(ConsoleClassifier.CONSOLE);
        assertThat(ConsoleClassifier.category("hello world", true)).isEqualTo(ConsoleClassifier.ERROR);
        assertThat(ConsoleClassifier.category("INFO c.Foo - started", true)).isEqualTo(ConsoleClassifier.ERROR);
        assertThat(ConsoleClassifier.category("[Route] register GET", true)).isEqualTo(ConsoleClassifier.ROUTE);
    }

    @Test
    @DisplayName("分组顺序固定 route→net→out→err（无 log 组），且只输出非空组")
    void groupsAreOrderedAndNonEmptyOnly() {
        List<Map<String, Object>> groups = ConsoleClassifier.classify(
                List.of("plain stdout", "INFO c.Foo - started", "[Route] register GET",
                        "NET GET https://api/x -> 200"),
                List.of("boom"));

        assertThat(groups).extracting(g -> g.get("key"))
                .containsExactly("route", "net", "out", "err");
        assertThat(groups).allSatisfy(g -> {
            assertThat(g.get("label")).isNotNull();
            assertThat((List<?>) g.get("lines")).isNotEmpty();
        });
    }

    @Test
    @DisplayName("空/仅空行输入返回空分组")
    void emptyInputYieldsNoGroups() {
        assertThat(ConsoleClassifier.classify(List.of(), List.of())).isEmpty();
        assertThat(ConsoleClassifier.classify(null, null)).isEmpty();
        assertThat(ConsoleClassifier.classify(List.of("  "), List.of())).isEmpty();
    }

    @Test
    @DisplayName("route 组标题为 Route registration（与 Route Captured 区分）")
    void routeGroupLabelIsRouteRegistration() {
        List<Map<String, Object>> groups =
                ConsoleClassifier.classify(List.of("[Route] register GET route for 'x'"), List.of());
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).get("label")).isEqualTo("Route registration");
    }

    @Test
    @DisplayName("net 组标题为 Route Captured（实际抓到的调用）")
    void networkGroupLabelIsRouteCaptured() {
        List<Map<String, Object>> groups =
                ConsoleClassifier.classify(List.of("NET GET https://api/x -> 200 (5 ms)"), List.of());
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).get("label")).isEqualTo("Route Captured");
    }

    @Test
    @DisplayName("步骤级捕获只留 net；注册日志留在 Route Register，不重复进捕获区")
    void capturedAndResidualSplitsAreDisjoint() {
        List<String> out = List.of("plain stdout", "[Route] register GET", "NET GET https://api/x -> 200");
        List<String> err = List.of("boom");

        assertThat(ConsoleClassifier.classifyCaptured(out, err))
                .extracting(g -> g.get("key"))
                .containsExactly("net");
        assertThat(ConsoleClassifier.classifyResidual(out, err))
                .extracting(g -> g.get("key"))
                .containsExactly("out", "err");
    }
}
