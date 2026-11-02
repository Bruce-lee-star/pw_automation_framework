package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator;

/** 定位器策略常量（对应 Playwright 各候选策略，分数越低越优，详见技术方案文档）。 */
public final class LocatorStrategy {

    public static final String TESTID = "TESTID";
    public static final String ROLE_WITH_NAME = "ROLE_WITH_NAME";
    public static final String ROLE = "ROLE";
    public static final String CSS = "CSS";

    private LocatorStrategy() {}
}
