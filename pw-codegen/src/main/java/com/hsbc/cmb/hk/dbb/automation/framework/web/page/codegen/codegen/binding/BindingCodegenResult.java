package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding;

import java.nio.file.Path;

/**
 * 一次「需求 → 代码绑定」流水线的落盘结果。
 *
 * @param featureFile 生成的 {@code .feature} 绝对/相对路径（取决于调用方传入的目录）
 * @param glueFile    生成的 Glue {@code .java} 路径
 */
public record BindingCodegenResult(Path featureFile, Path glueFile) {
}
