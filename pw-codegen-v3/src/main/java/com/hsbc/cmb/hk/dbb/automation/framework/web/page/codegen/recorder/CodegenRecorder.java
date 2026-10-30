package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.recorder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 操作序列录制器（独立于注入脚本，Java 侧保序）。
 *
 * <p>被测页 recorder 回传真实用户操作（click / fill / check），每项经 {@link #record} 写入一条带全局递增序号的
 * {@link Action}。<b>字段键（fieldKey = 拾取条目的 sigKey）</b>由调用方在匹配到已拾取元素后传入，
 * 未匹配到拾取元素（如点击了未拾取的页面区域）的操作被忽略（不污染产物）。
 *
 * <p>{@link #orderedFieldKeys()} 返回<b>首次出现顺序去重</b>的字段键序列，供 {@code CodegenLauncher}
 * 重排 entries，使生成的 Step / Assert 方法体按用户真实操作先后排列（而非按拾取顺序）。
 */
public final class CodegenRecorder {

    private final List<Action> actions = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    /** 一条录制动作（fieldKey + 动作类型 + 输入值 + 全局序号）。 */
    public static final class Action {
        public final String fieldKey;
        public final String type;
        public final String value;
        public final long seq;

        public Action(String fieldKey, String type, String value, long seq) {
            this.fieldKey = fieldKey;
            this.type = type;
            this.value = value;
            this.seq = seq;
        }
    }

    /** 记录一条动作；fieldKey 为空（未匹配到拾取元素）时忽略。 */
    public void record(String fieldKey, String type, String value) {
        if (fieldKey == null || fieldKey.isBlank()) return;
        actions.add(new Action(fieldKey, type, value, seq.incrementAndGet()));
    }

    public List<Action> snapshot() {
        return new ArrayList<>(actions);
    }

    /** 首次出现顺序去重后的字段键序列（驱动 entries 重排）。 */
    public List<String> orderedFieldKeys() {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Action a : actions) seen.add(a.fieldKey);
        return new ArrayList<>(seen);
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }
}
