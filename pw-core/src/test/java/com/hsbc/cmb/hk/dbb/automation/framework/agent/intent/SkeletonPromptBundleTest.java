package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link SkeletonPromptBundle} 契约测试（IDE 助手场景的「交出去」一侧）。
 *
 * <p>锁定：允许动作表与授权集合同源、默认值占位化（R1/R7 数据最小化）、
 * 关闭脱敏时逐字沿用骨架 JSON、输出确定性。</p>
 */
public class SkeletonPromptBundleTest {

    @Test
    public void containsEveryAuthorizedActionAndNoInternalPrimitive() {
        String bundle = SkeletonPromptBundle.of(skeleton());

        for (Command.ActionType action : IntentActionSchema.authorizedActions()) {
            assertTrue("缺少动作 " + action, bundle.contains("| " + action.name() + " |"));
        }
        // 框架内部原语不得出现在提示词里（否则等于诱导模型注入越权动作）
        assertFalse(bundle.contains(Command.ActionType.SWITCH_TO_POPUP.name()));
    }

    @Test
    public void redactsValuesByDefault() {
        String bundle = SkeletonPromptBundle.of(skeleton());

        assertFalse("业务值不得进入提示词: " + bundle, bundle.contains("alice"));
        assertTrue("占位符须保留结构信息", bundle.contains(SkeletonPromptBundle.VALUE_PLACEHOLDER));
    }

    @Test
    public void keepsValuesWhenRedactionDisabled() {
        String bundle = SkeletonPromptBundle.of(skeleton(), false);

        assertTrue(bundle.contains("alice"));
        // 关闭脱敏时直接内嵌 toJson()，与落盘形态逐字一致
        assertTrue(bundle.contains(skeleton().toJson()));
    }

    @Test
    public void isDeterministic() {
        assertEquals(SkeletonPromptBundle.of(skeleton()), SkeletonPromptBundle.of(skeleton()));
    }

    private static RecordedSkeleton skeleton() {
        return new RecordedSkeleton(RecordedSkeleton.SUPPORTED_SCHEMA_VERSION, "codegen",
                "https://www.example.com/logon",
                List.of(RecordedStep.of("NAVIGATE", null, "url", "https://www.example.com/logon"),
                        new RecordedStep("FILL", "role=textbox;name=User", Map.of("value", "alice"))));
    }
}
