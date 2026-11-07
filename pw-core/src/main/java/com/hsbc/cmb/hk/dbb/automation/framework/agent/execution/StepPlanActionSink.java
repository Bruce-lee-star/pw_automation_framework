package com.hsbc.cmb.hk.dbb.automation.framework.agent.execution;

/**
 * 路径 C 生成物（BDD Steps 类）的执行接缝。
 *
 * <p>agent 把已验证的 {@code StepPlan} 转成 {@code .feature + Glue + Steps} 后，生成的 Steps 类仅声明
 * 与受控原语一一对应的方法，并通过本接口把"执行"推迟到测试运行时注入的实现（由 agent 测试装配或 CI 提供，
 * 把 StepPlan 真正落到 {@code BrowserCommandChannel}）。这样生成的 BDD 资产<b>可编译、可审计</b>，
 * 但绝不在生成期伪造任何浏览器交互（I-02 红线：编译/冒烟委托 CI 真实执行）。</p>
 */
public interface StepPlanActionSink {

    void navigateTo(String url);

    void clickOn(String target);

    void fillWith(String target, String value);

    void selectIn(String target, String option);

    void check(String target);

    void uncheck(String target);

    void waitFor(String target);

    void expectVisible(String target);

    void expectText(String target);

    void switchToPopup();
}
