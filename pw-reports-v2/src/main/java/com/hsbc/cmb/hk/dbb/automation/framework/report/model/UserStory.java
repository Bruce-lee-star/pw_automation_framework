package com.hsbc.cmb.hk.dbb.automation.framework.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 用户故事 / feature 信息——JSON 结构对齐 Serenity {@code Story} 中报告实际读取的字段
 * （{@code storyName} / {@code displayName}，消费方实证见 {@code SummaryReportGenerator}）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class UserStory {

    /** feature 名（场景大纲同名时的区分维度）。 */
    private String storyName;

    /** 展示名（feature 文件名去扩展名）。 */
    private String displayName;

    /** feature 文件 URI（相对路径）。 */
    private String path;

    /** 类型，固定 {@code "feature"}。 */
    private String type = "feature";

    public UserStory() {
    }

    public UserStory(String storyName, String displayName, String path) {
        this.storyName = storyName;
        this.displayName = displayName;
        this.path = path;
    }

    public String getStoryName() {
        return storyName;
    }

    public void setStoryName(String storyName) {
        this.storyName = storyName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
