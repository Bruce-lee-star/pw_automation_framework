package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 场景级生命周期信息（替代 serenity 的 {@code TestOutcome} 中场景元数据部分）。
 *
 * <p>携带场景名、标签（含 {@code @} 前缀，如 {@code @test}）、所属 feature 名与 uri，供
 * AutoBrowser 标签解析、并发闸门等消费。不可变快照；线程安全。</p>
 */
public final class LifecycleScenarioInfo {

    private final String name;
    private final List<String> tags;
    private final String featureName;
    private final String uri;

    private LifecycleScenarioInfo(Builder b) {
        this.name = Objects.requireNonNull(b.name, "name");
        this.tags = Collections.unmodifiableList(new ArrayList<>(b.tags));
        this.featureName = b.featureName;
        this.uri = b.uri;
    }

    public String name() {
        return name;
    }

    public List<String> tags() {
        return tags;
    }

    public String featureName() {
        return featureName;
    }

    public String uri() {
        return uri;
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name = "";
        private final List<String> tags = new ArrayList<>();
        private String featureName = "";
        private String uri = "";

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder tags(List<String> tags) {
            if (tags != null) {
                this.tags.addAll(tags);
            }
            return this;
        }

        public Builder featureName(String featureName) {
            this.featureName = featureName;
            return this;
        }

        public Builder uri(String uri) {
            this.uri = uri;
            return this;
        }

        public LifecycleScenarioInfo build() {
            return new LifecycleScenarioInfo(this);
        }
    }
}
