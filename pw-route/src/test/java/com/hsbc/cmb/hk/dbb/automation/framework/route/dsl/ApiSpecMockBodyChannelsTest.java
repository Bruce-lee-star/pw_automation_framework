package com.hsbc.cmb.hk.dbb.automation.framework.route.dsl;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/**
 * `ApiSpec` 两个 Mock 体通道的契约：<b>字符串体与字节体互斥</b>（build 期 fail-fast），
 * 且字节通道<b>两端</b>都做防御性拷贝（不可变规则不得被外部数组反向影响）。
 */
public class ApiSpecMockBodyChannelsTest {

    @Test
    public void stringBodyAndByteBodyAreMutuallyExclusive() {
        ApiSpec.Builder b = ApiSpec.builder("/api/x", RouteCapability.MOCK);
        b.mockBody("{\"a\":1}").mockBodyBytes(new byte[] {1, 2, 3});
        assertThrows("同时声明两个体通道必须在 build 期 fail-fast，避免回放歧义",
                IllegalArgumentException.class, b::build);
    }

    @Test
    public void byteBodyIsAcceptedAloneAndCopiedOnTheWayInAndOut() {
        byte[] source = new byte[] {10, 20, 30};
        ApiSpec spec = ApiSpec.builder("/api/y", RouteCapability.MOCK)
                .mockBodyBytes(source)
                .build();

        source[0] = 99;   // 改动调用方数组不得影响已构建的不可变规则
        assertArrayEquals("入口必须拷贝", new byte[] {10, 20, 30}, spec.mockBodyBytes());

        byte[] out = spec.mockBodyBytes();
        out[1] = 88;      // 改动取出的数组不得影响规则内部
        assertArrayEquals("出口必须拷贝", new byte[] {10, 20, 30}, spec.mockBodyBytes());
        assertNull("未设置字节体时访问器为 null", ApiSpec.builder("/api/z", RouteCapability.MOCK)
                .mockBody("{}").build().mockBodyBytes());
    }

    @Test
    public void interceptConflictsWithTheByteBodyToo() {
        ApiSpec.Builder b = ApiSpec.builder("/api/w", RouteCapability.MOCK);
        b.mockIntercept(true).mockBodyBytes(new byte[] {1});
        assertThrows("intercept 与静态体（含字节体）互斥",
                IllegalArgumentException.class, b::build);
    }
}
