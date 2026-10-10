package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

/**
 * Element操作异常类
 *
 * Enterprise-grade element operation exception with detailed diagnostic information.
 *
 * @since 1.0.0
 */
public class ElementOperationException extends ElementException {

    private static final long serialVersionUID = 1L;

    private final String selector;
    private final String operation;
    private final String pageUrl;
    private final String elementState;
    private final DiagnosticInfo diagnosticInfo;

    private ElementOperationException(String message, Throwable cause,
            String selector, String operation, String pageUrl,
            String elementState, DiagnosticInfo diagnosticInfo) {
        super(message, cause);
        this.selector = selector;
        this.operation = operation;
        this.pageUrl = pageUrl;
        this.elementState = elementState;
        this.diagnosticInfo = diagnosticInfo;
    }

    // Getters
    public String getSelector() { return selector; }
    public String getOperation() { return operation; }
    public String getPageUrl() { return pageUrl; }
    public String getElementState() { return elementState; }
    public DiagnosticInfo getDiagnosticInfo() { return diagnosticInfo; }

    @Override
    public String toString() {
        // 简洁一行格式 — 避免 ASCII 艺术被 Serenity/Cucumber 在控制台和报告中重复输出
        // 详细诊断信息可通过 getDiagnosticInfo() 和 getCause() 获取
        String diag = diagnosticInfo != null
            ? String.format(" exists=%s vis=%s en=%s ed=%s count=%d retries=%d",
                diagnosticInfo.existsInDom, diagnosticInfo.isVisible,
                diagnosticInfo.isEnabled, diagnosticInfo.isEditable,
                diagnosticInfo.elementCount, diagnosticInfo.retryCount)
            : "";
        String cause = getCause() != null
            ? " | cause: " + getCause().getClass().getSimpleName()
            : "";
        return String.format("[%s] %s on '%s'%s%s", elementState, operation, selector, diag, cause);
    }

    // ==================== Builder Pattern ====================

    public static class Builder {
        private String selector;
        private String operation;
        private String pageUrl = "unknown";
        private String elementState = "unknown";
        private DiagnosticInfo diagnosticInfo;
        private Throwable cause;
        private String customMessage;

        public Builder selector(String selector) { this.selector = selector; return this; }
        public Builder operation(String operation) { this.operation = operation; return this; }
        public Builder pageUrl(String pageUrl) { this.pageUrl = pageUrl; return this; }
        public Builder elementState(String elementState) { this.elementState = elementState; return this; }
        public Builder diagnosticInfo(DiagnosticInfo info) { this.diagnosticInfo = info; return this; }
        public Builder cause(Throwable cause) { this.cause = cause; return this; }
        public Builder customMessage(String message) { this.customMessage = message; return this; }

        public ElementOperationException build() {
            String message = customMessage != null ? customMessage :
                String.format("Operation [%s] failed on element [%s]", operation, selector);
            return new ElementOperationException(message, cause, selector, operation,
                pageUrl, elementState, diagnosticInfo);
        }
    }

    public static Builder builder() { return new Builder(); }

    /**
     * 快速创建异常（用于 waitFor* 等方法，无需 Builder）
     */
    public ElementOperationException(String operation, String selector, String message) {
        this(message, null, selector, operation, "unknown", "unknown", null);
    }

    /**
     * 快速创建异常（带原因）
     */
    public ElementOperationException(String operation, String selector, String message, Throwable cause) {
        this(message, cause, selector, operation, "unknown", "unknown", null);
    }

    // ==================== Diagnostic Info ====================

    /**
     * 失败诊断快照：<b>只保留异常消息/报告真正渲染的字段</b>
     * （见 {@link ElementOperationException#toString()}：exists / vis / en / ed / count / retries）。
     *
     * <p>历史上另有 {@code tagName} 与 {@code attributes}（自选属性白名单）——二者全仓<b>无任何消费方</b>
     * （无渲染、无读取），已删除；需要结构化 DOM 证据时应使用 Playwright 原生 {@code ariaSnapshot()}。</p>
     */
    public static class DiagnosticInfo {
        private boolean existsInDom;
        private boolean isVisible;
        private boolean isEnabled;
        private boolean isEditable;
        private int elementCount;
        private int retryCount;

        public DiagnosticInfo existsInDom(boolean val) { this.existsInDom = val; return this; }
        public DiagnosticInfo isVisible(boolean val) { this.isVisible = val; return this; }
        public DiagnosticInfo isEnabled(boolean val) { this.isEnabled = val; return this; }
        public DiagnosticInfo isEditable(boolean val) { this.isEditable = val; return this; }
        public DiagnosticInfo elementCount(int val) { this.elementCount = val; return this; }
        public DiagnosticInfo retryCount(int val) { this.retryCount = val; return this; }

        // Getters
        public boolean existsInDom() { return existsInDom; }
        public boolean isVisible() { return isVisible; }
        public boolean isEnabled() { return isEnabled; }
        public boolean isEditable() { return isEditable; }
        public int elementCount() { return elementCount; }
        public int retryCount() { return retryCount; }

        public static DiagnosticInfo create() { return new DiagnosticInfo(); }
    }
}
