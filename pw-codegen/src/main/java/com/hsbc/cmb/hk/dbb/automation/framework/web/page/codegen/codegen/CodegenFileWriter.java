package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 生成物落盘守卫：<b>只覆盖自己生成的产物</b>。
 *
 * <h2>为什么必须有这一层</h2>
 * <p>生成物一旦落进业务仓，业务同事会在上面补 {@code // TODO} 修正、增补断言、合并方法。若流水线用
 * {@code Files.write} 直接重跑，这些改动会被悄悄冲掉且没有任何提示。这里的规则只有一条：
 * <b>目标文件首行含 {@link CodegenMarkers#GENERATED_MARKER} 才允许覆盖，否则抛
 * {@link CodegenConflictException}</b>。
 *
 * <h2>批量落盘是「先全查、后全写」</h2>
 * <p>{@link #writeAllGenerated(Map)} 先校验全部目标再落笔——Feature 可覆盖、Glue 是手写时，
 * 不会出现"Feature 已被冲掉、Glue 报错退出"的半截状态。
 */
public final class CodegenFileWriter {

    private CodegenFileWriter() {}

    /** 目标文件存在且首行带生成标记（BOM 容忍）；不存在时返回 {@code false}。 */
    public static boolean isGenerated(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            if (first == null) {
                return false;
            }
            if (!first.isEmpty() && first.charAt(0) == '\uFEFF') {
                first = first.substring(1);
            }
            return first.contains(CodegenMarkers.GENERATED_MARKER);
        }
    }

    /**
     * 写单个生成物；目标已存在且无生成标记时抛 {@link CodegenConflictException}。
     *
     * @throws CodegenConflictException 目标是手写文件
     */
    public static void writeGenerated(Path file, String content) throws IOException {
        writeAllGenerated(Map.of(Objects.requireNonNull(file, "file"), Objects.requireNonNull(content, "content")));
    }

    /**
     * 批量写生成物：<b>先校验全部目标</b>，全部可覆盖才逐个落盘（父目录自动创建）。
     *
     * @param outputs 目标文件 → 内容（按插入顺序落盘）
     * @throws CodegenConflictException 任一目标是手写文件（此异常抛出时尚未写入任何文件）
     */
    public static void writeAllGenerated(Map<Path, String> outputs) throws IOException {
        Objects.requireNonNull(outputs, "outputs");
        Map<Path, String> ordered = new LinkedHashMap<>(outputs);
        List<Path> conflicts = new ArrayList<>();
        for (Path file : ordered.keySet()) {
            if (Files.exists(file) && !isGenerated(file)) {
                conflicts.add(file);
            }
        }
        if (!conflicts.isEmpty()) {
            Path first = conflicts.get(0);
            throw new CodegenConflictException(first, "拒绝覆盖非生成产物（首行缺少 \""
                    + CodegenMarkers.GENERATED_MARKER + "\"）：" + conflicts
                    + "；如确需重建，请先删除该文件或把改动合并回生成源（计划 JSON / 录制会话）");
        }
        for (Map.Entry<Path, String> e : ordered.entrySet()) {
            Path file = e.getKey();
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, e.getValue(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
    }
}
