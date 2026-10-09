package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

/**
 * {@link ApiCaptureQuery} 的注册表。
 *
 * <p>与既有 {@code RouteAssertionRegistry} 同范式：pw-route 的实现类按<b>类名懒加载</b>
 * （双重检查锁），pw-route 不在 classpath 时静默降级为 {@code null}，调用方按空结果处理——
 * 这样 pw-web-ui 只依赖 pw-core 也能编译与运行。
 */
public final class ApiCaptureQueryRegistry {

    /** pw-route 侧实现（保持字符串引用，避免 pw-core 编译期依赖 pw-route）。 */
    private static final String IMPL_CLASS =
            "com.hsbc.cmb.hk.dbb.automation.framework.route.lifecycle.ApiCaptureQueryImpl";

    private static volatile ApiCaptureQuery query;
    private static volatile boolean initialized;

    private ApiCaptureQueryRegistry() {
    }

    /** pw-route 实现可在静态块中主动注册（幂等；已加载则覆盖）。 */
    public static void register(ApiCaptureQuery impl) {
        if (impl != null) {
            query = impl;
            initialized = true;
        }
    }

    /** 清空注册（测试用）。 */
    public static void clear() {
        query = null;
        initialized = false;
    }

    /**
     * 取实现。
     *
     * @return 实现实例；pw-route 不在 classpath（或实例化失败）时返回 {@code null}
     */
    public static ApiCaptureQuery get() {
        if (!initialized) {
            synchronized (ApiCaptureQueryRegistry.class) {
                if (!initialized) {
                    try {
                        // 触发实现类初始化：其静态块会自注册（幂等）。forName 默认 initialize=true，
                        // 故静态块必然在此刻执行，随后无需再反射实例化（避免白建第二个无状态实例）。
                        Class<?> impl = Class.forName(IMPL_CLASS);
                        if (query == null) {
                            // 兜底：实现类未自注册（例如将来的版本去掉了静态块）时，仍按旧路径实例化。
                            query = (ApiCaptureQuery) impl.getDeclaredConstructor().newInstance();
                        }
                    } catch (Exception | LinkageError e) {
                        // pw-route 未在 classpath：降级为 null，由调用方返回空结果（不抛错、不刷屏）
                        query = null;
                    } finally {
                        initialized = true;
                    }
                }
            }
        }
        return query;
    }
}
