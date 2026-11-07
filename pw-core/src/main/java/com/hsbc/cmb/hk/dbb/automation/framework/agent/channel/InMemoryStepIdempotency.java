package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内步骤幂等记忆（T7 默认实现）：以 {@code (planDigest, 步索引)} 为键缓存每步的通过记录。
 *
 * <p>线程安全（{@link ConcurrentHashMap}），同一进程内「首跑记录、重跑跳过」闭环生效；
 * 跨进程持久化不在本任务范围（验收为同进程重跑）。</p>
 */
public final class InMemoryStepIdempotency implements StepIdempotency {

    private final ConcurrentHashMap<String, PassRecord> store = new ConcurrentHashMap<>();

    private static final class PassRecord {
        final String commandDigest;
        final String postSnapshotDigest;
        final CommandResult cached;
        PassRecord(String commandDigest, String postSnapshotDigest, CommandResult cached) {
            this.commandDigest = commandDigest;
            this.postSnapshotDigest = postSnapshotDigest;
            this.cached = cached;
        }
    }

    // 键以「步位置」锚定：同一会话内，位置相同且命令内容未变的步被跳过；命令变化（变更步）则重跑。
    // 不使用 planDigest 作键——否则「改动某步」导致整计划指纹变化，反而使全部步重跑，违背验收。
    private static String key(int index) {
        return "step-" + index;
    }

    /** 命令内容指纹：action + target + 排序参数 + resolvedScope，任一变化都使指纹变化（即「变更步」）。 */
    private static String commandDigest(Command c, ParamScope s) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.action()).append('\n').append(c.targetRef()).append('\n');
        c.params().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append('\n'));
        String sc = (s == null) ? "" : s.canonical();
        if (!sc.isEmpty()) {
            sb.append("scope:").append(sc).append('\n');
        }
        return sha256Hex(sb.toString());
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256，无法计算命令指纹", e);
        }
    }

    @Override
    public CommandResult cachedPass(StepPlan plan, int index, Command command, ParamScope resolvedScope) {
        PassRecord rec = store.get(key(index));
        if (rec == null) {
            return null;
        }
        if (!rec.commandDigest.equals(commandDigest(command, resolvedScope))) {
            return null; // 命令内容变化 → 视为变更步，必须重跑
        }
        return rec.cached;
    }

    @Override
    public void recordPass(StepPlan plan, int index, Command command, ParamScope resolvedScope, String postSnapshotDigest) {
        CommandResult cached = new CommandResult(true, null,
                "idempotent pass (plan " + plan.planDigest() + " step " + index + ")", postSnapshotDigest);
        store.put(key(index),
                new PassRecord(commandDigest(command, resolvedScope), postSnapshotDigest, cached));
    }

    /** 清空记忆（测试 / 调试用）。 */
    public void clear() {
        store.clear();
    }
}
