package com.hsbc.cmb.hk.dbb.automation.framework.agent.perf;

/**
 * 非功能基线样本的 SPI 出口（T9 / I-08）。
 *
 * <p>接口定义于 pw-core，实现由 pw-reports 经 {@code META-INF/services} 注册——与 R9
 * {@code AgentTraceSink} 同构，保持 core → reports 单向依赖，不引入新模块耦合。</p>
 *
 * <p><b>尽力而为</b>：实现不得抛出影响执行链的异常；调用方
 * {@link AgentPerfRegistry} 与 {@link AgentPerfProbe} 亦各自兜底。采集默认关闭，
 * 关闭时本接口不会被解析、不会被调用。</p>
 */
@FunctionalInterface
public interface AgentPerfSink {

    /** 无实现时的 no-op 出口。 */
    AgentPerfSink NO_OP = sample -> { };

    /** 记录一条样本；实现应做到线程安全、非阻塞、异常不外溢。 */
    void record(AgentPerfSample sample);
}
