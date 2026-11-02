package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.BehaviorEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventoryEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraph;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 确定性资产校验器（无 LLM，参考实现）。
 *
 * <p>校验维度（§4.4 梯队 + §4.6 ④ + §4.7 多语言）：
 * <ul>
 *   <li>每个元素必须有<b>角色优先</b>定位候选（role-first），否则 ERROR；</li>
 *   <li>元素主定位符必须<b>唯一</b>，重复即 ERROR（歧义定位）；</li>
 *   <li>联动图谱的触发器/目标必须存在于元素清单（refId），否则 ERROR；</li>
 *   <li>需求未映射（incomplete）仅 WARNING（允许 incomplete 资产，不阻断落库）；</li>
 *   <li><b>多语言</b>：配置了 NLS 目录时，用<b>字面文案</b>定位的 role 候选若其文案能反查到 key，
 *       判 WARNING —— 该资产切语言即失效，应改用 {@code roleOfKey}（§4.7）。</li>
 * </ul>
 * 任意 ERROR → {@code passed=false}，流水线失败关闭转人工，不自动回退（§4.4 铁律 / R6）。</p>
 */
public final class DeterministicAssetValidator implements AssetValidator {

    private static final Logger LOG = LoggerFactory.getLogger(DeterministicAssetValidator.class);

    /** 多语言目录（可选）：仅用于给出"该用 key 却用了字面文案"的告警。 */
    private final NlsCatalog nls;

    public DeterministicAssetValidator() {
        this(null);
    }

    public DeterministicAssetValidator(NlsCatalog nls) {
        this.nls = nls;
    }

    @Override
    public ValidationReport validate(AssetDraftBundle bundle) {
        List<ValidationReport.Issue> issues = new ArrayList<>();

        // 1) 定位候选完整性 + 唯一性
        Set<String> seenLocators = new HashSet<>();
        for (ElementInventoryEntry e : bundle.inventory().entries()) {
            LocatorSpec primary = e.ref().primaryLocator();
            if (primary == null) {
                issues.add(ValidationReport.Issue.error(
                        "Element " + e.ref().refId() + " has no role-first locator candidate"));
                continue;
            }
            String canonical = primary.canonical();
            if (!seenLocators.add(canonical)) {
                issues.add(ValidationReport.Issue.error(
                        "Duplicate role-first locator " + canonical + " at element " + e.ref().refId()));
            }
            // 多语言：能反查到 key 却仍用字面文案定位 → 切语言即失效（不阻断，但必须提示整改）
            if (nls != null && primary.strategy() == LocatorSpec.Strategy.ROLE
                    && primary.nameKey() == null && primary.name() != null) {
                String key = nls.keyOf(primary.name());
                if (key != null) {
                    issues.add(ValidationReport.Issue.warning(
                            "Element " + e.ref().refId() + " locates by localized text \"" + primary.name()
                                    + "\" (NLS key '" + key + "') — use roleOfKey(role, \"" + key
                                    + "\") so the asset survives language switch"));
                }
            }
            if (e.requirementId() == null || e.requirementId().isBlank()) {
                issues.add(ValidationReport.Issue.warning(
                        "Element " + e.ref().refId() + " has no requirement mapping (incomplete)"));
            }
        }

        // 2) 行为模型：操作类型非空（已隐含），仅做完整性提示
        for (BehaviorEntry b : bundle.behaviors().entries()) {
            if (b.action() == null) {
                issues.add(ValidationReport.Issue.error("Behavior of " + b.ref().refId() + " has null action"));
            }
        }

        // 3) 联动图谱：触发器/目标必须存在于元素清单
        Set<String> refIds = new HashSet<>();
        for (ElementInventoryEntry e : bundle.inventory().entries()) {
            refIds.add(e.ref().refId());
        }
        LinkageGraph g = bundle.linkage();
        for (LinkageGraph.LinkageRule rule : g.rules()) {
            if (!refIds.contains(rule.trigger().refId())) {
                issues.add(ValidationReport.Issue.error(
                        "Linkage trigger " + rule.trigger().refId() + " not in inventory"));
            }
            for (LinkageGraph.ExpectedChange change : rule.expectations()) {
                if (!refIds.contains(change.target().refId())) {
                    issues.add(ValidationReport.Issue.error(
                            "Linkage target " + change.target().refId() + " not in inventory"));
                }
            }
        }

        boolean hasError = issues.stream().anyMatch(i -> i.severity() == ValidationReport.Issue.Severity.ERROR);
        boolean passed = !hasError;
        String summary = String.format("elements=%d behaviors=%d linkageRules=%d errors=%d warnings=%d",
                bundle.inventory().entries().size(), bundle.behaviors().entries().size(),
                g.rules().size(),
                (int) issues.stream().filter(i -> i.severity() == ValidationReport.Issue.Severity.ERROR).count(),
                (int) issues.stream().filter(i -> i.severity() == ValidationReport.Issue.Severity.WARNING).count());
        LOG.info("DeterministicAssetValidator passed={} {}", passed, summary);
        // 通过时仍保留 warnings（需求未映射等提示），不丢弃 issues（避免 summary 与 issues 不一致）
        return new ValidationReport(passed, List.copyOf(issues), summary);
    }
}
