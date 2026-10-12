# SSO 并发与会话隔离 —— 架构评审与企业级方案

> 适用版本：Playwright Java 1.62 / Serenity BDD 4.3.4 / Cucumber JVM 7.34 / JDK 21
> 适用仓库：`pw_automation_framework`（分支 `DEV-V4`）
> 评审对象：`docs/architecture/SSO并发与会话隔离设计.md`（PoC 设计稿）
> 评审结论：**方向正确、与已落地实现高度一致；存在 5 处分歧/缺口需修正，并需补齐 API 并发与多策略兼容**。
> 状态：评审 + 企业级修订方案（可作为 `DEV-V4` 实施基线）

---

## 0. 评审方法

- 以**实际落地代码**为权威，对照 PoC 设计稿逐章评判合理性。
- 实际落地点（已提交 `dd93deb8` 及后续收口）：
  - `pw-web-ui/.../web/concurrent/ConcurrencyGate.java` —— SSO 感知并发闸门
  - `pw-web-ui/.../web/concurrent/ConcurrencyPartitionKey.java` —— 不可变分区键
  - `pw-web-ui/.../web/session/SessionStore.java` —— 会话内核 + 闸门唯一接入点
  - `pw-web-ui/.../web/session/SessionManager.java` —— 兼容门面（全委托）
  - `pw-route/.../route/claim/RouteClaim.java`、`exec/BoundedOps.java`、`exec/RouteIoExecutor.java` —— 路由/捕获并发隔离
- PoC 设计稿意图：同身份串行、不同身份并行、协议异常不触发重登、原子写 + 回滚、SSO 三态、分阶段上线。

---

## 1. 现状核对：设计稿意图 vs 实际落地

| 维度 | PoC 设计稿 | 实际落地 | 判定 |
| --- | --- | --- | --- |
| 闸门类 | 新造 `SsoUserSessionGate`（键 `entity/env/user`） | 复用 `ConcurrencyGate`（键 `sessionKey=env_username`，**缺 `entity`**） | **冲突+缺口**：不应新造一套；且 `entity` 维度（取自 `logon <entity>` 步骤）须补入 `ConcurrencyPartitionKey`，否则跨实体同账号错并一道闸 |
| 接入点 | 业务层 `SsoScenarioHooks`（`@Before/@After`） | 框架 `SessionStore` 会话路径（restore/save 入口）+ `PlaywrightListener` 收尾无条件释放 | **设计稿反模式**：业务层接线会依赖 `@After` 释放、易泄漏；以框架接入点为唯一真相 |
| 同线程重入 | leaseId 版本化 | `ThreadLocal<Deque>` 持有表 + 重入 no-op | **实际更优**：重入 + 泄漏兜底释放，杜绝自锁/卡死 |
| 公平/许可 | 公平 `Semaphore(1)` | 公平 `Semaphore(perKeyPermits)` | 一致 |
| 失败策略 | 超时 fail | `fail-closed`（默认）+ `fail-open` 逃生舱 | **实际更优**：逃生舱避免整套卡死 |
| 缓存内容 | 只缓存认证材料（`SsoSessionEntry`） | `SessionStore` 只存 storageState 路径 + token 元数据 | 一致 |
| 原子写 | tmp + `ATOMIC_MOVE` + **先备份 `.prev`** | tmp + `ATOMIC_MOVE`（无 `.prev` 备份） | **缺口**：回滚位未实现 |
| 跨 fork 分区 | `stride` + `ForkAwareAccountResolver` | 单 JVM 部署（`forkCount=1`），未实现 | 当前 N/A；`forkCount>1` 时需补 |
| SSO 三态 | `VALID/SUSPECT/INVALID` 枚举 | 仅原则，无显式枚举；失效检测靠 401/跳登录页/服务端探针 | 需抽枚举并接入失效检测 |
| Refresh rotation | 刷新即导出 | `saveSession` 每次导出 storageState | 一致 |
| Route handler 规则 | §8 禁止项 | ArchUnit L6 + `RouteClaim` CAS 单所有者 | 一致且更强 |
| API 并发 | **未覆盖** | route 捕获并发已由 route 模块独立隔离；UI/API 共享身份未建模 | **缺口**：需补 |

---

## 2. 逐章合理性评判

### §2 线程安全边界 —— 合理，采纳
对象矩阵（Playwright/Browser/Context/Page/调用期对象）与框架铁律一致。补充一点：路由 handler 已通过 `RouteClaim`（CAS 单所有者状态机）把"谁有权终结 Route"锁死，解决了 `playwright-java` 的 `RouteImpl.handled` 非原子 TOCTOU——这条应并入 §2.1 作为"调用期对象"的强约束证据。

### §3 SsoUserSessionGate —— 意图正确，实现路径应修正
- **键维度**：设计稿用 `(entity, env, user)` 三维，**正确且需采纳**。`entity` 不是新造抽象——它对应 `Given ... logon <entity> ...` 步骤里已有的**实体名**维度（测试在步骤中显式写明的实体），沿用现有概念即可；注意 **`DBB` 是产品/项目名，不是该 `entity` 维度的值**，且**不存在"厂"的区分**——`entity` 仅作 `logon` 步骤透传的实体值，框架不得据此构造工厂/租户级分区或层级，不引入任何"厂"抽象。
  - **修正我的误判**：此前我把 `entity`（误称"厂"）判为"无对应抽象、冗余、退化为二维"是**错的**。同一 `env+user` 在不同 `entity` 下其实是**不同的登录身份**，若只用 `env_username` 会错误地把跨实体同账号并到同一道闸（既可能误串行，也可能串缓存），因此 `entity` 必须纳入键。
  - **做法**：不要新造 `SsoUserSessionGate`。在 `ConcurrencyPartitionKey` 上**显式新增 `entity` 维度**，值取自 `logon` 步骤的实体名（与 `sessionkey`/`apiclient`/`sessionPolicy` 同列一道 `ConcurrencyGate`），避免"两套并行闸门"象限分裂。
- **leaseId 版本化 vs 持有表**：设计稿用 `activeLease` + leaseId 防误释放；实际用 `ThreadLocal<Deque<Key>>` 持有表，并在场景收尾 `releaseAllForCurrentThread()` 无条件释放。后者更优——它根治"许可泄漏 → 整套 park 死"的实测死锁，且不依赖调用方记得 release。应采用实际方案，删除设计稿的 leaseId 思路。
- **公平信号量**：一致，保留。

### §4 SessionStore 只缓存认证材料 —— 合理，原子写有缺口
- 缓存内容约束（禁存 Context/Page/Route/Response）与 `SessionStore` 实际一致，采纳。
- **§4.3 原子写顺序（先备份 `.prev` 再覆盖主文件）未被实际代码满足**。实际 `writeStorageStateAtomically`（SessionStore:888）只做 `tmp → ATOMIC_MOVE`，**没有 `.prev.json` 回滚位**。
  - **风险**：登录态导出中途进程被杀/磁盘满，主文件损坏且无法回滚到"上一次成功登录态"，下一场景 restore 到坏 JSON → 判定 INVALID → 全体重登。
  - **修正（企业级缺口）**：补 `.prev.json` 备份（备份在覆盖主文件之前），与 §4.3 顺序一致；并加"主文件损坏时自动回退 `.prev`"的恢复逻辑。

### §5 跨 fork 分区 —— 思路正确，当前 N/A，SPI 方向对
- 当前部署 `forkCount=1`（单 JVM、进程内多线程并行），故跨 fork 账号碰撞**物理上不可能发生**（见 `ConcurrencyGate` 类注与 SessionManager 评审结论）。设计稿 §5 属"未来 `forkCount>1` 时"的前瞻设计。
- §5.7 的 `RunScopeAccountProvider` SPI 思路**正确且企业级**：框架不读业务参数，由业务项目经 SPI 决定本次账号池。`entity` 维度**本就来自 `logon <entity>` 步骤**（测试已显式给出实体名），**不应从 env 名前缀推导（O63→厂 脆弱且错误）**。**修正**：entity 由 `logon` 步骤直接提供，框架从步骤取，不隐式推导、不靠 env 前缀；账号池由业务显式声明（配置或 SPI 返回）。
- §5.6 静态账号扫描 + Dry-run 报告：有价值的"开跑前 fail fast"，但与 stride 一样依赖 `forkCount>1`。单 JVM 下也可保留为"账号清单校验"（缺失账号早失败），与 fork 解耦，建议保留。

### §6 SSO 三态 —— 合理，但需与 Playwright 通道竞态治理对齐
- 三态枚举（`VALID/SUSPECT/INVALID`）与"协议异常 ≠ 会话失效"铁律**正确且必要**，采纳。
- **零容忍原则（Prevention-First）**：协议异常（含 `Object doesn't exist: response@…`、导航中断、`Connection.dispatch` 通用敞口）**本质都是通道对象所有权治理的漏洞**，不是可预期的运行时常态。治理目标不是"发生后再分类"，而是**从源端杜绝**：严格单协调入口、调用期对象不跨线程逃逸、Context 销毁顺序受控、Response/Route 不缓存。设计稿把 `response@` 列为 SUSPECT 常态触发源已不成立——其触发源（`context.onResponse` 订阅）**已在框架源端消除**（v2 `RouteRuntimeImpl` 移除订阅、v1 passthrough 默认 `false`），其余协议异常同样应被治理到零。
- SUSPECT 三态保留为**防御性最后兜底**（仅当治理仍有漏洞时）：一旦发生，① **绝不破坏 SSO 缓存**（不删不重登）；② **按框架缺陷对待**——打全量诊断（线程名 / context GUID / 对象 GUID / 调用栈）并计入框架质量信号，借 CI 通道压测把发生次数收敛到 0，而非"标失败就放行"。
- 三态判定须与"SSO 失效检测"联动：401 / 跳登录页 / 服务端探针失败 → `INVALID`（重登，属真实会话失效）；纯协议/网络层异常 → `SUSPECT`（兜底，且应驱动治理修复）。当前框架无显式 `SsoSessionState` 枚举——**建议抽枚举并接入失效检测链**。

### §7 Refresh Token Rotation —— 合理补充
原则（刷新即导出、刷新期间不释放 gate、刷新失败 SUSPECT、同 user 等待）正确。实际 `saveSession` 每次导出 storageState，与"刷新即导出"一致。补充：gate 只解决"登录/restore 串行"，解决不了轮换窗口期——须配套 §7 策略，且应与 SSO 团队确认测试环境 rotation 豁免。采纳。

### §8 Route Handler 规则 —— 合理，且已有更强保证
§8 允许/禁止项与框架实际一致。补充：并发安全已由 `RouteClaim`（CAS 单所有者）+ `BoundedOps`（有界 IO）+ `RouteIoExecutor`（独立 IO 线程）在 route 模块内独立保证，**与 SSO gate 正交**——route 捕获并发不受 SSO 闸门影响。ArchUnit L6 已固化 `route..` 不依赖 `..playwright.Page..` 写方法。采纳并补强。

### §9 与现有代码接线 —— 设计稿接入点反模式，以框架接入点为真相
- 设计稿 §9.1 把 gate 提到业务层 `SsoScenarioHooks`（`@Before` acquire / `@After` release）。**这与框架"业务层禁止接线"决策直接冲突**（见 `ConcurrencyGate` 类注：业务自建分区键会形成"两把键不同的锁"且依赖业务 `@After` 释放，漏跑即泄漏 → 同身份后续场景卡到 fail-closed 超时）。
- **实际接入点（唯一真相）**：`SessionStore.acquireSessionGate(sessionKey)` 覆盖 restore/save 入口，释放由 `PlaywrightListener.cleanupThreadLocals()` → `SessionStore.releaseSessionGate()` + `ConcurrencyGate.releaseAllForCurrentThread()` **无条件**执行。
- **修正**：删除 §9.1 的 `SsoScenarioHooks` 方案，明确"业务层禁止接线，全部经 `SessionStore` 会话路径"。

### §10/§11 分阶段上线 / 验收 —— 合理
分阶段（单线程回归 → 同 user 排队 → 跨线程 → 跨 fork → 容量压测）与"连续 20 次稳定"验收合理，采纳。补充：当前 `parallel` profile 已默认关闭（`-Pparallel` 才开），故阶段 0~2（单 JVM 串行/进程内并行）可立即验证；阶段 3~5（跨 fork）需 `forkCount>1` 才成立。

---

## 3. 兼容性矩阵（用户明示的多场景）

| 场景 | 特征 | 企业级行为 | 落地要点 |
| --- | --- | --- | --- |
| **不分实体（单 entity）** | 全部测试只 `logon` 同一实体 | `entity` 为常量可省略，退化为 `env_username`；多实体时 `entity` 维度必须纳入，避免跨实体同账号错并闸 | 与现有实现一致（单 entity 时零改动） |
| **没有 SSO 竞态**（服务端允许多并发会话） | 同账号可同时多 Context | `sessionPolicy=MULTI`：该身份 gate 退化为 `permits=N`（并行限额）或 no-op；`fail-closed` 不得误杀并行场景 | **新增策略维度**（设计稿缺失） |
| **有 SSO 竞态**（账户级单会话，如 DBB） | 同账号二登互踢 | `sessionPolicy=SINGLE`（默认）：同 `sessionKey` 串行（permits=1），不同并行 | 当前 `ConcurrencyGate` 已满足 |
| **API 并发** | rest-assured / 服务令牌复用 | API 凭证经同一 `ConcurrencyPartitionKey` 建模（维度含 `apiclient`/`apitoken`），按 `sessionPolicy` 串行/并行；route 捕获并发由 route 模块独立隔离 | **设计稿缺失，需补** |
| **跨 fork（`forkCount>1`）** | 多 JVM | `RunScopeAccountProvider` SPI 决定账号池 + `stride` 分区；JVM 间不撞 user | 当前单 JVM 下 N/A，未来启用 |
| **Playwright 通道连接异常** | `Object doesn't exist` / 导航中断 / 网络抖动 | **零容忍（Prevention-First）**：源端治理杜绝（单协调入口 / 对象不逃逸 / 受控销毁）；`response@` 已消除；残余协议异常 → `SUSPECT` 兜底且按框架缺陷暴露诊断、收敛到 0 | 与 §4.10 / §6 三态对齐 |
| **场景失败** | stepFailed / 超时 | gate 由框架收尾无条件释放，Context 关闭（同 `sessionKey` 失败不关、无绑定才彻底拆——见 `clearThreadResourcesOnCaseAbort`） | 当前已实现 |

> **核心结论**：同一套 `ConcurrencyGate` + `ConcurrencyPartitionKey` 覆盖全部场景：
> - 身份键 = `entity` + `env` + `user`；`entity` 取自 `logon <entity>` 步骤的实体名（**不是产品名 DBB**）。此前把 `entity` 误判为冗余"厂"是错的——同 `env+user` 跨实体即不同身份，必须纳入键，否则错并闸/串缓存；
> - `sessionPolicy`（SINGLE/MULTI）—— 决定同身份串行还是并行；
> - `apiclient`/`apitoken`（或通用 `identityType`）—— 把 API 凭证纳入同一分区模型。
> 不引入第二套闸门、不引入业务层接线。

---

## 4. 企业级方案（修订版，基于 DEV-V4）

### 4.1 统一身份 + 策略模型
复用 `ConcurrencyPartitionKey`，维度规范化（小写维度名、值保留大小写以兼容大小写敏感 IdP）：
```
entity     = <logon 步骤实体名>             // UI 登录身份实体维度（取自 Given ... logon <entity> ...，非产品名 DBB）
sessionkey = env + "_" + username          // UI 登录身份（现有）；须与 entity 维度叠加，否则跨实体同账号错并闸
apiclient  = <clientId>                     // API 凭证身份（新增）
sessionPolicy = SINGLE | MULTI              // 串行/并行策略（新增，默认 SINGLE）
tenant / role                              // 可选扩展维度
```
`ConcurrencyGate.perKeyPermits()` 由 `sessionPolicy` 决定：`SINGLE→1`，`MULTI→N`（可配）。键含 `entity` + `sessionPolicy` 维度，使"同 `env+user` 跨实体"不会串到同一道闸、且"同身份不同策略"也不会串到同一道闸。

### 4.2 单一闸门实现（不新造）
- 维持 `ConcurrencyGate` 为唯一闸门；删除 PoC 的 `SsoUserSessionGate` 方案。
- 保留：同线程重入 no-op、`releaseAllForCurrentThread()` 泄漏兜底、公平信号量、fail-closed + fail-open 逃生舱、`stats()` 观测。

### 4.3 框架唯一接入点（业务禁止接线）
- UI：`SessionStore.acquireSessionGate(sessionKey)`（restore/save 入口）+ `PlaywrightListener.cleanupThreadLocals()` 收尾释放。
- API：在 API 会话/凭证获取路径（如 `ApiTestContext` 绑定客户端时）复用 `ConcurrencyGate.acquire(ConcurrencyPartitionKey.of(Map.of("apiclient", ...)))`，释放同样由框架收尾。
- 业务测试层**禁止**自建分区键 acquire/release（铁律，与现有 `ConcurrencyGate` 类注一致）。

### 4.4 原子写补回滚位（关闭 §4.3 缺口）
`writeStorageStateAtomically` 增加：覆盖主文件**之前**先把当前主文件备份为 `<key>.prev.json`；restore 时若主文件损坏/格式错，自动回退 `.prev`。保留 `ATOMIC_MOVE` + 非原子回退兼容。

### 4.5 SSO 失效三态 + 协议异常隔离（Prevention-First）
- 抽 `SsoSessionState { VALID, SUSPECT, INVALID }` 枚举。
- **协议异常零容忍**：通道对象所有权治理（严格单协调入口、调用期对象不跨线程逃逸、Context 销毁顺序受控、Response/Route 不缓存）是**第一道防线**，目标是运行期零协议异常；`response@` 已在框架源端消除，其余协议异常同样须治理到零。
- 失效检测链映射：401 / 跳登录页 / 服务端探针失败 → `INVALID`（重登，属真实会话失效）；纯协议/网络层异常 → `SUSPECT`（**仅兜底**：保留缓存、标记场景失败、**不删不重登**，且必须输出全量诊断驱动治理修复，不得作为常态放行）。
- 与框架 `PageEventMonitor` 诊断、`BrowserCrashGuard` 韧性守卫联动，避免把"框架内部事件订阅竞态"误判为"SSO 会话失效"。

### 4.6 跨 fork 账号分区（仅 `forkCount>1`）
- 保留 `RunScopeAccountProvider` SPI（业务项目决定本次账号池），框架不读业务参数。
- `stride` 静态切分 + 可选静态账号扫描（开跑前 fail fast：缺失账号早失败、未用账号 WARN）。
- 账号池由业务**显式声明**，`entity` 由 `logon` 步骤直接提供，不靠 env 名前缀隐式推导。

### 4.7 API 并发（独立补充）
- API 测试与 UI 测试混跑时，统一身份模型：API 客户端凭证走 `apiclient` 维度分区，按 `sessionPolicy` 串行/并行。
- route 捕获并发（多 Context 各自 route、handler 并发终结）已由 `RouteClaim`/`BoundedOps`/`RouteIoExecutor` 在 route 模块内独立保证，与 SSO gate 正交，无需在 SSO 方案内重复处理。

### 4.8 可观测性
- `ConcurrencyGate.stats()`：进入次数 / 被串行化身份数 / 活跃闸门数。
- 开跑前 Dry-run 报告（账号清单 + 分区 + 并发上限公式）。
- 故障诊断速查表（沿用设计稿 §15，补充"MULTI 策略下仍互踢 = 服务端真单会话，需改 SINGLE"）。

### 4.10 协议异常零容忍（Prevention-First）验证门禁
- **目标**：CI 必须证明在并行负载下**零协议异常**；任何协议异常视为通道治理的缺陷，而非可吞掉的常态。
- **治理手段（源端杜绝）**：
  1. `Browser` 由唯一协调入口发起调用，`newContext()` 走串行协调入口（§2 铁律）；
  2. 调用期对象（`Request`/`Response`/`Route`/`APIResponse`）不得逃逸到其他线程、不得缓存到静态字段；
  3. Context/Page 销毁顺序受控，场景收尾由框架无条件清理（`PlaywrightListener`）；
  4. Route handler 不回调 Page 写方法、不跨线程发布对象（`RouteClaim` CAS 单所有者 + `BoundedOps` 有界 IO 已保证）。
- **验证（CI 通道压测）**：加入"故意高并行 + 故意慢 handler + 故意中断导航"的 torture 用例集，断言运行期协议异常计数 == 0；非零则**构建失败**（fail-closed），并输出诊断（线程 / context GUID / 对象 GUID / 栈）定位所有权违规。
- SUSPECT 仅作为兜底安全网：即便发生，也不破坏 SSO 缓存、不重登，并计入框架质量信号推动治理收敛到零。

### 4.9 并发上限公式（统一）
```
最大有效并发 = min(
    JVM 内并行度（parallel profile 的 fixed.parallelism × 每线程 scenario 数）,
    forkCount × 每 fork 并行度,                  // 仅 forkCount>1 时计入
    可用 SSO/API 账号数（按 sessionPolicy）,
    SSO 服务端单用户并发会话上限,
    浏览器进程可承载 Context 数,
    CI CPU/内存
)
```

---

## 5. 落地路线（DEV-V4 优先级）

| 序 | 项 | 类型 | 优先级 | 说明 |
| --- | --- | --- | --- | --- |
| 1 | 原子写补 `.prev` 回滚位 + 主文件损坏回退 | 缺口修复 | P0 | 直接消除"登录态损坏→全体重登"风险 |
| 2 | `entity` 维度（取自 `logon` 步骤）+ `sessionPolicy` 维度（SINGLE/MULTI）+ `perKeyPermits` 联动 | 兼容性/正确性 | P0 | `entity` 补入键避免跨实体同账号错并闸；`sessionPolicy` 覆盖"无 SSO 竞态"场景避免误串行 |
| 3 | API 凭证身份维度（`apiclient`）+ 接入点 | 兼容性 | P1 | 覆盖"API 并发"场景 |
| 4 | 抽 `SsoSessionState` 枚举 + 失效检测链接入 + 协议异常零容忍验证门禁 | 健壮性 | P1 | 落实 §6 三态；新增 CI 通道压测断言零协议异常（§4.10） |
| 5 | 删除设计稿 `SsoUserSessionGate`/`SsoScenarioHooks` 反模式，文档以框架接入点为真相 | 文档/治理 | P1 | 防业务误接线 |
| 6 | `RunScopeAccountProvider` SPI + stride（仅 `forkCount>1`） | 前瞻 | P2 | 当前单 JVM N/A，未来启用 |
| 7 | 静态账号扫描 + Dry-run 报告 | 质量增强 | P2 | 开跑前 fail fast |

> 阶段 0~2（单 JVM 串行/进程内并行）可立即验证；阶段 3~5（跨 fork）依赖 `forkCount>1`。
> 验收沿用设计稿 §11，新增：MULTI 策略下并行无互踢、API 凭证并发无令牌失效、原子写回滚位有效。

---

## 6. 一句话总结（企业级）

> 复用唯一 `ConcurrencyGate` + 不可变 `ConcurrencyPartitionKey`：**同身份按 `sessionPolicy` 串行（SINGLE）或并行（MULTI）**，**不同身份/不同策略并行**；身份模型同时覆盖 **UI 登录（`sessionkey`）与 API 凭证（`apiclient`）**；**业务层禁止接线**，全部经 `SessionStore`/API 会话路径由框架收尾释放；**协议异常零容忍（Prevention-First）—— 源端治理杜绝通道对象所有权漏洞，CI 通道压测断言运行期零协议异常，残余仅作 SUSPECT 兜底且绝不破坏 SSO 缓存**；storageState **原子写 + `.prev` 回滚位**；跨 fork 经 SPI 账号池 + stride（仅多 JVM）。不新造第二套闸门。
