package com.hsbc.cmb.hk.dbb.automation.framework.agent.nls;

import java.util.Set;

/**
 * NLS（多语言）目录契约（设计 §4.7）。
 *
 * <p><b>要解决的问题</b>：被测页面是多语言的，同一元素在不同语言下可访问名不同
 * （"I agree" / "我同意" / ...）。若资产把<b>文案</b>写死进定位符，切换语言即全部失效
 * —— 这与 codegen 早已确立的约定一致：<b>定位只认 key，文案在运行时由 NLS 解析</b>
 * （codegen 生成的是 {@code [name=Nls.t("login.submit")]}）。</p>
 *
 * <p>本契约提供双向能力，分别服务两条路径：</p>
 * <ul>
 *   <li><b>分析期（路径 B）</b>：{@link #keyOf(String)} 把观测到的可访问名<b>反查</b>成 key，
 *       使资产主键与定位符跨语言稳定；</li>
 *   <li><b>执行期（路径 A/C）</b>：{@link #visibleText(String)} 把 key <b>解析</b>为当前语言下
 *       可用于 {@code getByRole(role,{name})} 的可见文本（模板值走正则）。</li>
 * </ul>
 *
 * <p>实现不得引入浏览器依赖；LLM 适配实现也不在此契约内（R6）。</p>
 */
public interface NlsCatalog {

    /** 当前生效语言（如 {@code "en"} / {@code "zh"}），取自 nls json 第一层语言 Key。 */
    String language();

    /** 当前语言包中是否存在该 key。 */
    boolean hasKey(String key);

    /** 当前语言下 key 的<b>原始值</b>（未归一化，可能含 HTML）；缺失即抛 {@link IllegalStateException}。 */
    String value(String key);

    /** 当前语言下 key 的<b>可见文本</b>（已归一化，可直接作为 ARIA name 使用）。 */
    String visibleText(String key);

    /**
     * 反查：把页面上的可访问名/可见文本还原为 nls key。
     *
     * @param visibleName 页面观测到的名字（当前语言）
     * @return 命中的 key；未命中返回 {@code null}（调用方应回落为字面文案定位，并据此给出跨语言告警）
     */
    String keyOf(String visibleName);

    /** 当前语言包的全部 key（用于校验元素自带的 key，如 {@code data-i18n}）。 */
    Set<String> keys();
}
