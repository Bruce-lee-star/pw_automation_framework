package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.execution.StepPlanActionSink;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.execution.StepPlanActionSinkProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PlaywrightStepPlanActionSinkProviderTest {

    private static final String HOSTS = PlaywrightStepPlanActionSinkProvider.ALLOWED_HOSTS_KEY;
    private static final String ENV = PlaywrightStepPlanActionSinkProvider.ENV_KEY;
    private static final String ENV_URL = "environment.O63_SIT1";
    private static final String ENABLED = WebFrameworkConfig.AGENT_SEMANTIC_ENABLED.getKey();

    private final Map<String, String> original = new LinkedHashMap<>();

    @Before
    public void setUp() {
        for (String key : new String[]{HOSTS, ENV, ENV_URL, ENABLED}) {
            original.put(key, System.getProperty(key));
            System.setProperty(key, "");
        }
        System.setProperty(ENABLED, "true");
    }

    @After
    public void tearDown() {
        original.forEach((key, value) -> System.setProperty(key,
                value == null ? WebFrameworkConfig.AGENT_SEMANTIC_ENABLED.getDefaultValue() : value));
    }

    @Test
    public void explicitHostsAreAppliedIncludingSubdomains() {
        System.setProperty(HOSTS, "example.com");

        Predicate<String> policy = PlaywrightStepPlanActionSinkProvider.urlPolicy();

        assertTrue(policy.test("https://portal.example.com/logon"));
        assertFalse(policy.test("https://evil.test/"));
    }

    @Test
    public void loginEnvironmentDerivesAllowedHostFromConfiguration() {
        System.setProperty(ENV, "O63_SIT1");
        System.setProperty(ENV_URL, "https://qualityassurance.example.com/portalserver/logon");

        Predicate<String> policy = PlaywrightStepPlanActionSinkProvider.urlPolicy();

        assertTrue(policy.test("https://qualityassurance.example.com/home"));
        assertFalse(policy.test("https://evil.test/"));
    }

    @Test
    public void fullUrlInAllowedHostsIsReducedToHost() {
        System.setProperty(HOSTS, "https://portal.example.com/logon, foo.test");

        Predicate<String> policy = PlaywrightStepPlanActionSinkProvider.urlPolicy();

        assertTrue(policy.test("https://portal.example.com/home"));
        assertTrue(policy.test("https://api.foo.test/x"));
        assertFalse(policy.test("https://evil.test/"));
    }

    @Test
    public void missingConfigurationIsFailClosed() {
        Predicate<String> policy = PlaywrightStepPlanActionSinkProvider.urlPolicy();

        assertFalse(policy.test("https://portal.example.com/logon"));
        assertFalse(policy.test(null));
    }

    @Test
    public void createIsLazyAndDoesNotTouchTheBrowser() {
        StepPlanActionSinkProvider provider = new PlaywrightStepPlanActionSinkProvider();

        StepPlanActionSink sink = provider.create();

        assertNotNull(sink);
        assertEquals("生产 provider 应创建 PlaywrightStepPlanActionSink",
                PlaywrightStepPlanActionSink.class, sink.getClass());
    }

    @Test
    public void createRefusesAssemblyWhenAgentIsDisabled() {
        System.setProperty(ENABLED, WebFrameworkConfig.AGENT_SEMANTIC_ENABLED.getDefaultValue());
        StepPlanActionSinkProvider provider = new PlaywrightStepPlanActionSinkProvider();

        IllegalStateException ex = assertThrows(IllegalStateException.class, provider::create);

        assertTrue(ex.getMessage().contains(ENABLED));
    }
}
