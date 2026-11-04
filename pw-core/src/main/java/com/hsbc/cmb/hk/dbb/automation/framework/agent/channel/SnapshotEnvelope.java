package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

/**
 * 快照协议信封（设计 §4.2 SnapshotEnvelope / P0 第 2 项）。
 *
 * <p>把 {@link Snapshot} 从「平铺元素列表」升级为<b>有版本、有资源边界、有明确状态</b>的一次观测：
 * ref 绑定到 {@code contextId/pageId/frameId/snapshotVersion}，页面或 Frame 变化后必须重新快照；
 * 采集只带白名单字段，触顶或无法观测时以 {@link Status} 明确标注，供验证侧 fail-closed 处理。</p>
 *
 * @param schemaVersion     协议版本（当前 1）
 * @param contextId         浏览器上下文标识（隔离靶场每次 acquire 独立）
 * @param pageId            页面标识
 * @param frameId           帧标识（主帧为 "main"）
 * @param snapshotVersion   同一通道内单调递增的快照序号
 * @param capturedAtEpoch   采集时间（epoch millis）
 * @param status            完整 / 截断 / 不支持
 * @param detail            截断或不支持原因（{@link Status#COMPLETE} 时为 null）
 * @param nodeCount         实际收录节点数
 * @param byteCount         实际收录字节数（UTF-8）
 */
public record SnapshotEnvelope(int schemaVersion, String contextId, String pageId, String frameId,
                               long snapshotVersion, long capturedAtEpoch, Status status, String detail,
                               int nodeCount, int byteCount) {

    public enum Status {
        /** 完整采集，未触任何上界。 */
        COMPLETE,
        /** 触顶（深度/节点/字节/时限）后的部分观测；不可当作完整快照做存在性结论。 */
        TRUNCATED,
        /** 采集失败或观测能力不支持（跨域 Frame / Shadow DOM / 采集异常）。 */
        UNSUPPORTED
    }

    public static final int CURRENT_SCHEMA = 1;

    public static SnapshotEnvelope complete(String contextId, String pageId, String frameId,
                                            long version, int nodeCount, int byteCount) {
        return new SnapshotEnvelope(CURRENT_SCHEMA, contextId, pageId, frameId, version,
                System.currentTimeMillis(), Status.COMPLETE, null, nodeCount, byteCount);
    }

    public static SnapshotEnvelope truncated(String contextId, String pageId, String frameId,
                                             long version, String reason, int nodeCount, int byteCount) {
        return new SnapshotEnvelope(CURRENT_SCHEMA, contextId, pageId, frameId, version,
                System.currentTimeMillis(), Status.TRUNCATED, reason, nodeCount, byteCount);
    }

    public static SnapshotEnvelope unsupported(String contextId, String pageId, String frameId,
                                               long version, String reason) {
        return new SnapshotEnvelope(CURRENT_SCHEMA, contextId, pageId, frameId, version,
                System.currentTimeMillis(), Status.UNSUPPORTED, reason, 0, 0);
    }

    /** 只有 COMPLETE 快照可用于「元素不存在」这类绝对态结论。 */
    public boolean isComplete() {
        return status == Status.COMPLETE;
    }
}
