package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

/**
 * 命令执行结果（含执行后快照，供联动断言）。
 *
 * <p>{@code snapshotDigest} 为<b>可选覆盖字段</b>：通常由 {@link Snapshot#snapshotDigest()} 派生，
 * 但 T7 幂等跳过的缓存结果会直接携带「该步此前通过时的后态指纹」，此时以覆盖值优先
 * （快照为 {@code null} 也能给出确定指纹）。</p>
 */
public final class CommandResult {
    private final boolean success;
    private final Snapshot snapshot;
    private final String message;
    private final String snapshotDigest; // 幂等跳过的缓存指纹；非 null 时优先于快照派生

    /** 兼容构造（无缓存指纹，由快照派生）。 */
    public CommandResult(boolean success, Snapshot snapshot, String message) {
        this(success, snapshot, message, null);
    }

    /** 完整构造（可显式携带缓存指纹，用于幂等跳过）。 */
    public CommandResult(boolean success, Snapshot snapshot, String message, String snapshotDigest) {
        this.success = success;
        this.snapshot = snapshot;
        this.message = message;
        this.snapshotDigest = snapshotDigest;
    }

    public boolean success() { return success; }
    public Snapshot snapshot() { return snapshot; }
    public String message() { return message; }

    /**
     * 执行后快照的稳定结构指纹（见 {@link Snapshot#snapshotDigest}）。
     * 覆盖字段非 null 时优先返回（幂等跳过的缓存结果），否则由快照派生；
     * 快照为 {@code null} 且未设覆盖值时返回 {@code null}。
     */
    public String snapshotDigest() {
        if (snapshotDigest != null) {
            return snapshotDigest;
        }
        return snapshot == null ? null : snapshot.snapshotDigest();
    }

    public static CommandResult ok(Snapshot snapshot) {
        return new CommandResult(true, snapshot, null);
    }
    public static CommandResult fail(String message) {
        return new CommandResult(false, null, message);
    }
}
