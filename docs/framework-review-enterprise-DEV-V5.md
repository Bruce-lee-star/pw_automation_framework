# 框架企业级评审与优化方案（对照 Serenity BDD）

> 评审对象：`D:\IdeaProject\pw_automation_framework`（分支 DEV-V5）
> 方法：**仅读源码逻辑**，不参照任何历史评审文档 / `MEMORY.md` / 代码注释；对照 `D:\IdeaProject\serenity-core` 的源码作为企业级基准。
> 配套交付：本次已落实 V3-only codegen、跨线程 trace 归属及重试诊断修复；下文标明已修复项和仍需验证的建议。

---

## 0. TL;DR（总体结论）

框架**已经过了多轮硬化**，并发核心的几处历史性 P0（驱动信道进程级单例、失败信号全局毒化、trace 跨线程丢失、CONTEXT_VIDEOS 泄漏、报告跨轮残留）在当前代码中**均已修复**。这本身是企业级框架该有的"缺陷被发现→收口"的闭环信号。

但对照 Serenity BDD，仍有 **1 类结构性治理项**及若干运行环境验收项：

1. **codegen 维护面已收敛**：当前仅保留并构建 `pw-codegen-v3`；V1/V2 已从仓库移除，历史文档仅作方案记录。
2. **配置读取仍需持续分类治理**：业务报告项已收口；保留的调用主要属于密钥、第三方引擎选项、系统元数据、路径和 CI 探测，不应机械迁移。

报告跨线程归属已改为显式 context/outcome 解析，并补充 route evidence 队列隔离与缺失归属计数。其余观察项包括控制台单字节写入性能（需目标环境基准）、路由调度容量（需真实负载评估）和 Windows 控制台/外部进程编码验收；元素操作的 retry 诊断次数已修正。JDK 21 默认字符集为 UTF-8，不将全局强制 `-Dfile.encoding=UTF-8` 列为缺陷。

---

## 1. 模块协作与包管理（D1）

### 1.1 现状（实测依赖方向）
依赖方向：`pw-reports-v2`、`pw-api-core`、`pw-route` → `pw-core`；`pw-web-ui` → `pw-core` + `pw-reports-v2`；`pw-codegen-v3` → `pw-web-ui`。根 POM 提供统一依赖管理。

- 原先继承 `pw-framework-bom` 的框架模块现直接继承根 POM；根 POM 的 `dependencyManagement` 统一管理其依赖版本，不构成运行时模块依赖边。独立维护的 `pw-reports` 保留自身 POM 与版本管理。
- `pw-route` → `pw-core`；`pw-codegen-v3` → `pw-web-ui`，不构成循环依赖。
- 桥接接口（如 `ReportingBridge`）+ ServiceLoader 用于解耦运行时扩展，不构成当前模块间循环依赖。

**评价：当前模块依赖方向无编译期循环，SPI/桥接用于运行时解耦**——属于**优势**，应在评审中保留。

### 1.2 已处理：codegen 收敛至 V3
| 模块 | 主代码行数 | 父 pom 状态 | 结论 |
|---|---|---|---|
| `pw-codegen`（V1） | 已移除 | 不参与当前构建 | 历史实现 |
| `pw-codegen-v2`（V2） | 已移除 | 不参与当前构建 | 历史实现 |
| `pw-codegen-v3`（V3，包 `web.page.codegen`） | 保留 | 激活 | **唯一维护和构建版本** |

V3 依赖 `pw-web-ui`，应保留其所需的 Web/UI 公共桥接契约。旧版本的方案文档可保留作历史记录，但不得视为当前实现或交付能力。

### 1.3 配置职责边界
配置注册和读取已分别收口到 `pw-core` 的 `ConfigRegistry` 与 `ConfigReader`，领域门面负责类型化访问。`pw-api-core` 中的 `ConfigKeys` 是 API 领域键，不应与核心配置注册表混为同一个配置源。

---

## 2. 配置类（D2）

### 2.1 现状（优势）
已建立 `ConfigRegistry`（核心键及默认值）、`ConfigReader`（配置源和优先级）及类型化访问器（如 `WebFrameworkConfig`、`ApiFrameworkConfig`），形成可继续扩展的配置体系。API 领域的 `ConfigKeys` 仍承载 API 专属键，不等同于核心注册表。

### 2.2 问题：裸配置读取未收口（P2）
- 当前主源码中有直接 `System.getProperty` / `System.getenv` 读取；其中包含运行环境、路径、JVM 属性和 CI 探测，不能把总数认定为业务配置缺陷。BrowserStack 凭证、Cucumber/JUnit 启动选项及并行模式判定现统一经 `ConfigReader`，原环境变量优先级和系统属性实时覆盖保留。
- 报告浏览器分布、CI 链接覆盖、报告 driver/test framework/target 展示、旧 project name 兼容键和 console reporter 开关已登记到 `ConfigRegistry`，并迁移至 `ConfigReader`。
- 剩余直接调用主要位于：统一配置 SPI 的底层属性/环境解析；密钥材料读取（ConfigCipher）；SanitizerRules 的即时 system-property 覆盖；JVM/OS/Maven/Playwright 元数据、临时目录/工作目录/classpath/PATH；PATH 中可执行文件探测及 CI 环境探测。上述均有明确运行时/安全语义；新增业务配置不得绕过 `ConfigRegistry` / `ConfigReader`，也不应把边界探测机械替换或将底层读取总数压到零。
- Serenity 的反例：`EnvironmentVariables` 接口统一收口（`getValue(Enum)` / `getPropertyAsInteger`），业务代码**不直接碰裸字符串键**。

**建议**：新增业务配置统一经过 `ConfigRegistry` / `ConfigReader`；对操作系统、路径及 CI 信息等环境探测保留直接读取的合理例外。增加静态规则前，应先提供显式白名单或统一环境访问封装，避免误报正常 JVM 信息读取。

---

## 3. 日志处理与编码（D3）

### 3.1 已修复：`ConsoleCapture` 编码不一致（P1 → 已修）
`pw-reports-v2/.../report/console/ConsoleCapture.java` 的 `TeePrintStream` 原构造为：
```java
super(new InterceptingOutputStream(delegate, isError), true);   // 使用 JVM 默认字符集
```
而 `flushLine()` 解码侧是 `new String(bytes, StandardCharsets.UTF_8)`。若 JVM 默认字符集被显式设为非 UTF-8，写入与报告解码就可能不一致。

修复：tee 流显式使用 UTF-8 编码：
```java
super(new InterceptingOutputStream(delegate, isError), true, StandardCharsets.UTF_8);
```
（多字节跨 `write` 拆分由行缓冲在 `\n` 处合并后解码。）

### 3.2 已修复：`ReportServer` 无 charset 的 `getBytes()`（P2 → 已修）
`pw-reports-v2/.../report/cli/ReportServer.java:146`：
```java
UUID.randomUUID().toString().getBytes()   // 平台默认编码
```
改为 `getBytes(StandardCharsets.UTF_8)`。

### 3.3 编码边界（观察项，不按全局缺陷处理）
项目目标为 JDK 21，其默认字符集为 UTF-8，报告和日志也显式设置编码。仍应在 Windows 控制台、外部进程及显式指定非 UTF-8 的输入输出边界做集成验证；不建议仅因 JVM 参数未显式设置而强制修改所有启动脚本。

---

## 4. 报告处理（D5）

### 4.1 现状（优势，已修历史缺陷）
- `LocalReportSink` 在每轮 run 开始时 `writtenIds.clear()` + `errors.set(0)`（`LocalReportSink.java:75-76`）——**跨轮残留已修**。
- 报告写入（`ReportServer` 等）全部显式 UTF-8——**无乱码写入**。
- trace 和 video 使用 `ContextOutcomeBinding` 显式关联浏览器上下文与用例；异步收尾凭该绑定获得 outcome ID，不依赖线程上下文猜测归属。
- 报告总线 `ReportBus` 用 `CopyOnWriteArrayList` + 有界 append + dropped 计数 + `fanOut` 逐 sink try/catch——下沉设计稳健。

### 4.2 已修复：跨线程证据归属不再使用全局最近值（P1）
`ReportBus.activeOutcomeId()` / `activeStepId()` 现在仅返回调用线程绑定的 ThreadLocal 值；进程级 `activeStep` / `activeOutcomeId` 回退已移除。`ReportingBridgeImpl.traceTarget()` / `videoTarget()` 也只在当前线程有句柄时返回落点；异步收尾应使用由 `ContextOutcomeBinding` 取得的显式 outcome ID。

NOOP 句柄的 trace/video 不会再挂到另一个并行用例，而是跳过、记录告警并累计 `unattributedArtifactCount()`。相应回归测试覆盖外线程查询和异步产物不串用例。调用方必须为异步收尾保留/传递 context 绑定的 outcome ID。

Web 的 `RouteEvidenceSink` 将待写记录按 outcome ID 分队列：route 调用传入 Playwright `Request`，实现经 `Request.frame().page().context()` 查询 `ContextOutcomeBinding`；每次 flush 只消费当前用例条目，避免 A 场景证据写入 B 场景。显式 context 回调不继承工作线程的 ThreadLocal step，未知 step 的证据按用例级记录。每用例有独立待写上限（`route.evidence.max.pending.per.outcome`，默认 100），进程级上限为 `route.evidence.max.pending`（默认 500）；单用例超限只淘汰自身最旧项。进程级满载时优先从记录最多且至少有两条的其它用例中淘汰一条以接纳新用例；无可安全腾挪空间时拒绝新证据，不将证据写给其它用例。无 frame / 无绑定的事件会告警并计入 `unattributedCount()`；队列溢出、写入失败和成功写入分别计数并有回归测试。非页面 API 请求仍可能没有 frame，目标运行中需采样该计数；如需留存，应由调用方显式传入 `BrowserContext`。

---

## 5. 性能（D6）

### 5.1 观察：`ConsoleCapture.write(int)` 的性能待测
单字节调用会逐次转发到原始流并检查行缓冲；但 `PrintStream` 的常见文本输出通常走批量写入路径。尚无目标运行机基准数据，当前不宣称其存在性能问题，也不贸然改变控制台可见性/flush 语义；需在目标 JDK 与控制台上以代表性日志负载重复测量后再决策。

### 5.2 观察：路由响应轮询 2 线程（P3，需复核）
`RouteDelayScheduler` 使用延迟调度器，并设置 `route.delay.max.pending` 准入上限（默认 1024）；backlog 统计已准入的在途+排队任务，而非仅队列长度。现有 rejected 计数之外，也提供任务启动迟延（累计/最大/平均）和启动计数。定向测试覆盖并发准入上限和指标读取；生产线程数与上限仍须结合真实负载、拒绝率和端到端延迟验收，报告生成线程池规模不能直接作为路由调度基准。

---

## 6. 安全（D7，现状优势）

- **出口脱敏双层**：logback `conversionRule`（`SanitizingMessageConverter`/`SanitizingThrowableConverter`）+ 不经 logback 的 `SanitizingPrintStream`（覆盖 RestAssured `log().all()`），fail-closed（`[SUPPRESSED]`）。
- **密钥管理**：`ConfigCipher` 主密钥链（环境变量 → 系统属性 → `~/.dbb_automation_master_key`）；`SecretValue` 严格模式开关。
- **Body 脱敏**：`BodySanitizers` JSON 树递归 / XML 双通道 / form-urlencoded / 纯文本兜底，解析失败不原样放行。
- 这些是企业级"凭据不出域"的成熟做法，建议作为标杆保留并补充单测覆盖率证据。

---

## 7. 内存泄漏（D8）

### 7.1 现状（优势，已修历史缺陷）
- 所有静态注册表（`PageEventMonitor`/`PageInteractionMonitor` 的 `REGISTERED_*`、`RolePickerSessionState` 的 9 张表、`PAGE_SWITCH_LOCKS` 等）底层均为 **`WeakHashMap`**（弱引用键），随每 case 重建的 `BrowserContext`/`Page` 被 GC 后自动退场——**不会无界增长**。
- `CONTEXT_VIDEOS` 已从"强引用键"改为 `synchronizedMap(new WeakHashMap<>())`（`PlaywrightContextManager.java:80`），并在 `:418` 显式 `remove`——**历史泄漏已修**。
- `ThreadLocal` 清理有专门 `cleanupThreadLocals()` 钩子。

### 7.2 已处理：`ThreadContextRegistry.activeThreadCount()`
当前实现同步遍历 `CONTEXTS` 计数，不是直接调用 `WeakHashMap.size()`。原先关于该指标系统性偏大的问题描述已过时；后续只需将其作为可观测性回归项维护。

---

## 8. 类的分层与设计（D4）

### 8.1 优势
- Playwright 生命周期为清晰的 4 层（Playwright / Browser / BrowserContext / Page），Browser 为 worker 级复用、Context 每 case 重建，并由 `LifecycleLockMediator`、`ContextRegistryImpl` 收口。
- `pw-core` 内按"切片"分层（common / core），并有 **ArchUnit 门禁**（`G1: framework 切片无循环`）——这是 Serenity 没有、但更现代的治理手段。

### 8.2 已优化：`ensurePageValid` 不再每次 `getPage()`（P3，已缓解）
`PageContextState.java:65-66,159-163`：仅当 `currentPageEpoch()` 与本品不同才走昂贵的 `PlaywrightManager.getPage()` 全链路比对，否则跳过。**历史"逐元素操作静态调用链开销"已缓解**。

### 8.3 已修复：`ElementOperationSupport` retry 诊断
诊断字段现在报告实际重试次数：无重试失败为 0，导航后重试失败为 1；错误消息相应报告总尝试次数 1 或 2，并有定向回归测试。
- 注：导航判定已从裸 `"navigat"` 收窄为 `"navigation"/"navigating"`（`:204`），过宽匹配已修复。

---

## 9. 对照 Serenity BDD 差距总表

| 维度 | Serenity 做法（证据） | 本框架现状 | 差距 |
|---|---|---|---|
| 模块/包 | model←reports←core 多模块分层 | DAG 干净、SPI 解耦（优势） | ✅ 持平/更好 |
| 配置 | `EnvironmentVariables` 统一收口，无裸键 | `ConfigRegistry` / `ConfigReader`；报告展示项和控制台 reporter 开关已登记，OS/JVM/CI 元数据仍直接探测 | ⚠ P2（剩余调用需按类别治理） |
| 报告 | `TestOutcome` 模型与渲染分离；`AcceptanceTestReporter` SPI + `META-INF/services`；有界线程池并行生成 | `ReportBus`+sinks 解耦；当前线程上下文与 context 显式 outcome ID 分离，未知归属产物不再猜测 | ✅ 串用例兼容回退已收口 |
| 扩展点 | `StepListener`/`DriverSource` 等全 SPI | `ReportingBridge`/`RouteLifecycleRegistry` 桥接（优势） | ✅ 持平 |
| 并发 | `ThreadLocal<StepEventBus>` 按测试线程隔离 | 当前线程句柄 + context/outcome 显式绑定；缺少归属的异步产物跳过并累计指标 | ✅ 已收口 |
| 编码 | `StandardCharsets.UTF_8` 强制 + `SERENITY_REPORT_ENCODING` 可配 | 写入显式 UTF-8；跨 write 多字节捕获有回归测试，Windows 控制台/外部进程仍需环境验收 | ⚠ 环境验收 |
| 资源生命周期 | `WebdriverManager`/`DriverServicePool.shutdown()` 显式回收 | `CloseGuard` 有界关闭 + `ShutdownCoordinator` | ✅ 持平 |
| 冗余 | 单一 screenplay/webdriver 实现 | 仅保留 `pw-codegen-v3` | ✅ 已收敛 |

---

## 10. 本会话已实施修复（代码级）

| 文件 | 改动 |
|---|---|
| `pw-web-ui/.../ScenarioTraceRecorder.java` | Context 绑定的 outcome ID 优先于进程级活动用例快照，避免并行场景 trace 落错目录 |
| `pw-reports-v2/.../ReportingBridgeImpl.java` | `currentOutcomeId()` 只读当前线程句柄，避免用全局最近用例绑定新 Context |
| `pw-web-ui/.../ElementOperationSupport.java` | 诊断中报告实际重试次数和总尝试次数 |
| `pw-codegen-v3` | 保留 V3；删除 V1/V2 旧实现 |
| `pw-reports-v2/.../ReportBus.java` | 取消全局活动用例/步骤回退；NOOP trace/video 不再猜测归属，暴露未归属产物计数并告警 |
| `pw-reports-v2/.../ReportingBridgeImpl.java` | 无当前线程句柄时不推断 trace/video 落点；异步路径使用显式 outcome ID |
| `pw-core/.../ConfigRegistry.java` | 登记报告展示配置、渲染超时和场景控制台 reporter 开关 |
| `pw-reports-v2/.../ReportView.java`、`AggregateBuilder.java`、`pw-web-ui/.../ConsoleScenarioListener.java` | 业务配置读取统一通过 `ConfigReader` |
| `pw-reports-v2/.../ConsoleCaptureTest.java` | 增加 UTF-8 多字节字符跨多次 write 的回归测试 |
| `pw-route/.../RouteDelayScheduler.java` | 增加任务启动迟延（累计/最大/平均）和已启动任务计数，可与积压/拒绝计数一起观测 |
| `pw-core/.../RouteEvidenceSink.java`、`RouteEvidenceRegistry.java` | 增加可选请求上下文透传，不破坏旧实现的默认调用契约 |
| `pw-web-ui/.../RouteEvidenceSink.java` | 通过 Request → BrowserContext → ContextOutcomeBinding 解析用例；待写队列按 outcome ID 隔离，未知归属不会串用例；增加未归属、丢弃、写失败、写入计数 |
| `pw-web-ui/.../RouteEvidenceSinkTest.java` | 覆盖显式 context 绑定优先、按 outcome flush 和无上下文可观测 |

---

## 11. 优化方案（按优先级）

### P1（结构性，先动）
1. **codegen V3-only**：V1/V2 已删除，保持 V3 为唯一构建和维护版本；旧方案文档标注为历史材料。
2. **报告跨线程归属**：已移除全局活动用例/步骤猜测；异步 trace/video 使用显式 outcome ID，缺少上下文时拒绝错误挂载并提供未归属产物计数。
3. **配置收口**：报告展示和 reporter 开关等业务键已登记并迁移；其它系统属性/环境变量读取按配置、密钥、JVM/OS 元数据、CI 探测分类，不能将原生运行环境探测机械替换为业务配置。

### P2（稳健性/可维护性）
4. 已增加 UTF-8 行捕获跨 write 回归测试，并在不同 JVM 默认字符集下启动测试子进程验证捕获仍使用 UTF-8；Windows 控制台、外部进程实际编码边界仍需在目标 CI/运行环境验收，不全局强制 JVM 字符集参数。
5. `ConsoleCapture.write(int)` 未做未经测量的缓冲优化；应在目标 JDK/控制台下跑重复基准并确认 flush/可见性后再决策。
6. 调度器已将准入上限登记为 `route.delay.max.pending`，backlog/rejected/任务启动迟延均可观测并有定向测试；线程池容量阈值仍需在真实负载下验收，不按 CPU 核数盲目扩池。
7. retry 次数诊断已修复，保留定向测试防回归。

### P3（可观测性）
8. `ThreadContextRegistry.activeThreadCount()` 已遍历计数，保留可观测性回归测试。
9. trace/video 与 route evidence 的缺少上下文、溢出和写入失败均有可观测计数；API evidence 现由 ReportBus 统一应用同一份可配置脱敏规则，并有定向产物测试。密钥/证据完整链路仍需在目标 CI 验收，route evidence 无 outcome 的运行计数需在并行 CI 中采样。

---

## 12. 落地顺序建议
1. 仅保留并构建 `pw-codegen-v3`；V1/V2 已删除。
2. 完成报告跨线程归属收口：无上下文时不再采用最近活动用例；收尾调用必须传入 context 绑定的 outcome ID。
3. 修复并测试 retry 诊断次数。
4. 报告业务配置读取、API evidence 统一脱敏、UTF-8 跨 write 子进程测试、route evidence 配额隔离及路由调度迟延/准入指标已落地；控制台性能、真实负载容量及 Windows 控制台/外部进程编码仍属于目标环境验收，不在缺少基准时宣称通过或改运行策略。

> 说明：本评审所有结论均来自当前源码逻辑推导；Serenity 基准来自对其 `serenity-core` 源码的实地勘察。凡标注"已修/已缓解"的项，均为当前代码中已确认修复的历史问题，列出以说明框架的演进状态，而非作为现存缺陷重复计入。
