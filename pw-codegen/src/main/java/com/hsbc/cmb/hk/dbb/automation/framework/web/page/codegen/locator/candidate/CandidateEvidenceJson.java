package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 证据的 JSON 形态：让「观测证据」可以从 trace / 录制会话 / Agent 侧以文件形式提交。
 *
 * <p>为什么证据要能单独落成文件：验证结论必须绑定"哪一次观测"（见 {@link CandidateEvidence}），
 * 而观测发生在浏览器侧。把证据序列化成 JSON，浏览器侧与命令行侧就只用一个契约通信，
 * 不必在 CLI 里塞一个 Playwright 实例。
 *
 * <p>JSON 形态（字段可省，省即"未观测"）：
 * <pre>{@code
 * {
 *   "matches": 1,
 *   "visible": true,
 *   "enabled": true,
 *   "textMatched": true,
 *   "businessAssertion": "点击提交后出现成功提示",
 *   "assertionPassed": true,
 *   "detail": "trace=trace.zip#frame-42"
 * }
 * }</pre>
 *
 * <p><b>fail-closed 的关键细节</b>：{@code matches} 用 {@link Integer} 而不是 {@code int} 接收。
 * 若用 {@code int}，JSON 里漏写这个字段会被 Gson 默认成 {@code 0}，等于伪造了一条
 * "观测到命中 0 个元素"的证据；用包装类型则缺省是 {@code null}，可如实转成 {@code -1}（未观测），
 * 由 {@link CandidateValidator} 判为不通过。{@code assertionPassed} 同理：缺省 = 未执行，而不是"通过"。
 */
public final class CandidateEvidenceJson {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private CandidateEvidenceJson() {}

    /** 解析证据 JSON 字符串（结构错误抛 {@link IllegalArgumentException}）。 */
    public static CandidateEvidence parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("证据 JSON 为空");
        }
        EvidenceDto dto;
        try {
            dto = GSON.fromJson(json, EvidenceDto.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("证据 JSON 解析失败：" + e.getMessage(), e);
        }
        if (dto == null) {
            throw new IllegalArgumentException("证据 JSON 不是一个对象");
        }
        return dto.toEvidence();
    }

    /** 从文件读取证据（UTF-8）。 */
    public static CandidateEvidence read(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file)) {
            throw new IOException("证据文件不存在：" + file.toAbsolutePath());
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** 序列化证据（写入文件 / 审计留档用）。 */
    public static String toJson(CandidateEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        EvidenceDto dto = new EvidenceDto();
        dto.matches = evidence.matches() < 0 ? null : evidence.matches();
        dto.visible = evidence.visible();
        dto.enabled = evidence.enabled();
        dto.textMatched = evidence.textMatched();
        dto.businessAssertion = evidence.businessAssertion();
        dto.assertionPassed = evidence.assertionPassed();
        dto.detail = evidence.detail();
        return GSON.toJson(dto);
    }

    /**
     * 把证据写到文件（UTF-8，自动创建父目录）。
     *
     * <p>这是"录制会话/Trace 自动产出证据 JSON"的落点：取证跑完后把证据留档，
     * 审批人不必重跑浏览器就能复核"当时到底看到了什么"。</p>
     */
    public static void write(Path file, CandidateEvidence evidence) throws IOException {
        Objects.requireNonNull(file, "file");
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, toJson(evidence), StandardCharsets.UTF_8);
    }

    // --- Gson DTO：包装类型是刻意选择，缺省即"未观测" ---

    private static final class EvidenceDto {
        Integer matches;
        Boolean visible;
        Boolean enabled;
        Boolean textMatched;
        String businessAssertion;
        Boolean assertionPassed;
        String detail;

        CandidateEvidence toEvidence() {
            CandidateEvidence.Builder b = CandidateEvidence.builder()
                    .matches(matches == null ? -1 : matches)
                    .visible(Boolean.TRUE.equals(visible))
                    .enabled(Boolean.TRUE.equals(enabled))
                    .textMatched(Boolean.TRUE.equals(textMatched))
                    .businessAssertion(businessAssertion)
                    .detail(detail);
            if (assertionPassed != null) {
                b.assertionPassed(assertionPassed);
            }
            return b.build();
        }
    }
}
