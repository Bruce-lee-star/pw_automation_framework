# 模板库

> 每份模板都是**可直接复制**的骨架。使用方式：复制文件 → 放到目标目录 → 重命名为 kebab-case → 填写 `{{占位符}}` → 删除用不到的章节（保留「本文不涉及」并说明理由）。
>
> 填写完成后，模板文件本身不含占位符；`docs/templates/` 目录被门禁 R7 豁免，其余目录残留占位符即失败。

## 模板清单

| 模板 | 对应 `type` | 适用场景 | 篇幅目标 |
|---|---|---|---|
| [template-adr.md](template-adr.md) | `adr` | 记录一个技术决策及其取舍 | 1–3 页 |
| [template-design-doc.md](template-design-doc.md) | `architecture` | 跨模块技术方案、分层指南 | 3–10 页 |
| [template-module-readme.md](template-module-readme.md) | `module` | Maven 模块说明 | 2–5 页 |
| [template-tutorial.md](template-tutorial.md) | `usage` | 教学型上手教程 | 3–8 页 |
| [template-howto.md](template-howto.md) | `howto` | 任务型操作指南 | 2–6 页 |
| [template-reference.md](template-reference.md) | `reference` | 配置项 / API / CLI 参数查阅 | 按条目数 |
| [template-runbook.md](template-runbook.md) | `runbook` | 故障处置手册 | 2–5 页 |
| [template-alignment.md](template-alignment.md) | `alignment` | 框架与报告/用例层的契约规范 | 2–4 页 |

## 使用约定

### 占位符

统一使用 `{{大写占位符}}` 形式，替换后门禁会扫描残留。常见占位符：

| 占位符 | 含义 |
|---|---|
| `{{TITLE}}` | 文档标题（与 H1 一致） |
| `{{ID}}` | 文档 id，如 `DOC-ARCH-0007` |
| `{{TYPE}}` | 文档类型，见 S3 §2 |
| `{{OWNER}}` | 责任团队或责任人 |
| `{{DATE}}` | 日期 `YYYY-MM-DD` |
| `{{MODULE}}` | 适用模块，如 `pw-route` |
| `{{APPLIES_TO}}` | 适用范围 |

### 章节取舍

- 模板中标注 `<!-- 可选 -->` 的章节：不需要时**整节删除**（不要留空标题）。
- 标注 `<!-- 必填 -->` 的章节：即使不适用也保留，写「本文不涉及 XXX，原因：……」。
- **不要**为了让文档"完整"而保留空章节——读者会把空章节理解为"作者漏了"。

### 元数据头

所有模板已内置元数据骨架（`id` / `title` / `type` / `status` / `owner` / `created` / `updated` / `applies_to` / `tags`）。`id` 需按 S3 §1.1 规则分配唯一值。

## 模板使用反模式

| 反模式 | 为什么坏 | 正确做法 |
|---|---|---|
| 复制模板后不删无关章节 | 文档变长、读者找不到重点 | 删掉整节，或写「不涉及 + 原因」 |
| 保留 `{{...}}` 占位符 | 门禁 R7 失败；且暴露未完成状态 | 全部替换 |
| 一份文档套多个模板 | 结构混乱 | 一份文档一个类型 |
| 模板里塞具体项目内容 | 复制后忘改，误导读者 | 模板保持通用，用「示例」标注 |
