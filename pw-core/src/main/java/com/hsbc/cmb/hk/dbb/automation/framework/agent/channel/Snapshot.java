package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 有界无障碍快照（设计 §4.2 SnapshotTree 的精简值类型）。
 *
 * <p>从 Playwright 无障碍树（{@code page.accessibility().snapshot()}）枚举，<b>仅携带白名单字段</b>
 * （角色、可访问名、可见/启用、脱敏文本），对应 R1/R7 数据最小化与 R6（不接外部脚本）。</p>
 *
 * <p>{@link ElementState#ref()} 即 {@link LocatorSpec} 的 canonical 串（角色优先），
 * 既是快照索引键，也是命令目标，可在 pw-core 与实现层无损传递。</p>
 */
public final class Snapshot {
    private final String pageRef;                       // 页面指纹
    private final Map<String, ElementState> byRef;      // ref(canonical) -> 状态
    private final SnapshotEnvelope envelope;            // 协议信封（可为 null：旧式精简快照）

    /** 旧构造器：无协议信封（测试用假通道 / 历史调用方），语义与 v3.7 之前完全一致。 */
    public Snapshot(String pageRef, List<ElementState> elements) {
        this(pageRef, elements, null);
    }

    /**
     * 带协议信封的构造器（生产采集路径）。
     *
     * @param envelope 观测版本/资源边界/状态；{@code null} 表示未声明协议（不触发验证侧 fail-closed 门禁）
     */
    public Snapshot(String pageRef, List<ElementState> elements, SnapshotEnvelope envelope) {
        this.pageRef = pageRef;
        this.byRef = elements.stream()
                .collect(Collectors.toMap(ElementState::ref, Function.identity(), (a, b) -> a));
        this.envelope = envelope;
    }

    public String pageRef() { return pageRef; }

    /** 快照协议信封；旧式精简快照返回 {@code null}。 */
    public SnapshotEnvelope envelope() { return envelope; }

    /** 该快照是否明确为完整观测（无信封的历史快照按「未声明」处理，返回 true 以保持向后兼容）。 */
    public boolean isComplete() {
        return envelope == null || envelope.isComplete();
    }
    public List<ElementState> elements() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(byRef.values()));
    }

    /** 按 ref（LocatorSpec canonical）查元素状态（联动断言主入口）。 */
    public ElementState byRef(String ref) { return byRef.get(ref); }

    /** 按 ref 查元素状态（联动断言主入口，传入定位符）。 */
    public ElementState byRef(LocatorSpec spec) { return byRef.get(spec.canonical()); }

    /** 单个元素的可观测状态。 */
    public static final class ElementState {
        private final String ref;       // 稳定引用（来自 SnapshotTree ref）
        private final String role;      // 角色（button/input/...）
        private final String name;      // 可访问名（脱敏）
        private final boolean visible;
        private final boolean enabled;
        private final String text;      // 可见文本（脱敏）

        public ElementState(String ref, String role, String name, boolean visible, boolean enabled, String text) {
            this.ref = ref; this.role = role; this.name = name;
            this.visible = visible; this.enabled = enabled; this.text = text;
        }
        public String ref() { return ref; }
        /** 还原为定位符（角色优先）。 */
        public LocatorSpec locator() { return LocatorSpec.parse(ref); }
        public String role() { return role; }
        public String name() { return name; }
        public boolean visible() { return visible; }
        public boolean enabled() { return enabled; }
        public String text() { return text; }
    }
}
