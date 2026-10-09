package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * 采集到的响应体<b>载体抽象</b>（公共类型）—— 使 {@code CapturedApiCall} 能承载
 * "内存原字节 / 内存 gzip / 临时文件"三种存储形态，而调用方无感
 * （{@code responseBody()} / {@code responseBodyBytes()} 的名称与语义均不变）。
 *
 * <p><b>为什么需要它</b>：{@code CapturedApiCall} 是公共 record，其组件即存储本身；若组件仍是
 * {@code String}/{@code byte[]}，则阈值（{@code route.capture.inline.max.bytes} /
 * {@code route.capture.gzip.max.bytes}）无论如何配置都无法改变堆占用 —— 存储形态由本抽象承载后，
 * 阈值才真正生效（{@code BodyStore} 为其实现）。
 *
 * <p><b>所有权与生命周期（契约）</b>：实例由采集器 {@code CaptureSink} 拥有，在 {@code reset()}
 * 或该记录被 history 驱逐时自动 {@link #close()}；因此 <b>{@code reset()} 之后不得再读取</b>
 * （文件型将抛异常）。消费者须在该记录仍被 sink 持有期间读取 —— 现有用法均满足：
 * 非消费式 {@code peek} 即时读取、消费式 {@code dump} 在同一方法内即时读取。
 *
 * <p><b>实现约定</b>：{@link #readAll()} / {@link #open()} 返回的数据等价于原始响应体字节；
 * {@link #text(Charset)} 只解码一次并缓存；{@link #close()} 幂等。
 *
 * @apiNote framework-internal：仅 route 采集域使用，业务代码不应直接依赖。
 */
public interface CapturedBody {

    /** 原始响应体字节长度。 */
    long length();

    /** 原始响应体内容类型；未知时为 {@code null}。 */
    String contentType();

    /** 是否文本型内容（决定是否允许走内存 gzip 档）。 */
    boolean isTextual();

    /** 原始响应体字节的完整副本。 */
    byte[] readAll();

    /** 原始响应体字节流（调用方负责关闭）。 */
    InputStream open();

    /**
     * 按指定字符集解码为文本（只解码一次并缓存）。
     *
     * @param charset 调用方给定的字符集（由内容类型解析而来，本抽象不做解析，避免编码口径漂移）
     * @return 解码后的文本
     */
    String text(Charset charset);

    /** 释放底层资源（文件型删除临时文件）；幂等。 */
    void close();
}
