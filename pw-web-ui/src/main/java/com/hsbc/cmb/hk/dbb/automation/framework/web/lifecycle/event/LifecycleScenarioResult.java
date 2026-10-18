package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import java.util.Objects;

/**
 * 场景级生命周期结果（替代 serenity 的 {@code TestOutcome} 结果部分）。
 *
 * <p>不可变快照；线程安全。</p>
 */
public final class LifecycleScenarioResult {

    private final LifecycleScenarioInfo info;
    private final LifecycleStatus status;
    private final Throwable error;

    private LifecycleScenarioResult(Builder b) {
        this.info = Objects.requireNonNull(b.info, "info");
        this.status = Objects.requireNonNull(b.status, "status");
        this.error = b.error;
    }

    public LifecycleScenarioInfo info() {
        return info;
    }

    public LifecycleStatus status() {
        return status;
    }

    public Throwable error() {
        return error;
    }

    public boolean failed() {
        return status == LifecycleStatus.FAILED;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private LifecycleScenarioInfo info;
        private LifecycleStatus status = LifecycleStatus.PASSED;
        private Throwable error;

        public Builder info(LifecycleScenarioInfo info) {
            this.info = info;
            return this;
        }

        public Builder status(LifecycleStatus status) {
            this.status = status;
            return this;
        }

        public Builder error(Throwable error) {
            this.error = error;
            return this;
        }

        public LifecycleScenarioResult build() {
            return new LifecycleScenarioResult(this);
        }
    }
}
