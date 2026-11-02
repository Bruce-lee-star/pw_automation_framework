package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

/** 命令执行结果（含执行后快照，供联动断言）。 */
public final class CommandResult {
    private final boolean success;
    private final Snapshot snapshot;
    private final String message;

    public CommandResult(boolean success, Snapshot snapshot, String message) {
        this.success = success;
        this.snapshot = snapshot;
        this.message = message;
    }

    public boolean success() { return success; }
    public Snapshot snapshot() { return snapshot; }
    public String message() { return message; }

    public static CommandResult ok(Snapshot snapshot) {
        return new CommandResult(true, snapshot, null);
    }
    public static CommandResult fail(String message) {
        return new CommandResult(false, null, message);
    }
}
