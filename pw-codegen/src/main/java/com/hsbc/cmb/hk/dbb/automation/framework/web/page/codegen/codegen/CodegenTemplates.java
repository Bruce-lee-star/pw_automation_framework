package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import freemarker.template.Configuration;
import freemarker.template.Template;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 代码生成模板的统一渲染入口（FreeMarker，与框架报告侧 pw-reports 共用引擎 {@code 2.3.34}）。
 *
 * <p>Page / Step / Assert 三类产物<b>共用同一套模板机制</b>：模板落在
 * {@code src/main/resources/codegen/templates/}（{@code page-class.ftl} / {@code step-class.ftl} /
 * {@code assert-class.ftl}），各生成器只负责装配模型。
 *
 * <p>两处刻意的处理：
 * <ul>
 *   <li><b>纯文本输出</b>：模板首行 {@code <#ftl output_format="plainText">} 声明，避免注解里的
 *       {@code "} 被 HTML 自动转义成 {@code &#34;}；</li>
 *   <li><b>行尾归一化为 LF</b>：模板资源 Windows 检出为 CRLF、Linux CI 为 LF，不归一会导致同一批拾取
 *       在不同 OS 渲染出的产物字节不一致，使"CI 漂移校验"永远有 diff。</li>
 * </ul>
 */
public final class CodegenTemplates {

    private static final String TEMPLATE_DIR = "/codegen/templates/";

    private static final Configuration FM = new Configuration(Configuration.VERSION_2_3_34);
    static {
        FM.setDefaultEncoding(StandardCharsets.UTF_8.name());
    }

    private CodegenTemplates() {}

    public static String render(String templateName, Map<String, Object> model) {
        try {
            Template t = new Template(templateName, new StringReader(loadTemplate(templateName)), FM);
            StringWriter out = new StringWriter();
            t.process(model, out);
            String rendered = out.toString();
            return rendered.contains("\r") ? rendered.replace("\r\n", "\n").replace('\r', '\n') : rendered;
        } catch (IOException | freemarker.template.TemplateException e) {
            throw new IllegalStateException("failed to render codegen template: " + templateName, e);
        }
    }

    private static String loadTemplate(String name) {
        String path = TEMPLATE_DIR + name;
        try (InputStream in = CodegenTemplates.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("codegen template not found on classpath: " + path);
            }
            StringBuilder sb = new StringBuilder();
            try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                char[] buf = new char[1024];
                int n;
                while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read codegen template: " + path, e);
        }
    }
}
