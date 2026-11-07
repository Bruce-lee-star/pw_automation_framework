package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.recorder;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorRoleVocabulary;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.IntentActionSchema;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.IntentTranslationException;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedSkeleton;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedStep;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFieldPlanner;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 录制产物 → {@link RecordedSkeleton} 转换器（设计 §4.3 / §9 P1「pw-codegen：录制产物 → 结构化骨架」）。
 *
 * <p><b>跨模块边界（本类是该边界的首个消费者）</b>：pw-codegen 只<b>产出</b>骨架（纯数据），
 * 翻译（{@code IntentTranslator}）、校验（{@code IntentPlanValidator}）、执行（{@code StepDriver}）
 * 全部留在各自模块。因此本类不 import 任何 pw-web-ui 执行期类型，也不触碰浏览器。</p>
 *
 * <p><b>输入是「录制会话 + 拾取条目」</b>：{@link CodegenRecorder} 只记录
 * {@code fieldKey = 拾取条目 sigKey} 的动作序列（click / fill / check），元素事实在
 * {@link CodegenEntry} 里（role / name / nlsKey / css）。两者缺一不可：前者给顺序与输入值，
 * 后者给定位符——这也是「复用 codegen 既有事实源」的落点（{@code CodegenEntry} 即
 * {@code CodegenFacts} 的规范化产物，本类不重算元素评分/唯一性）。</p>
 *
 * <h3>四条不可放宽的口径（均有单测锁定）</h3>
 * <ol>
 *   <li><b>动作词表不另起一张表</b>：动作原语取 {@link Command.ActionType} 枚举名，参数/授权
 *       统一过 {@link IntentActionSchema#validate}（与翻译侧同一张 schema，生成/校验不漂移）；
 *       click 落在 checkbox/radio 上时沿用 {@link CodegenFieldPlanner#verbFor} 的口径记为
 *       {@code CHECK}，与生成代码里的 {@code check()} 一致。</li>
 *   <li><b>fail-closed，不静默丢步</b>：录到未拾取的元素、未知动作类型、fill 空值、超上界，
 *       一律抛 {@link IntentTranslationException} 并带精确 {@code Reason}——静默丢弃会让
 *       「骨架看起来完整、实际少了关键步骤」，比直接失败更危险。</li>
 *   <li><b>数据最小化（R1/R7）</b>：录制到的输入值先过
 *       {@link SensitiveDataSanitizer#sanitizeFreeText}；骨架只承载动作/定位/参数，
 *       不含 DOM 全文、截图、Cookie、凭据原文。</li>
 *   <li><b>确定性</b>：按录制序号 {@code seq} 升序落盘（调用方传无序集合也得到同一结果），
 *       定位符走固定梯队（ROLE+nlsKey → ROLE+name → CSS），同一输入恒产出同一步骤序列，
 *       可直接与模型建议做 {@code PlanEquivalence} 对账。</li>
 * </ol>
 *
 * <p><b>刻意不表达的东西</b>：dialog / iframe 切换 / 新标签页在 pw-core 无对应原语
 * （{@code SWITCH_TO_POPUP} 属框架内部产生、禁止外部注入），故转换期<b>不生成</b>这类步骤；
 * 若录制侧出现，调用方须自行处理（本类只接受 click/fill/check 三种录制类型）。</p>
 *
 * <p>无状态、线程安全（全部 static，输入防御性拷贝）。</p>
 */
public final class RecordedSkeletonConverter {

    private RecordedSkeletonConverter() {
    }

    /**
     * 一次转换的输入（录制会话的最小化视图）。
     *
     * @param origin  录制来源标识（用于批准裁决，见 {@link #originFromUrl(String)} 归一化）
     * @param pageRef 录制页面地址（骨架的来源页面，可作 NAVIGATE 目标）
     * @param actions 录制动作序列（{@link CodegenRecorder#snapshot()} 的原样输出）
     * @param entries 拾取条目（key = {@link CodegenEntry#sigKey}，与动作的 fieldKey 同源）
     */
    public record Source(String origin, String pageRef,
                         List<CodegenRecorder.Action> actions,
                         Map<String, CodegenEntry> entries) {

        public Source {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(pageRef, "pageRef");
            Objects.requireNonNull(actions, "actions");
            Objects.requireNonNull(entries, "entries");
            actions = List.copyOf(actions);
            entries = Map.copyOf(entries);
        }
    }

    /**
     * 把录制会话转换为骨架（schemaVersion 固定为 {@link RecordedSkeleton#SUPPORTED_SCHEMA_VERSION}）。
     *
     * @param source 录制会话 + 拾取条目（不得为 {@code null}）
     * @return 结构化录制骨架（后续可交 {@code IntentTranslator} 生成 StepPlan 建议）
     * @throws IntentTranslationException 空录制 / 超上界 / 未拾取元素 / 未知动作 / fill 空值 /
     *                                    无法生成定位符
     */
    public static RecordedSkeleton convert(Source source) {
        Objects.requireNonNull(source, "source");
        List<CodegenRecorder.Action> ordered = new ArrayList<>(source.actions());
        if (ordered.isEmpty()) {
            throw new IntentTranslationException(IntentTranslationException.Reason.BOUNDS_VIOLATION,
                    "recording contains no actions");
        }
        if (ordered.size() > RecordedSkeleton.MAX_STEPS) {
            throw new IntentTranslationException(IntentTranslationException.Reason.BOUNDS_VIOLATION,
                    "recording has " + ordered.size() + " actions, exceeds bound "
                            + RecordedSkeleton.MAX_STEPS);
        }
        ordered.sort(Comparator.comparingLong(action -> action.seq));

        List<RecordedStep> steps = new ArrayList<>(ordered.size());
        for (CodegenRecorder.Action action : ordered) {
            steps.add(toStep(action, source.entries()));
        }
        return new RecordedSkeleton(RecordedSkeleton.SUPPORTED_SCHEMA_VERSION,
                source.origin(), source.pageRef(), steps);
    }

    /**
     * 从页面地址派生 origin（host 或 host:port，统一小写）。
     *
     * <p>骨架的 {@code origin} 必须是 {@code [a-z0-9][a-z0-9._:-]{0,127}}（见
     * {@link RecordedSkeleton#normalizeOrigin}），URL 原文含 {@code ://} 不能直接当 origin，
     * 故这里取 authority 的 host（带非默认端口）作为来源标识；无 host（{@code about:blank}、
     * 相对路径等）<b>显式拒绝</b>，不猜。</p>
     *
     * @param url 页面地址（如 {@code https://www.example.com:8443/path?q=1}）
     * @return 归一化 origin（如 {@code www.example.com:8443}）
     * @throws IntentTranslationException {@link IntentTranslationException.Reason#INVALID_INPUT}
     */
    public static String originFromUrl(String url) {
        Objects.requireNonNull(url, "url");
        String trimmed = url.trim();
        try {
            URI uri = new URI(trimmed);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IntentTranslationException(IntentTranslationException.Reason.INVALID_INPUT,
                        "cannot derive origin from url without host: " + trimmed);
            }
            int port = uri.getPort();
            return RecordedSkeleton.normalizeOrigin(port > 0 ? host + ":" + port : host);
        } catch (URISyntaxException e) {
            throw new IntentTranslationException(IntentTranslationException.Reason.INVALID_INPUT,
                    "invalid url for origin derivation: " + trimmed, e);
        }
    }

    // ------------------------------------------------------------------
    // 单步转换
    // ------------------------------------------------------------------

    private static RecordedStep toStep(CodegenRecorder.Action action, Map<String, CodegenEntry> entries) {
        CodegenEntry entry = entries.get(action.fieldKey);
        if (entry == null) {
            throw new IntentTranslationException(IntentTranslationException.Reason.TARGET_VIOLATION,
                    "recorded action references an element that was not picked: fieldKey=" + action.fieldKey);
        }
        Command.ActionType actionType = actionTypeOf(action, entry);
        String target = canonicalTargetOf(entry);
        Map<String, String> params = paramsOf(actionType, action);
        // 与翻译侧共用同一张 schema（生成/校验不漂移）
        IntentActionSchema.validate(actionType, target, params);
        return new RecordedStep(actionType.name(), target, params);
    }

    /**
     * 录制类型 → 动作原语。
     *
     * <p>{@code click} 落在 checkbox/radio 上按 {@link CodegenFieldPlanner#verbFor} 记为
     * {@code CHECK}（与生成代码 {@code check()} 同口径）；{@code fill} 恒为 {@code FILL}
     * （值必填，校验交给 schema）；其余录制类型一律拒绝，不猜测。</p>
     */
    private static Command.ActionType actionTypeOf(CodegenRecorder.Action action, CodegenEntry entry) {
        String recorded = action.type == null ? "" : action.type.trim().toLowerCase(Locale.ROOT);
        switch (recorded) {
            case "fill":
                return Command.ActionType.FILL;
            case "check":
                return Command.ActionType.CHECK;
            case "click":
                return "check".equals(CodegenFieldPlanner.verbFor(entry))
                        ? Command.ActionType.CHECK : Command.ActionType.CLICK;
            default:
                throw new IntentTranslationException(IntentTranslationException.Reason.UNKNOWN_ACTION,
                        "unsupported recorded action type: " + action.type);
        }
    }

    /**
     * 定位符梯队（与 §4.4 Role-First 一致）：ROLE + NLS key → ROLE + 可访问名 → CSS。
     *
     * <p>不把 {@link CodegenEntry#index} 塞进 {@code nth}：它是 codegen 的「List 分组序号」
     * （同级兄弟位置），与 Playwright {@code getByRole(...).nth()} 的过滤口径不同源，
     * 混用会定位漂移。</p>
     *
     * <p><b>映射契约（T11）</b>：canonical 的 {@code role=} 段写
     * {@link LocatorRoleVocabulary#canonicalRole(String)} 的规范常量名（小写），而不是 CDP AX
     * 原始词。否则 {@code image} / {@code menuitem-checkbox} 会在生成侧映射成
     * {@code AriaRole.IMG} / {@code MENUITEMCHECKBOX}，执行侧却拿着原始词解析失败——
     * 同一元素在两侧不一致。不可定位/不可映射的角色一律回退 CSS。</p>
     */
    private static String canonicalTargetOf(CodegenEntry entry) {
        String role = entry.role;
        String canonicalRole = LocatorRoleVocabulary.canonicalRole(role);
        if (canonicalRole != null && LocatorRoleVocabulary.isLocatable(role)) {
            String nameKey = trimToNull(entry.nlsKey);
            if (nameKey != null) {
                return bounded(entry, LocatorSpec.roleOfKey(canonicalRole, nameKey).canonical());
            }
            String name = trimToNull(entry.name);
            if (name != null) {
                return bounded(entry, LocatorSpec.role(canonicalRole, name).canonical());
            }
        }
        String css = trimToNull(entry.cssSelector);
        if (css == null) {
            css = trimToNull(entry.selector);
        }
        if (css != null) {
            return bounded(entry, LocatorSpec.css(css).canonical());
        }
        throw new IntentTranslationException(IntentTranslationException.Reason.TARGET_VIOLATION,
                "picked element has neither locatable role nor css: sigKey=" + entry.sigKey);
    }

    private static Map<String, String> paramsOf(Command.ActionType actionType, CodegenRecorder.Action action) {
        if (actionType != Command.ActionType.FILL) {
            return Map.of();
        }
        // R1/R7：录制值可能含凭据（密码框、token 输入框），出域前先脱敏
        String sanitized = SensitiveDataSanitizer.sanitizeFreeText(
                action.value == null ? "" : action.value).trim();
        if (sanitized.isEmpty()) {
            throw new IntentTranslationException(IntentTranslationException.Reason.PARAM_SCHEMA_VIOLATION,
                    "recorded fill has blank value after sanitization: fieldKey=" + action.fieldKey);
        }
        if (sanitized.length() > RecordedSkeleton.MAX_FIELD_LENGTH) {
            throw new IntentTranslationException(IntentTranslationException.Reason.INVALID_INPUT,
                    "recorded fill value exceeds bound " + RecordedSkeleton.MAX_FIELD_LENGTH
                            + ": fieldKey=" + action.fieldKey);
        }
        return Map.of("value", sanitized);
    }

    private static String bounded(CodegenEntry entry, String canonical) {
        if (canonical.length() > RecordedSkeleton.MAX_FIELD_LENGTH) {
            throw new IntentTranslationException(IntentTranslationException.Reason.INVALID_INPUT,
                    "locator canonical exceeds bound " + RecordedSkeleton.MAX_FIELD_LENGTH
                            + ": sigKey=" + entry.sigKey);
        }
        return canonical;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
