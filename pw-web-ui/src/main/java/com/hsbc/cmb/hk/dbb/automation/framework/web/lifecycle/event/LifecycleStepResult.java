package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import java.util.Objects;

/**
 * 步骤级生命周期结果（替代 serenity 的 {@code StepFailure} / 步骤结果）。
 *
 * <p>不可变快照；线程安全。</p>
 */
public final class LifecycleStepResult {

    private final LifecycleStatus status;
    private final Throwable error;
    private final String stepName;
    private final int line;

    private LifecycleStepResult(Builder b) {
        this.status = Objects.requireNonNull(b.status, "status");
        this.error = b.error;
        this.stepName = b.stepName;
        this.line = b.line;
    }

    public LifecycleStatus status() {
        return status;
    }

    public Throwable error() {
        return error;
    }

    public String stepName() {
        return stepName;
    }

    public int line() {
        return line;
    }

    public boolean failed() {
        return status == LifecycleStatus.FAILED;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private LifecycleStatus status = LifecycleStatus.PASSED;
        private Throwable error;
        private String stepName;
        private int line;

        public Builder status(LifecycleStatus status) {
            this.status = status;
            return this;
        }

        public Builder error(Throwable error) {
            this.error = error;
            return this;
        }

        public Builder stepName(String stepName) {
            this.stepName = stepName;
            return this;
        }

        public Builder line(int line) {
            this.line = line;
            return this;
        }

        public LifecycleStepResult build() {
            return new LifecycleStepResult(this);
        }
    }
}
