package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;

import java.util.Map;
import java.util.Objects;

/**
 * 参数范围门禁（v3.7）：运行期在 {@code BrowserCommandChannel.execute(Command)} 边界，
 * 校验每步命令参数是否落在 {@link ParamScope} 声明的取值域内。
 *
 * <p><b>fail-closed</b>：scope 中<b>未声明</b>的参数（{@code secretRef} 引用型除外）一律拒绝；
 * 已声明参数若取值越界，抛 {@link ParamScopeViolationException}，<b>不执行该步</b>。</p>
 *
 * <p>scope 为 {@code null} 或空 = 无约束（调用方若要求「副作用动作必须声明 scope」，
 * 由独立的治理层负责；本门禁只做取值域核对，保持与定位器审批 {@code ApprovalGrant} 解耦）。</p>
 *
 * @apiNote 框架内部门禁：业务代码不得直接调用，应统一经 execute 边界挂接。
 */
public final class ParamScopeGate {

    /** 引用型参数（R1）：仅作密钥引用，不是可被取值域约束的数据值，豁免校验。 */
    private static final String SECRET_REF = "secretRef";

    private ParamScopeGate() {
    }

    public static void check(ParamScope scope, Command command) {
        Objects.requireNonNull(command, "command");
        if (scope == null || scope.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : command.params().entrySet()) {
            String key = e.getKey();
            if (SECRET_REF.equals(key)) {
                continue;
            }
            ParamConstraint c = scope.constraintFor(key).orElse(null);
            if (c == null) {
                throw new ParamScopeViolationException(
                        command.action() + " @ " + command.targetRef()
                                + ": 参数 '" + key + "' 未声明于 scope（fail-closed，拒绝未声明的参数）");
            }
            String value = e.getValue();
            if (!c.allows(value)) {
                throw new ParamScopeViolationException(
                        command.action() + " @ " + command.targetRef()
                                + ": 参数 '" + key + "' 取值 '" + value + "' 超出 scope 允许范围");
            }
        }
    }
}
