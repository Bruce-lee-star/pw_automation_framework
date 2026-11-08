package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.execution.StepPlanActionSink;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.execution.StepPlanActionSinkProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 生产 SPI 实现：为生成 case 自动提供 {@link PlaywrightStepPlanActionSink}，业务无需任何钩子。
 *
 * <p><b>「登录信息 + 所登录环境」如何被消费</b>：框架不做完整 SSO 登录（那是业务
 * {@code Given logon DBB "<env>" ... as user "<user>"} 步骤的职责，凭据与环境 URL 取自
 * 业务 {@code pw.conf} 的 {@code userinfo_<env>.<user>.*} / {@code environment.<env>}）。
 * 本 provider 只从<b>同一个环境配置</b>推导导航白名单，使框架与业务使用同一套环境信息，
 * 不需要业务再写一份 host 清单。</p>
 *
 * <p>以下两项为运行期可选参数（走 {@link ConfigReader}，系统属性优先级最高；刻意不登记进
 * {@code ConfigRegistry}，不影响 {@code config-keys-golden} 基线）。启用开关本身已登记在
 * {@link WebFrameworkConfig#AGENT_SEMANTIC_ENABLED}：</p>
 * <ul>
 *   <li>{@value #ALLOWED_HOSTS_KEY} —— 逗号分隔的额外白名单 host（也可写完整 URL）；</li>
 *   <li>{@value #ENV_KEY} —— 登录环境名，自动读 {@code environment.<env>} 的 URL 并取其 host；</li>
 * </ul>
 * 两者都为空时 {@code sink.navigateTo} 走 {@link PlaywrightStepPlanActionSink#denyAll()}
 * （fail-closed）；业务自有登录步骤不经过该策略，故不会打断登录。</p>
 *
 * <p><b>惰性铁律</b>：{@link #create()} 只组装对象，不调用 {@code getPage()}、
 * 不启动浏览器；Page 经 {@code PlaywrightManager::getPage} 在每个动作时按需取得。</p>
 */
public final class PlaywrightStepPlanActionSinkProvider implements StepPlanActionSinkProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaywrightStepPlanActionSinkProvider.class);

    /** 额外白名单 host（逗号分隔）。 */
    public static final String ALLOWED_HOSTS_KEY = "pw.agent.semantic.allowedHosts";
    /** 登录环境名，用于从 {@code environment.<env>} 推导允许 host。 */
    public static final String ENV_KEY = "pw.agent.semantic.env";

    @Override
    public StepPlanActionSink create() {
        // 双保险：即使调用方绕过 StepPlanActionSinks 直接取 provider，关闭时也不装配。
        if (!WebFrameworkConfig.AGENT_SEMANTIC_ENABLED.getBooleanValue()) {
            throw new IllegalStateException(
                    "语义 Agent 未启用，拒绝装配生产 sink；请先设置 -D"
                            + WebFrameworkConfig.AGENT_SEMANTIC_ENABLED.getKey() + "=true");
        }
        return new PlaywrightStepPlanActionSink(PlaywrightManager::getPage, null, urlPolicy());
    }

    /** 包级可见供单测直接验证配置解析；生产经 {@link #create()} 调用。 */
    static Predicate<String> urlPolicy() {
        Set<String> hosts = new LinkedHashSet<>();
        String explicit = ConfigReader.getProperty(ALLOWED_HOSTS_KEY);
        if (explicit != null) {
            for (String part : explicit.split(",")) {
                String host = hostOf(part);
                if (host != null) {
                    hosts.add(host);
                }
            }
        }
        String env = ConfigReader.getProperty(ENV_KEY);
        if (env != null && !env.isBlank()) {
            String loginUrl = ConfigReader.getProperty("environment." + env.trim());
            String host = hostOf(loginUrl);
            if (host != null) {
                hosts.add(host);
            } else {
                LOGGER.warn("[semantic-nav] 环境 [{}] 的 environment.{} 未配置或非合法 URL，忽略该 host",
                        env, env.trim());
            }
        }
        if (hosts.isEmpty()) {
            LOGGER.warn("[semantic-nav] 未配置 {} / {}，sink.navigateTo 将 fail-closed；"
                            + "业务自有登录步骤不受影响",
                    ALLOWED_HOSTS_KEY, ENV_KEY);
            return PlaywrightStepPlanActionSink.denyAll();
        }
        LOGGER.info("[semantic-nav] navigation allowed hosts = {}", hosts);
        return PlaywrightStepPlanActionSink.allowingHosts(hosts.toArray(new String[0]));
    }

    /** 接受纯 host 或完整 URL；非法值返回 null（不抛，避免单个坏配置拖垮装配）。 */
    private static String hostOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        try {
            String host = URI.create(trimmed).getHost();
            if (host != null && !host.isEmpty()) {
                return host;
            }
            if (trimmed.contains("://") || trimmed.contains("/")) {
                return null;
            }
            return trimmed;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
