package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotBounds;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotEnvelope;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * ARIA 快照的有界解析（设计 §4.2 SnapshotTree / P0 第 2 项）。
 *
 * <p>把 {@code page.ariaSnapshot()} 的原始文本解析为 Role-First 元素状态，并强制应用
 * {@link SnapshotBounds} 的<b>深度 / 节点数 / 字节数 / 采集时限</b>四道上界。任一触顶即停止收录，
 * 产出 {@link SnapshotEnvelope.Status#TRUNCATED} 且写明原因；<b>绝不把部分观测误报为完整快照</b>。
 * 超出观测能力或采集异常由调用方以 {@link SnapshotEnvelope.Status#UNSUPPORTED} 标注。</p>
 *
 * <p>纯逻辑、无浏览器依赖：输入是字符串，输出是带协议信封的 {@link Snapshot}，可浏览器无关单测。
 * ref 绑定到 {@code contextId/pageId/frameId/snapshotVersion}，动作后须重新快照取新版本。</p>
 */
public final class AriaSnapshotParser {

    private AriaSnapshotParser() {
    }

    /** 解析结果：快照本体 + 是否截断 + 截断原因。 */
    public record Result(Snapshot snapshot, String truncationReason) {
        public boolean truncated() { return truncationReason != null; }
    }

    /**
     * 有界解析。
     *
     * @param raw        {@code ariaSnapshot()} 原文；{@code null} 视为空观测（COMPLETE，零节点）
     * @param pageRef    页面 URL（页面指纹）
     * @param contextId  隔离靶场的浏览器上下文标识
     * @param pageId     页面标识
     * @param frameId    帧标识（主帧 "main"）
     * @param version    快照序号（同一通道内单调递增）
     * @param bounds     资源上界
     */
    public static Result parse(String raw, String pageRef, String contextId, String pageId,
                               String frameId, long version, SnapshotBounds bounds) {
        return parse(raw, pageRef, contextId, pageId, frameId, version, bounds, System::nanoTime);
    }

    /**
     * 包内重载：可注入纳秒时钟，使「采集时限」上界在单测里<b>确定性</b>触发
     * （不依赖机器速度 / 输入规模）。生产路径固定传 {@link System#nanoTime()}。
     */
    static Result parse(String raw, String pageRef, String contextId, String pageId,
                        String frameId, long version, SnapshotBounds bounds, LongSupplier nanoClock) {
        SnapshotBounds effective = bounds == null ? SnapshotBounds.DEFAULT : bounds;
        long deadline = nanoClock.getAsLong() + effective.timeoutMillis() * 1_000_000L;

        List<Snapshot.ElementState> states = new ArrayList<>();
        Map<String, Integer> counters = new HashMap<>();
        int nodes = 0;
        int bytes = 0;
        String reason = null;

        if (raw != null) {
            for (String line : raw.split("\n", -1)) {
                if (nanoClock.getAsLong() > deadline) {
                    reason = "TIMEOUT(" + effective.timeoutMillis() + "ms)";
                    break;
                }
                int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
                if (bytes + lineBytes > effective.maxBytes()) {
                    reason = "MAX_BYTES(" + effective.maxBytes() + ")";
                    break;
                }
                bytes += lineBytes;

                int indent = leadingSpaces(line);
                int depth = indent / 2 + 1;
                String t = line.trim();
                if (!t.startsWith("- ") || t.startsWith("- /")) {
                    continue; // 属性行 / 续行，不产生节点
                }
                if (depth > effective.maxDepth()) {
                    reason = "MAX_DEPTH(" + effective.maxDepth() + ")";
                    break; // 深度上界：停止收录，避免把深层子树误当完整
                }

                Snapshot.ElementState state = toState(t, counters, depth, frameId);
                if (state == null) {
                    continue; // 纯文本节点等非元素行
                }
                if (nodes >= effective.maxNodes()) {
                    reason = "MAX_NODES(" + effective.maxNodes() + ")";
                    break;
                }
                states.add(state);
                nodes++;
            }
        }

        // 平铺列表按缩进深度还原为有界树（per-node 携带 depth 与 frameId），供结构指纹与联动分析。
        List<Snapshot.ElementState> roots = nest(states);

        SnapshotEnvelope envelope = reason == null
                ? SnapshotEnvelope.complete(contextId, pageId, frameId, version, nodes, bytes)
                : SnapshotEnvelope.truncated(contextId, pageId, frameId, version, reason, nodes, bytes);
        return new Result(new Snapshot(pageRef, roots, envelope), reason);
    }

    /**
     * 把先序平铺的节点列表还原为树。使用深度栈：每个节点的父节点是栈中最近的深度更小的节点，
     * 即使源文本存在「跨级缩进」（中间层是非元素行）也能正确归位。
     */
    private static List<Snapshot.ElementState> nest(List<Snapshot.ElementState> flat) {
        java.util.Deque<TreeNode> path = new java.util.ArrayDeque<>();
        List<TreeNode> roots = new java.util.ArrayList<>();
        for (Snapshot.ElementState s : flat) {
            TreeNode node = new TreeNode(s);
            while (!path.isEmpty() && path.peek().depth >= node.depth) {
                path.pop();
            }
            if (path.isEmpty()) {
                roots.add(node);
            } else {
                path.peek().children.add(node);
            }
            path.push(node);
        }
        return roots.stream().map(AriaSnapshotParser::toElement).toList();
    }

    private static Snapshot.ElementState toElement(TreeNode n) {
        List<Snapshot.ElementState> kids = n.children.stream().map(AriaSnapshotParser::toElement).toList();
        Snapshot.ElementState s = n.state;
        return new Snapshot.ElementState(s.ref(), s.role(), s.name(), s.visible(), s.enabled(), s.text(),
                s.depth(), s.frameId(), kids, s.unsupported(), s.unsupportedReason());
    }

    /** 树构建中间节点（持有 ElementState 与可变子列表）。 */
    private static final class TreeNode {
        final Snapshot.ElementState state;
        final int depth;
        final List<TreeNode> children = new java.util.ArrayList<>();

        TreeNode(Snapshot.ElementState state) {
            this.state = state;
            this.depth = state.depth();
        }
    }


    private static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** 单行 ARIA 节点 → 元素状态；非元素行（text / 属性）返回 null。 */
    private static Snapshot.ElementState toState(String trimmed, Map<String, Integer> counters,
                                                int depth, String frameId) {
        String body = trimmed.substring(2).trim();
        if (body.startsWith("/")) {
            return null;
        }
        int end = body.length();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ' ' || c == '"' || c == '[' || c == ':') {
                end = i;
                break;
            }
        }
        String role = body.substring(0, end);
        if ("text".equals(role)) {
            return null;
        }
        String name = null;
        int q1 = body.indexOf('"');
        if (q1 >= 0) {
            int q2 = body.indexOf('"', q1 + 1);
            if (q2 > q1) {
                name = body.substring(q1 + 1, q2);
            }
        }
        boolean disabled = body.contains("[disabled]");
        String key = role + "|" + (name == null ? "" : name);
        int nth = counters.getOrDefault(key, 0);
        counters.put(key, nth + 1);
        LocatorSpec spec = LocatorSpec.role(role, name, nth);
        String safeName = name == null ? "" : name;
        return new Snapshot.ElementState(spec.canonical(), role, safeName, true, !disabled, safeName,
                depth, frameId, java.util.List.of(), false, null);
    }
}
