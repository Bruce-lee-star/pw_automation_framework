package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

/**
 * 快照资源上限（设计 §4.2「视口与边界」/ P0 第 2 项）。
 *
 * <p>采集方在生成 {@link Snapshot} 前必须应用这四道上界；任一触顶即返回
 * {@link SnapshotEnvelope.Status#TRUNCATED} 并写明原因，<b>不得把截断结果误报为完整快照</b>。
 * 全页/子树须显式提高上限，不允许实现侧静默放宽。</p>
 *
 * @param maxDepth       ARIA 树最大深度（2 空格缩进为一级）
 * @param maxNodes       最大节点数
 * @param maxBytes       最大字节数（UTF-8）
 * @param timeoutMillis  采集时限（毫秒）
 */
public record SnapshotBounds(int maxDepth, int maxNodes, int maxBytes, long timeoutMillis) {

    /** 默认上界：深度 32 / 节点 2000 / 256 KiB / 2s，覆盖常规业务页面且对失控页面 fail-closed。 */
    public static final SnapshotBounds DEFAULT = new SnapshotBounds(32, 2000, 256 * 1024, 2_000L);

    public SnapshotBounds {
        if (maxDepth <= 0 || maxNodes <= 0 || maxBytes <= 0 || timeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "SnapshotBounds must all be positive: " + maxDepth + "/" + maxNodes + "/"
                            + maxBytes + "/" + timeoutMillis);
        }
    }
}
