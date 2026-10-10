package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertTrue;

/**
 * Java↔JS 契约门禁（2026-10-10 新增）。
 *
 * <p><b>要防的缺陷</b>：{@link RolePickerConstants}（{@code CMD_*} / {@code MODE_*} / {@code STATE_KEY_*} /
 * {@code STRATEGY_*}）是 Java 与浏览器面板脚本之间的<b>字符串契约</b>。历史上出现过 F-06 那类错位
 * （Java {@code scan_page} vs JS {@code scanPage}），症状是<b>静默失效</b>：模式判定恒不成立、
 * 面板按钮禁用/提示失效，且没有任何测试报警。</p>
 *
 * <p><b>断言范围</b>：{@link RolePickerScripts} 里<b>全部</b> {@code public static final String}
 * 脚本常量（即拾取器可能注入浏览器的所有脚本）的并集 —— 而不是只拼 {@code START_SCRIPT} /
 * {@code PANEL_SCRIPT} 两个组合体：{@code stateJson} 由 {@code apply-pick-state-js.js} 消费、
 * {@code pageName} 由 {@code set-page-name-*.js} 消费，二者都不在那两个组合体里
 * （2026-10-10 实测：范围放窄会让契约测试出现假失败）。通过反射收集，新增脚本常量自动纳入守护。</p>
 *
 * <p><b>与既有守卫的分工</b>：JS 语法与组合体由 {@code pw-core} 的 validate 阶段
 * （{@code tools/validate_picker_js.js}）兜底；本测试只守<b>字符串一致性</b>（契约键是否仍在脚本里）。</p>
 */
public class RolePickerContractTest {

    /** 拾取器可能注入浏览器的全部脚本（{@link RolePickerScripts} 的 public static final String 常量并集）。 */
    private static String allScripts() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Field f : RolePickerScripts.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())
                    && f.getType() == String.class) {
                sb.append(f.get(null)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 反"幻影守卫"前置断言：脚本并集为空/异常偏小（资源未加载、常量被 stubs 化）时，
     * 下面所有 {@code contains} 断言都失去意义 —— 必须先把它钉住。
     */
    @Test
    public void injectedScripts_areNotPhantom() throws Exception {
        String all = allScripts();
        assertTrue("脚本常量并集异常偏小（实际 " + all.length() + "）", all.length() > 100_000);
        assertTrue("START_SCRIPT 必须是组合后的拾取核心脚本（实际 " + RolePickerScripts.START_SCRIPT.length() + "）",
                RolePickerScripts.START_SCRIPT.length() > 20_000);
        assertTrue("PANEL_SCRIPT 必须是组合后的面板脚本（实际 " + RolePickerScripts.PANEL_SCRIPT.length() + "）",
                RolePickerScripts.PANEL_SCRIPT.length() > 20_000);
    }

    /**
     * {@code MODE_*} 必须与面板 JS 的比对字面量一致（当前契约面只有 idle / manual：
     * 「扫描整页 / 区域扫描」两类命令已整体移除，模式只剩手动拾取）。
     */
    @Test
    public void modeConstants_matchBrowserSideLiterals() throws Exception {
        assertContainsQuoted(allScripts(), "MODE 契约键", RolePickerConstants.MODE_IDLE);
        assertContainsQuoted(allScripts(), "MODE 契约键", RolePickerConstants.MODE_MANUAL);
    }

    /** {@code CMD_*}：面板 → Java 引擎的命令名必须出现在某个脚本里（否则命令永远发不出来）。 */
    @Test
    public void commandNames_appearInScripts() throws Exception {
        String all = allScripts();
        assertContains(all, "CMD 契约键", RolePickerConstants.CMD_START);
        assertContains(all, "CMD 契约键", RolePickerConstants.CMD_PACKAGE);
        assertContains(all, "CMD 契约键", RolePickerConstants.CMD_REFRESH_CODE);
        assertContains(all, "CMD 契约键", RolePickerConstants.CMD_STOP);
        assertContains(all, "CMD 契约键", RolePickerConstants.CMD_ABORT);
        assertContains(all, "CMD 契约键", RolePickerConstants.CMD_DONE);
    }

    /** {@code STATE_KEY_*}：Java↔JS 通信实参键（对应脚本里读取的 {@code a.xxx}）。 */
    @Test
    public void stateKeys_appearInScripts() throws Exception {
        String all = allScripts();
        assertContains(all, "STATE 契约键", RolePickerConstants.STATE_KEY_MODE);
        assertContains(all, "STATE 契约键", RolePickerConstants.STATE_KEY_PAGE_NAME);
        assertContains(all, "STATE 契约键", RolePickerConstants.STATE_KEY_CODE);
        assertContains(all, "STATE 契约键", RolePickerConstants.STATE_KEY_MSG);
        assertContains(all, "STATE 契约键", RolePickerConstants.STATE_KEY_FILES);
        assertContains(all, "STATE 契约键", RolePickerConstants.STATE_KEY_NLS);
        assertContains(all, "STATE 契约键", RolePickerConstants.STATE_KEY_AUTO_STEP_COUNT);
        assertContains(all, "STATE 契约键", RolePickerConstants.STATE_KEY_STATE_JSON);
    }

    /** {@code STRATEGY_*}：拾取数据 schema 的策略名（改值必须同步 JS 侧）。 */
    @Test
    public void strategies_appearInScripts() throws Exception {
        String all = allScripts();
        assertContains(all, "STRATEGY 契约键", RolePickerConstants.STRATEGY_TEXT);
        assertContains(all, "STRATEGY 契约键", RolePickerConstants.STRATEGY_ALT_TEXT);
        assertContains(all, "STRATEGY 契约键", RolePickerConstants.STRATEGY_TITLE);
        assertContains(all, "STRATEGY 契约键", RolePickerConstants.STRATEGY_PLACEHOLDER);
        assertContains(all, "STRATEGY 契约键", RolePickerConstants.STRATEGY_LABEL);
        assertContains(all, "STRATEGY 契约键", RolePickerConstants.STRATEGY_TEST_ID);
        assertContains(all, "STRATEGY 契约键", RolePickerConstants.STRATEGY_ID);
        assertContains(all, "STRATEGY 契约键", RolePickerConstants.STRATEGY_CSS);
        assertContains(all, "STRATEGY 契约键", RolePickerConstants.STRATEGY_I18N);
    }

    private static void assertContains(String scripts, String what, String token) {
        assertTrue(what + " [" + token + "] 未出现在任何 picker 脚本常量里（Java↔JS 契约漂移）",
                scripts.contains(token));
    }

    private static void assertContainsQuoted(String scripts, String what, String token) {
        assertTrue(what + " ['" + token + "'] 未以单引号字面量出现在脚本里（与 JS 比对字面量不一致）",
                scripts.contains("'" + token + "'"));
    }
}
