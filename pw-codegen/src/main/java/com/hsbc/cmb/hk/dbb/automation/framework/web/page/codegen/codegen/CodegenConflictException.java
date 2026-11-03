package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 生成物落盘冲突：目标文件存在且<b>不含生成标记</b>，视为手写代码，拒绝覆盖。
 *
 * <p>这是 {@link CodegenMarkers#GENERATED_MARKER} 的强制点——标记写进产物首行不是为了好看，
 * 而是让"再生成"能区分「自己上次的产物（可覆盖）」与「人改过的代码（不可覆盖）」。
 *
 * <p>继承 {@link IOException}：落盘类 API 本就声明 {@code throws IOException}，调用方无须新增异常类型；
 * 需要区分时按类型捕获即可。
 */
public class CodegenConflictException extends IOException {

    private final transient Path file;

    public CodegenConflictException(Path file, String message) {
        super(message);
        this.file = file;
    }

    /** 冲突的目标文件。 */
    public Path file() {
        return file;
    }
}
