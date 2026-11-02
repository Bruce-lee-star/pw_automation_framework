package com.hsbc.cmb.hk.dbb.automation.framework.report.step;

/**
 * 代理标记接口——识别「已是 pw-reports 步骤代理」的实例/类（R2 幂等：不重复代理）。
 */
public interface PwStepProxy {

    /** 被代理的原始步骤库类型（诊断/断言用）。 */
    Class<?> pwReportStepsType();
}
