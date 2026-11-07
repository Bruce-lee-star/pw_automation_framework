package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import org.junit.Test;

import static org.junit.Assert.assertFalse;

/**
 * R1 红线门禁：凭据原文绝不出域（日志 / URL / 响应体）。
 *
 * <p>{@link SensitiveDataSanitizer} 是全框架脱敏的统一入口（门面）；本测试钉住其脱敏口径不被改坏，
 * 确保 password / token / Bearer / URL 内嵌 userinfo 等凭据原文不会出现在出域产物中。</p>
 */
public class SensitiveDataRedLineTest {

    @Test
    public void urlUserinfoMustBeStripped() {
        String out = SensitiveDataSanitizer.sanitizeUrl("https://user:secret@host.com/path?next=/home");
        assertFalse("URL userinfo (credential) must not leave the domain", out.contains("secret"));
        assertFalse("URL authority must not contain user:pass@", out.contains("user:secret@"));
    }

    @Test
    public void logMessageMustStripBearerToken() {
        String out = SensitiveDataSanitizer.sanitizeLogMessage("Authorization: Bearer abcdef1234567890");
        assertFalse("Bearer token must not leave the domain", out.contains("abcdef1234567890"));
    }

    @Test
    public void logMessageMustStripPasswordAssignment() {
        String out = SensitiveDataSanitizer.sanitizeLogMessage("password=hunter2secret");
        assertFalse("password value must not leave the domain", out.contains("hunter2secret"));
    }

    @Test
    public void bodyMustStripTokenField() {
        String out = SensitiveDataSanitizer.sanitizeBody("{\"token\":\"abcdef1234567890\",\"user\":\"alice\"}");
        assertFalse("token field value must not leave the domain", out.contains("abcdef1234567890"));
    }
}
