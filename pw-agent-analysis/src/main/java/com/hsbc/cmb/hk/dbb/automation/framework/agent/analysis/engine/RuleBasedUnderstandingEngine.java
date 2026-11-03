package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.ObservedElement;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraph;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.Provenance;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性规则理解引擎（无 LLM，韧性降级路径 / 参考实现）。
 *
 * <p>不调用任何模型，仅依据元素角色与需求关键词做<b>可解释</b>的推断：
 * <ul>
 *   <li>元素清单：每个观测元素 → {@link ElementInventoryEntry}，并按名称/角色关键词映射需求（无命中标记 incomplete）。</li>
 *   <li>行为模型：按角色推断操作类型与预期结果类别（checkbox→toggle、combobox→reveal、button→submit…）。</li>
 *   <li>联动图谱：单帧观测无法观测状态变化，故规则引擎产出<b>空规则</b>联动图谱；
 *       真正可复现的联动须由 LLM 对比操作前/后差分，或由
 *       {@link com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetVerifier} 在靶场验证。</li>
 * </ul>
 *
 * <p><b>多语言（§4.7）</b>：当注入了 {@link NlsCatalog} 时，本引擎会把观测到的可访问名反查成 NLS key，
 * 并让 key 成为<b>资产主键与首选定位符</b>（{@link LocatorSpec#roleOfKey}）——这正是 codegen 的约定
 * （{@code role=button[name=Nls.t("login.submit")]}）。于是同一份资产在 en/zh/… 下都成立，
 * 而不是"每换一种语言就重产一次资产"。</p>
 *
 * <p>全部产出为<b>未验证建议</b>（draft），须经 {@code AssetValidator} + 人工审批（R6）。</p>
 */
public final class RuleBasedUnderstandingEngine implements PageUnderstandingEngine {

    private static final Logger LOG = LoggerFactory.getLogger(RuleBasedUnderstandingEngine.class);

    private final NlsCatalog nls;

    public RuleBasedUnderstandingEngine() {
        this(null);
    }

    /**
     * @param nls 多语言目录；{@code null} 表示单语言环境（资产只能用字面文案定位）
     */
    public RuleBasedUnderstandingEngine(NlsCatalog nls) {
        this.nls = nls;
    }

    @Override
    public AssetDraftBundle analyze(RequirementContext requirement, PageObservation observation) {
        List<ElementInventoryEntry> inventory = new ArrayList<>();
        List<BehaviorEntry> behaviors = new ArrayList<>();
        int keyed = 0;

        for (ObservedElement e : observation.elements()) {
            // 1) 拿到跨语言稳定的 key：元素自带（data-i18n 等）优先，其次按当前语言反查
            String nameKey = nameKeyOf(e);
            if (nameKey != null) {
                keyed++;
            }
            // 2) 定位梯队：有 key 时「角色 + key」为首选，字面文案候选保留为回退
            List<LocatorSpec> candidates = candidatesOf(e, nameKey);
            ElementRef ref = new ElementRef(stableRefId(e, nameKey), candidates, e.role());

            RequirementEntry matched = requirement.matchByKeyword(keywordsOf(e, nameKey));
            String reqId = matched == null ? "" : matched.id().flat();

            inventory.add(new ElementInventoryEntry(ref, purposeOf(e), reqId));
            behaviors.add(new BehaviorEntry(ref, actionOf(e), resultCategoryOf(e), null, null, reqId));
        }

        String linkageReq = requirement.entries().isEmpty() ? "" : requirement.entries().get(0).id().flat();
        LinkageGraph linkage = new LinkageGraph(linkageReq, List.of());

        LOG.info("RuleBasedUnderstandingEngine produced inventory={} behaviors={} nlsKeyed={} linkageRules=0",
                inventory.size(), behaviors.size(), keyed);
        return new AssetDraftBundle(requirement, new ElementInventory(inventory),
                new BehaviorModel(behaviors), linkage,
                Provenance.manual(observation.pageRef()), observation.pageRef());
    }

    /** 元素自带 key 优先；否则用 NLS 目录把当前语言的可访问名反查成 key（无目录则 null）。 */
    private String nameKeyOf(ObservedElement e) {
        if (e.hasNlsKey()) {
            return e.nameKey();
        }
        return nls == null ? null : nls.keyOf(e.name());
    }

    /**
     * 定位候选梯队：有 NLS key 时把「角色 + key」插到最前（跨语言首选），
     * 原先的字面文案候选顺延为回退（单语言下仍可用）。
     */
    private static List<LocatorSpec> candidatesOf(ObservedElement e, String nameKey) {
        List<LocatorSpec> out = new ArrayList<>();
        if (nameKey != null && e.role() != null) {
            out.add(LocatorSpec.roleOfKey(e.role(), nameKey, nthOf(e)));
        }
        for (LocatorSpec s : e.locatorCandidates()) {
            if (nameKey != null && s.strategy() == LocatorSpec.Strategy.ROLE && s.usesNlsKey()) {
                continue;
            }
            out.add(s);
        }
        return out;
    }

    /** 同组序号沿用观测到的角色候选（多个同名元素时用于 nth 消歧）。 */
    private static int nthOf(ObservedElement e) {
        LocatorSpec primary = e.primaryLocator();
        return primary != null && primary.strategy() == LocatorSpec.Strategy.ROLE ? primary.nth() : 0;
    }

    /** 资产主键：有 key 用 key（跨语言稳定），否则 role:字面名。 */
    private static String stableRefId(ObservedElement e, String nameKey) {
        String role = e.role() == null ? "elem" : e.role();
        if (nameKey != null) {
            return role + ":" + nameKey;
        }
        String base = (e.name() != null && !e.name().isBlank()) ? e.name() : role;
        return role + ":" + base;
    }

    private static String purposeOf(ObservedElement e) {
        return (e.name() != null && !e.name().isBlank()) ? e.name() : (e.role() == null ? "element" : e.role());
    }

    private static String[] keywordsOf(ObservedElement e, String nameKey) {
        List<String> ks = new ArrayList<>();
        // 按词元（≥3 字符）切分名称，提升需求映射召回；避免整段名无法子串命中
        addTokens(ks, e.name());
        // key 本身也是语义来源（如 login.username → login / username），且跨语言不变
        addTokens(ks, nameKey);
        if (e.role() != null) {
            ks.add(e.role().toLowerCase());
        }
        return ks.toArray(new String[0]);
    }

    private static void addTokens(List<String> out, String value) {
        if (value == null) {
            return;
        }
        for (String w : value.toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !out.contains(w)) {
                out.add(w);
            }
        }
    }

    /** 按角色推断操作类型。 */
    private static Command.ActionType actionOf(ObservedElement e) {
        String r = e.role() == null ? "" : e.role().toLowerCase();
        return switch (r) {
            case "checkbox", "radio", "switch" -> Command.ActionType.CLICK;
            case "combobox", "listbox", "spinbutton" -> Command.ActionType.SELECT;
            case "textbox", "searchbox", "text" -> Command.ActionType.FILL;
            case "button", "link" -> Command.ActionType.CLICK;
            default -> Command.ActionType.CLICK;
        };
    }

    /** 按角色推断预期结果类别（未验证建议）。 */
    private static String resultCategoryOf(ObservedElement e) {
        String r = e.role() == null ? "" : e.role().toLowerCase();
        return switch (r) {
            case "checkbox", "radio", "switch" -> "toggle";
            case "combobox", "listbox" -> "reveal/refresh";
            case "button" -> "submit/navigation";
            case "link" -> "navigation";
            case "textbox", "searchbox" -> "input";
            default -> "unknown";
        };
    }
}
