package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleEntry;
import java.util.List;

/**
 * 一次 step 记录：含所属页面类与本次拾取的元素列表（多页面归类用）。
 *
 * @apiNote 框架内部共享数据契约（picker 与 generator 跨包共用）。业务代码不应依赖。
 */
public final class StepRec {
    public final String pageClass;
    public final List<RoleEntry> picks;

    /**
     * 【本轮的号表】元素身份键 → 该轮内的操作序号（可为 null）。
     *
     * <p>必需：元素对象是跨轮共享的同一批实例，其 {@code pickNos} 在每轮停止时会被清空（好让下一轮
     * 从 1 重新编号）。已封存的 step 若不带自己那一轮的号表，生成它时就无号可展开 ⇒ 历史 step 的操作
     * 整段丢失。号随 step 走，各轮互不干扰。
     */
    public java.util.Map<String, java.util.List<Integer>> roundNos;

    public StepRec(String pageClass, List<RoleEntry> picks) {
        this(pageClass, picks, null);
    }

    public StepRec(String pageClass, List<RoleEntry> picks,
                   java.util.Map<String, java.util.List<Integer>> roundNos) {
        this.pageClass = pageClass;
        this.picks = picks;
        this.roundNos = roundNos;
    }
}
