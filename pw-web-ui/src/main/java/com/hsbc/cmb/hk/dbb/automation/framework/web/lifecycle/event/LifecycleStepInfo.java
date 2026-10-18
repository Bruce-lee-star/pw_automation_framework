package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import java.util.Objects;

/**
 * 步骤开始信息（替代 serenity 的 {@code ExecutedStepDescription}）。
 *
 * <p>携带步骤标题与源码行号，供监听器在步骤开始时做截图时机判定、route 步骤边界标记、
 * 框架 {@code beforeStep} 桥接等。不可变快照；线程安全。</p>
 */
public final class LifecycleStepInfo {

    private final String stepName;
    private final int line;

    private LifecycleStepInfo(Builder b) {
        this.stepName = Objects.requireNonNull(b.stepName, "stepName");
        this.line = b.line;
    }

    public String stepName() {
        return stepName;
    }

    public int line() {
        return line;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String stepName = "";
        private int line;

        public Builder stepName(String stepName) {
            this.stepName = stepName;
            return this;
        }

        public Builder line(int line) {
            this.line = line;
            return this;
        }

        public LifecycleStepInfo build() {
            return new LifecycleStepInfo(this);
        }
    }
}
