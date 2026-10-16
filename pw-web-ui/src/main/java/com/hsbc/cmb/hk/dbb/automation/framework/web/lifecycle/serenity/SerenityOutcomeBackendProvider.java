package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity;

import io.cucumber.core.backend.Backend;
import io.cucumber.core.backend.BackendProviderService;
import io.cucumber.core.backend.Container;
import io.cucumber.core.backend.Glue;
import io.cucumber.core.backend.HookDefinition;
import io.cucumber.core.backend.Lookup;
import io.cucumber.core.backend.Snippet;
import io.cucumber.core.backend.TestCaseState;

import java.lang.reflect.Type;
import java.net.URI;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 经 Cucumber {@link BackendProviderService} SPI 自动注册：在每个 scenario 结束时校验当前线程的
 * Serenity outcome，消除"日志报红但 IDEA 显示绿色"的不一致。
 *
 * <p>由 Cucumber 通过 {@code META-INF/services/io.cucumber.core.backend.BackendProviderService}
 * 自动发现（随 pw-web-ui 发布）。其 {@link Backend#loadGlue} 注册一个 After 钩子，
 * order = {@link Integer#MAX_VALUE}，确保<b>优先于常规清理 After 钩子之后</b>执行；
 * 若 Cucumber 场景本身已失败（{@code state.isFailed()}），则不重复覆盖原失败。</p>
 *
 * <p>该机制只"暴露"已被 Serenity 记录的失败，不引入新的失败判定；业务若希望某自愈重试整体判绿，
 * 应将重试放进 {@code @Step} 方法<b>内部</b>（让代理看不到异常），而非在 {@code @Step} 之外吞异常。</p>
 */
public final class SerenityOutcomeBackendProvider implements BackendProviderService {

    @Override
    public Backend create(Lookup lookup, Container container, Supplier<ClassLoader> classLoader) {
        return new SerenityOutcomeBackend();
    }

    /**
     * 注册结束校验钩子的 Backend 实现。
     */
    static final class SerenityOutcomeBackend implements Backend {

        @Override
        public void loadGlue(Glue glue, List<URI> gluePaths) {
            glue.addAfterHook(new OutcomeAfterHook());
        }

        @Override
        public void buildWorld() {
            // 无场景级 world 状态
        }

        @Override
        public void disposeWorld() {
            // 无场景级 world 状态
        }

        @Override
        public Snippet getSnippet() {
            return NOOP_SNIPPET;
        }
    }

    /**
     * scenario 结束校验钩子：Cucumber 已失败时跳过；否则校验 Serenity outcome。
     */
    static final class OutcomeAfterHook implements HookDefinition {

        @Override
        public void execute(TestCaseState state) {
            if (state != null && state.isFailed()) {
                // Cucumber 已经知道失败，保留原失败，不重复覆盖
                return;
            }
            SerenityOutcomeVerifier.verifyCurrentThreadOutcome();
        }

        @Override
        public String getTagExpression() {
            return null; // 对所有 scenario 生效
        }

        @Override
        public int getOrder() {
            return Integer.MAX_VALUE; // 最后执行，常规清理之后
        }

        @Override
        public String getLocation() {
            return SerenityOutcomeBackendProvider.class.getName() + "#outcome-verifier-after-hook";
        }

        @Override
        public boolean isDefinedAt(StackTraceElement stackTraceElement) {
            return false;
        }
    }

    private static final Snippet NOOP_SNIPPET = new Snippet() {
        private final MessageFormat template = new MessageFormat("");

        @Override
        public MessageFormat template() {
            return template;
        }

        @Override
        public String tableHint() {
            return "";
        }

        @Override
        public String arguments(Map<String, Type> ignored) {
            return "";
        }

        @Override
        public String escapePattern(String pattern) {
            return pattern;
        }
    };
}
