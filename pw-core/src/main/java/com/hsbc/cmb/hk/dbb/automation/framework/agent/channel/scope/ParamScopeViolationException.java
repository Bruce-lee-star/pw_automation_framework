package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope;

/**
 * 参数越界：命令参数未声明于 scope，或取值超出 {@link ParamScope} 允许范围。
 *
 * <p>门禁 fail-closed：抛出即意味着该步<b>不执行</b>，不存在「次优回退」。</p>
 */
public class ParamScopeViolationException extends RuntimeException {

    public ParamScopeViolationException(String message) {
        super(message);
    }
}
