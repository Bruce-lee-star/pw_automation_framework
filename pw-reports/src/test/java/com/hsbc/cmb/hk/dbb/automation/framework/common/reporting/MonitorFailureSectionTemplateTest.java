package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import org.junit.Test;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * 校验独立报告模板 {@code monitor-failures-report.ftlh}：
 * 有内容时渲染按接口分组 + 去重后失败次数 + URL 列表 + 数据丢失红框。
 *
 * <p>本测试不依赖 route 模块，直接构造 {@link MonitorFailureReportData} 与去重后的 model 驱动模板。
 */
public class MonitorFailureSectionTemplateTest {

    private static final Configuration FM = new Configuration(Configuration.VERSION_2_3_33);
    static {
        FM.setClassForTemplateLoading(MonitorFailureSectionTemplateTest.class, "/templates");
        FM.setDefaultEncoding("UTF-8");
        FM.setRecognizeStandardFileExtensions(true);
        FM.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        FM.setLogTemplateExceptions(false);
        FM.setWrapUncheckedExceptions(true);
    }

    @Test
    public void rendersOwnerFailuresDedupedAndDataLoss() throws Exception {
        List<MonitorFailureItem> items = new ArrayList<>();
        // 同签名 3 次命中同 URL + 1 次不同 URL —— 去重后应为 2 条
        items.add(new MonitorFailureItem(
                "/api/v1/transfer", "POST", "/api/v1/transfer", "500 (expect 200)", "POST",
                "https://api.example.com/api/v1/transfer", "status=500 expected=200",
                List.of("Scenario A"), "", "", "", ""));
        items.add(new MonitorFailureItem(
                "/api/v1/transfer", "POST", "/api/v1/transfer", "500 (expect 200)", "POST",
                "https://api.example.com/api/v1/transfer", "status=500 expected=200",
                List.of("Scenario B"), "", "", "", ""));
        items.add(new MonitorFailureItem(
                "/api/v1/transfer", "POST", "/api/v1/transfer", "500 (expect 200)", "POST",
                "https://api.example.com/api/v1/transfer?retry=1", "status=500 expected=200",
                List.of("Scenario C"), "", "", "", ""));
        List<MonitorOwnerBlock> owners = new ArrayList<>();
        owners.add(new MonitorOwnerBlock("/api/v1/transfer", items));

        Map<String, Long> loss = new LinkedHashMap<>();
        loss.put("route_monitor_record", 3L);
        MonitorFailureReportData data = new MonitorFailureReportData(owners, loss, 3L, 1, 3);

        // 模拟 SummaryReportGenerator 的去重合并
        Map<String, Object> ownerView = new LinkedHashMap<>();
        ownerView.put("owner", "/api/v1/transfer");
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("method", "POST");
        merged.put("status", "500 (expect 200)");
        merged.put("reason", "status=500 expected=200");
        merged.put("count", 3);
        List<String> urls = new ArrayList<>(new LinkedHashSet<>(List.of(
                "https://api.example.com/api/v1/transfer",
                "https://api.example.com/api/v1/transfer?retry=1")));
        merged.put("urls", urls);
        merged.put("scenarios", List.of("Scenario A", "Scenario B"));
        merged.put("requestBody", "{\"amount\":100}");
        merged.put("responseBody", "{\"error\":\"internal\"}");
        ownerView.put("items", List.of(merged));
        List<Map<String, Object>> dedupedOwners = List.of(ownerView);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("report", data);
        model.put("dedupedOwners", dedupedOwners);

        Template tpl = FM.getTemplate("monitor-failures-report.ftlh");
        StringWriter out = new StringWriter();
        tpl.process(model, out);
        String html = out.toString();

        assertTrue("should contain report title", html.contains("API Monitor Failure Report"));
        assertTrue("should contain endpoint group", html.contains("/api/v1/transfer"));
        assertTrue("should contain collapsible structure", html.contains("<details class=\"fail-item\">"));
        assertTrue("should contain summary row", html.contains("<summary>"));
        assertTrue("should contain deduped failure count", html.contains("3x"));
        assertTrue("should contain both deduped URLs", html.contains("api/v1/transfer?retry=1"));
        assertTrue("should contain nested request/response details", html.contains("Request / response details"));
        assertTrue("should contain response body", html.contains("{\"error\":\"internal\"}"));
        assertTrue("should contain scenario names", html.contains("Scenario A"));
        assertTrue("should contain data-loss warning title", html.contains("Data integrity warning"));
        assertTrue("should contain loss record count", html.contains("3 records"));
        assertTrue("should contain white card container", html.contains("class=\"main\""));
        assertTrue("should contain stat number", html.contains(">3</div>"));
    }
}
