# SSO 并发与会话隔离设计方案（终稿）

> 适用版本：Playwright Java 1.62 / Serenity BDD 4.3.4 / Cucumber JVM 7.34 / JDK 21
> 适用仓库：`pw_automation_framework`（分支 `DEV-V4`）
> 状态：已评审合并终稿（DEV-V4 实施基线）。原《SSO并发与会话隔离_架构评审与企业级方案》已并入本文件，不再单独维护。
> 评审结论：方向正确、与已落地实现高度一致；原 PoC 设计稿的 `entity/厂` 三维键、业务层 `@Before/@After` 接线、协议异常常态分类 SUSPECT 三处需修正，并已补齐 API 并发与多策略兼容。

***

## 1. 背景与目标

### 1.1 现状
- 框架已具备：JVM 级 `Playwright` / `Browser`，scenario 级 `BrowserContext` / `Page`，Route v2 随 Context 销毁。
- 登录是 Given 步骤（`LogonGlue` → `LoginSteps.performLogin` → `SessionManager.restoreSession` → `SessionStore.wrapLeaderAction`）。
- `SessionStore` 当前在 JVM 内按 `env+user` 缓存登录会话；已落地 `ConcurrencyGate`（会话并发闸门）+ `ConcurrencyPartitionKey`（不可变分区键）作为唯一接入点。
- 路由并发由 `pw-route` 独立保证：`RouteClaim`（CAS 单所有者状态机）、`BoundedOps`（有界 IO）、`RouteIoExecutor`（独立 IO 线程），与 SSO 闸门正交。
- 未做多 JVM fork 并发；未做同 user 并发保护（待 §5 / DEV-V4 补齐）。

### 1.2 问题
DBB / SSO 是**账户级服务端单会话**：同一账号在第二个 Context 登录会把第一个 Context 的 token 踢掉，表现为随机 401、跳登录页、失败不可复现。

### 1.3 目标
1. 同一 user 在同一时刻只有一个 Context 持有其有效 SSO 会话。
2. 不同 user 的 scenario 完全并行，互不阻塞。
3. 跨 Surefire fork 不撞 user。
4. **协议层异常零容忍（Prevention-First）**：通过通道对象所有权治理在源端杜绝；残余协议异常仅作兜底、绝不误删 SSO 缓存、不重登。
5. 连续 20 次稳定运行：零互踢、零残留浏览器进程。

***

## 2. 线程安全边界（统一约束）

### 2.1 对象矩阵
| 对象                                               | 拥有者                       | 是否可跨线程        |
| ------------------------------------------------ | ------------------------- | ------------- |
| `Playwright`                                     | worker JVM 单例             | 否             |
| `Browser`                                        | worker JVM 持有，由串行协调入口发起调用 | 不允许业务线程直接并发调  |
| `BrowserContext`                                 | 单 scenario                | 否             |
| `Page`                                           | 单 scenario                | 否             |
| `Request` / `Response` / `Route` / `APIResponse` | 调用期                       | **不得逃逸到其他线程** |

### 2.2 铁律
- 每个 Browser 由 worker 生命周期拥有，`newContext()` 走唯一协调入口。
- 每个 Context / Page / 调用期对象，只由持有它的 scenario 线程访问。
- Route handler 不回调 Page 写方法、不缓存 Request/Response/Route（已由 `RouteClaim` CAS 单所有者 + `BoundedOps` 有界 IO 在 route 模块内强制保证）。
- 不写 "Playwright 已串行化所以对象随便传" 这种推断。

***

## 3. 核心模型：会话并发闸门（ConcurrencyGate）

### 3.1 语义
> 同一 `(env, user)` 在同一 JVM 内，同一时刻只允许一个线程进入其**完整会话生命周期**（gate acquire → newContext → scenario → storageState 导出 → context.close → release）。

不是 "借一个账号名"，是 "独占该账号从登录到 Context 关闭的整段生命周期"。

**键模型（最终）**：
- 分区键 = `env` + `user`，即 `sessionKey = env + "_" + username`。
- **无 `entity` 维度、无"厂"区分**：本框架没有实体/工厂分区概念；`DBB` 是产品/项目名，不是键维度（设计稿早期的 `entity` / "厂" 三维键已废弃）。
- 兼容维度通过 `ConcurrencyPartitionKey` 的附加维度表达（见 §3.4），不引入第二套闸门。

### 3.2 生命周期（gate 持有范围）
```
gate.acquire(sessionKey)
  │
  ├─ restore / 校验 storageState
  ├─ browser.newContext()            ← 每次新建，不复用旧 Context
  ├─ context.newPage()
  ├─ （可选）SSO 刷新 / 有效性校验
  ├─ 跑 scenario（业务步骤、Route、断言）
  ├─ 导出新 storageState（原子写）
  ├─ page.close()
  ├─ context.close()
  └─ gate.release()
```

**禁止**：
- `acquire` → `restore context` → `release` → 业务线程继续用 context。
- 跨 scenario 复用同一个 `BrowserContext`。

### 3.3 实现（复用现有 ConcurrencyGate，不新造）
- **不新造 `SsoUserSessionGate`**。复用已落地的 `ConcurrencyGate`（键 `ConcurrencyPartitionKey`）。
- 设计要点（沿用实际实现，优于 PoC 的 leaseId 版）：
  - `ThreadLocal<Deque<Key>>` 持有表 + 重入 no-op：根治"许可泄漏 → 整套 park 死"的实测死锁，且不依赖调用方记得 release。
  - `releaseAllForCurrentThread()` 泄漏兜底：scenario 收尾无条件释放。
  - 公平 `Semaphore(perKeyPermits)`：FIFO，避免同 user 长时饥饿。
  - fail-closed（默认）+ fail-open 逃生舱：超时默认 fail（避免整套卡死），逃生舱避免整套不可用。
  - `stats()` 观测：进入次数 / 被串行化身份数 / 活跃闸门数。
- `GATES` 是 JVM 内结构，**跨 fork 不共享**——跨 fork 靠 §5 分区。

### 3.4 维度扩展（多策略 / API 并发）
`ConcurrencyPartitionKey` 通过附加维度覆盖全部场景，不引入第二套闸门：
```
sessionkey    = env + "_" + username          // UI 登录身份（二维：无 entity、无"厂"）
apiclient     = <clientId>                     // API 凭证身份（新增，rest-assured / 服务令牌复用）
sessionPolicy = SINGLE | MULTI                 // 串行/并行策略（新增，默认 SINGLE）
tenant / role                                // 可选扩展维度
```
- `sessionPolicy` 解决"无 SSO 竞态"场景：服务端允许多并发会话时 `MULTI`（该身份 gate 退化为 `permits=N` 或 no-op），`fail-closed` 不得误杀并行场景；默认 `SINGLE`（账户级单会话，如 DBB）同 `sessionKey` 串行。
- `apiclient` 把 API 凭证纳入同一分区模型（维度含 `apiclient`/`apitoken`），按 `sessionPolicy` 串行/并行；route 捕获并发由 route 模块独立隔离，与 SSO gate 正交。

***

## 4. SessionStore 只缓存认证材料

### 4.1 存储对象
```
public final class SsoSessionEntry {
    String env;
    String user;
    Path storageState;
    String issuer;
    Instant authenticatedAt;
    Instant accessTokenExpiresAt;
    Instant refreshTokenExpiresAt;
    String sessionVersion;
}
```
**禁止**缓存：`BrowserContext` / `Page` / `Route` / `Request` / `Response` / `APIResponse`。

### 4.2 目录与命名
```
target/auth/
├─ uat__wp7uat2_1.json
├─ uat__wp7uat2_1.prev.json
├─ uat__wp7uat2_2.json
└─ uat__wp7uat2_2.prev.json
```
- 文件名 = `<env>__<user>.json`，唯一映射账号。
- **禁止** `current.json` 这种多线程多 fork 共享名。

### 4.3 原子写入（先备份 .prev 再覆盖）
```
// 1. 先把当前主文件（如果存在）备份成 prev —— prev 永远是"上一次成功的登录态"
if (Files.exists(base)) {
    Files.copy(base, prevBackup, StandardCopyOption.REPLACE_EXISTING);
}
// 2. 原子写入新内容
Path tmp = base.resolveSibling(base.getFileName() + ".tmp-" + UUID.randomUUID());
Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
Files.move(tmp, base,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
```
- 备份必须在覆盖主文件**之前**，否则 prev 备份的是新内容，失去回滚价值。
- 写完才可见，避免读到一半的 JSON。
- 保留 `.prev.json` 回滚位；主文件损坏/格式错时自动回退 `.prev`（**实现缺口 P0**：当前 `SessionStore.writeStorageStateAtomically` 仅做 `ATOMIC_MOVE`，需补回滚位）。

***

## 5. 跨 fork 账号分区（框架自动识别）

> 本章原设计稿用"厂/O63/O38/O88"描述账号分组。**修正**：O63/O38/O88 是 **env 前缀**，不是"厂"；本框架无工厂/租户分区概念。下文统一改为"env / 账号池"表述。

### 5.1 配置
```
# 登录环境
environment.O63_SIT1=https://www.qualityassurance-amh-gbb-sit.p2g.netd2.hsbc.com.hk/portalserver/logon
environment.O38_SIT2=https://qualityassurance-amh-gbb-sit2.p2g.netd2.hsbc.com.hk/portalserver/logon
environment.O88_SIT3=https://qualityassurance-amh-gbb-sit11.p2g.netd2.hsbc.com.hk/portalserver/logon

# 账号凭证（两种写法任选，框架自动探测）
# 写法 A：按 env 分组（多 env 时推荐）
userinfo_O63.WP7UAT2_2.password=b2g3ifd
userinfo_O63.WP7UAT2_2.profile=W1-HKHBAP001060698838PUSER1
userinfo_O63.WP7UAT2_2.token.security.url=https://.../getotp.htm?username=9900196198&type=1&key=...

# 写法 B：扁平（单 env 或不分组时直接用）
amhb2g0677_3.password=b2g3ifd
amhb2g0677_3.token.security.url=https://.../getotp.htm?username=9912644750&type=1&key=...
ABCDEW.password=123456
ABCDEW.profile=AABBCCDD
ABCDEW.token.security.url=http://localhost:8080/api/v1/token/security

# fork 切分
sso.fork.user.stride=4
```

**凭证查找顺序**（Given `logon DBB "O63_SIT1" as user "WP7UAT2_2"`，其中 `DBB` 为产品名、不参与键）：
| 优先级 | 查找键 | 说明 |
|---|---|---|
| 1 | `userinfo_O63.WP7UAT2_2.password` | env 前缀推导分组，先找分组下的凭证 |
| 2 | `WP7UAT2_2.password` | 分组没找到，退化为扁平按 user 名直接查 |
| 3 | 都没有 | fail fast，报"账号凭证未配置" |

> 框架不写死 `userinfo_<env>.<user>.<字段>` 前缀。配了 env 分组就走分组，没配就按 user 名直接查。env（O63/O38/O88）由 env 名前缀推导，仅用于凭证查找与账号池隔离，不强制配置键结构，不构成"厂"维度。

### 5.2 框架自动解析
Surefire 启动 fork 时自动往 JVM 注入系统属性 `surefire.forkNumber`（1-based）。框架读取并转 0-based：
```
public final class ForkAwareAccountResolver {

    /** Surefire 自动注入；非 Surefire（IDE 单跑）缺省为 1 → 0 */
    public static int currentForkIndex() {
        String override = System.getProperty("fork.index");   // 显式覆盖优先
        if (override != null && !override.isBlank()) {
            return Integer.parseInt(override.trim());
        }
        String raw = System.getProperty("surefire.forkNumber");
        int n = (raw == null || raw.isBlank()) ? 1 : Integer.parseInt(raw.trim());
        return n - 1;
    }

    public static List<String> myUsers(String env) {
        List<String> all = resolveList("sso.accounts." + env);
        int stride = config.getInt("sso.fork.user.stride", 4);
        int idx = currentForkIndex();
        int end = Math.min(all.size(), (idx + 1) * stride);
        return all.subList(idx * stride, end);
    }
}
```

### 5.3 兼容性
- **Surefire 多 fork**：自动按 `forkNumber` 切分，CI 只需配 `forkCount`，不用再传 `-Dfork.index`。
- **IDE 右键单跑**：`surefire.forkNumber` 不存在 → 缺省 `idx=0` → 用第 1 段账号。
- **显式覆盖**：需要时仍可 `-Dfork.index=2` 强制指定，调试方便。

### 5.4 两层防线
| 层                        | 解决                                   |
| ------------------------ | ------------------------------------ |
| stride 分区（§5）            | JVM **之间**不撞 user（框架自动识别 forkNumber） |
| `ConcurrencyGate`（§3） | JVM **之内**同 user 串行                  |

两者不可互相替代。

### 5.5 为什么是静态 stride，不是动态账号池
本框架的 Given 是钉死具体 user 的：
```
Given logon DBB "O63_SIT1" as user "WP7UAT2_2"
```
每个 user 的权限、角色、租户、测试数据都不一样——scenario 不能 "随便拿个空闲账号顶替"。因此：
- 动态账号池 / 文件锁跨进程抢占**不适用**：它解决的是 "谁抢到空闲账号"，而本场景下 user5 再空闲也不能顶替 user2（权限不同）。
- 静态 stride 分区可预测、零跨进程锁、无 stale lock 恢复问题，故障面小。
- 同 user 串行由 `ConcurrencyGate`（§3）保证，与分区方式正交。

**未来若出现 "scenario 只声明 `@Role("OPERATOR")`、账号可互换" 的用例，再评估引入动态池**；当前钉死 user 的模型下不做。

### 5.6 运行前静态账号扫描（企业级增强）
stride 是 "盲切"——不管 feature 实际用了哪些账号，按固定步长分。更好的做法是**开跑前先扫一遍所有 feature，摸清账号清单再分配**。

#### 5.6.1 账号清单来源
账号清单**由配置文件显式声明**（见 §5.1），不靠扫 feature 文件。框架启动时读 `sso.accounts.*`，构建内存中的账号清单：
```
public final record AccountRequirement(String env, String user) {}
public final class AccountManifest {
    // 启动时从 sso.accounts.<env>=user1,user2,... 加载
    public Set<AccountRequirement> allAccounts() { ... }
    public List<String> usersOf(String env) { ... }
}
```

#### 5.6.2 校验（fail fast，开跑前就报错）
| 检查 | 失败动作 |
| --- | --- |
| scenario 用了 `env/userX`，但 `sso.accounts.<env>` 配置里没有该 user | **直接 fail**，列出缺失账号和所属 scenario |
| 配置里有账号但没任何 scenario 用 | WARN（提示清理），不 fail |
| 同一 `(env, user)` 被 N 个 scenario 引用 | 记录，同 user 由 gate 串行 |
| 跨 env 同名 user | 视为不同账号，独立 gate，不报错 |

#### 5.6.3 分配策略
账号清单加载后，按 `sso.fork.user.stride` 切分给当前 fork（见 §5.2）。后续若需按 scenario 引用次数加权，再扩展为读取 scenario 元数据做负载均衡；当前 stride 已满足需求。

#### 5.6.4 Dry-run 报告
开跑前打印：
```
[SsoPlan] Scanned 12 feature files, 27 scenarios
[SsoPlan] Accounts used: 4
[SsoPlan]   uat/wp7uat2_1 (3 scenarios) → fork 1
[SsoPlan]   uat/wp7uat2_2 (8 scenarios) → fork 1
[SsoPlan]   uat/wp7uat2_5 (2 scenarios) → fork 2
[SsoPlan]   uat/wp7uat2_8 (1 scenarios) → fork 2
[SsoPlan] WARNING: configured account wp7uat2_3 is unused
[SsoPlan] Ready.
```

#### 5.6.5 与 stride 的关系
| 维度 | stride 盲切 | 静态扫描分配 |
| --- | --- | --- |
| 开跑前发现缺账号 | ❌ 跑到才报错 | ✅ 构建期 fail |
| 热点 user 均衡 | ❌ 不感知 | ✅ 加权分配 |
| 同 user 跨 fork 撞 | ❌ 可能撞 | ✅ 保证同 user 同 fork |
| 实现成本 | 低 | 中（一个扫描器 + 一个分配器） |

**落地顺序**：先上 stride（§5.1，零成本），PoC 跑通后再加静态扫描（§5.6）作为质量增强。

### 5.7 只跑部分账号时的并发
配置里有 O63/O38/O88 三个 env 的账号，但本次只跑 O63 的 case——此时 O38/O88 的账号不应参与分区，fork 数也只受 O63 可用账号数约束。

**做法：框架定义 SPI，业务层决定本次账号池**
框架不读 `-Dsso.run.factory` 这种业务参数，而是暴露一个 SPI 让业务项目自己实现：
```
// 框架侧定义
public interface RunScopeAccountProvider {
    /**
     * 本次运行涉及哪些账号。
     * 返回空 = 全量（业务层不限制）。
     */
    List<String> activeUsers(String env);
}

// 框架默认实现：返回全量（不裁剪）
public final class DefaultRunScopeProvider implements RunScopeAccountProvider { ... }
```
通过 SPI（`ServiceLoader`）或配置项 `sso.run.scope.provider=com.xxx.O63RunScopeProvider` 注入。

**关键约束**：
| 场景 | 行为 |
| --- | --- |
| 业务层只跑 O63 | 账号池 = `userinfo_O63.*`，fork 在这池子里切；O38/O88 不参与 |
| 跑 O63 + O38（跨 env 混合） | 账号池 = 两 env 并集，stride 跨 env 切；同一 user 仍 gate 串行 |
| 业务层不指定（SPI 返回空） | 全量账号池，按 env 分组各自 stride |
| forkCount > 本次账号数 | 多出的 fork 无账号可用 → WARN 并降并发，不报错 |

**并发上限自动收敛**：
```
本次有效并发 = min(
    forkCount × 每 fork 线程,
    本次运行涉及 env 的可用账号数,
    SSO 服务端单会话限制
)
```
例：O63 只有 4 个 user，配 `forkCount=4`，则实际有效并发 = 4（不是 4×2=8），多余 scenario 在 gate 排队。

***

## 6. SSO 失败三态（协议异常零容忍 Prevention-First）

```
public enum SsoSessionState { VALID, SUSPECT, INVALID }
```

### 6.1 零容忍原则（Prevention-First）
协议异常（`Object doesn't exist: response@…`、导航中断、`Connection.dispatch` 通用敞口）**本质都是通道对象所有权治理的漏洞**，不是可预期的运行时常态。治理目标不是"发生后再分类"，而是**从源端杜绝**：
- 严格单协调入口（`Browser` / `newContext()` 走串行协调入口）；
- 调用期对象（`Request`/`Response`/`Route`/`APIResponse`）不得跨线程逃逸、不得缓存到静态字段；
- Context/Page 销毁顺序受控，场景收尾由框架（`PlaywrightListener`）无条件清理；
- Route handler 不回调 Page 写方法、不跨线程发布对象（`RouteClaim` CAS 单所有者 + `BoundedOps` 有界 IO 已保证）。

> `Object doesn't exist: response@…` 的触发源（`context.onResponse` 订阅）**已在框架源端消除**（v2 `RouteRuntimeImpl` 移除订阅、v1 passthrough 默认 `false`），当前基本不再出现。

### 6.2 三态判定
| 错误 | 状态 | 动作 |
| --- | --- | --- |
| storageState 不存在 / 格式错 | INVALID | 重新登录 |
| access/refresh token 过期 | INVALID | 重新登录 |
| SSO 302 跳登录页 / 主动踢 | INVALID | 重新登录 |
| 401 / 服务端探针失败 | INVALID | 重新登录（真实会话失效） |
| 协议层 / 网络层异常（导航中断、`Connection.dispatch` 通用敞口、`response@` 残余） | **SUSPECT（仅兜底）** | 保留 storageState，标 scenario 失败，**不删缓存、不自动重登**；按框架缺陷暴露全量诊断（线程名 / context GUID / 对象 GUID / 调用栈），借 CI 通道压测收敛到 0 |

**铁律**：协议层异常 ≠ SSO 会话失效。禁止在 `Object doesn't exist` / 导航中断时 `authCache.delete()` + `reLogin()`。

### 6.3 验证门禁（CI 通道压测）
- CI 加入"故意高并行 + 故意慢 handler + 故意中断导航 + **故意启用 DELAY 能力（触发调度线程重入路径）**"的 torture 用例集，断言**运行期协议异常计数 == 0**；非零则**构建失败（fail-closed）**，并输出诊断（线程 / context GUID / 对象 GUID / 栈）定位所有权违规。DELAY 路径必须覆盖，因跨线程 `route` 驱动调用只在该路径暴露。
- SUSPECT 仅作为兜底安全网：即便发生，也不破坏 SSO 缓存、不重登，并计入框架质量信号推动治理收敛到零。

***

## 7. Refresh Token Rotation 专项
若 SSO 是 refresh token rotation 模式：
1. 刷新 token 后**立即**导出 storageState，不等 scenario 结束。
2. 刷新期间 gate 不释放，禁止其他线程 restore。
3. 刷新失败 → SUSPECT，不用旧 token 重试。
4. 一个 user 在跑时，后续请求**等待**，不新建第二个 Context。
5. 与 SSO 团队确认是否可给测试环境申请 rotation 豁免 / 专用测试客户端。

gate 只解决 "登录 /restore 串行"，**解决不了轮换窗口期**，必须配套上述策略。

***

## 8. Route Handler 规则

### 8.1 允许
- 同步读 `request().url() / method() / headers() / postData()`。
- `route.fetch()` 后立即复制 body 快照。
- 不可变快照 offer 给 scenario 的 recorder 队列。
- 立即 `fulfill / continue / abort / fallback` 之一。

### 8.2 禁止
- `Page.click / fill / navigate`。
- `context.newPage()`。
- 等业务线程锁 / 等 scenario 步骤完成。
- 把 `Request / Response / Route` 发布到其他线程。
- 把 `APIResponse` 缓存到静态字段。
- 在 handler 里访问 Serenity 当前 scenario /assert。

### 8.3 门禁
- ArchUnit 静态门禁：`route..` 包不得依赖 `..playwright.Page..` 的写方法（L6）。
- 运行时检测：handler 入口打印线程名 /threadId；CI 加故意慢 handler 用例，验证不阻塞页面；验证 handler 抛错不污染其他 scenario。
- **并发护栏（有效）**：`RouteClaim`（CAS 单所有者状态机，解决 `RouteImpl.handled` 非原子 TOCTOU——同一 `Route` 仅一个终结者）+ `BoundedOps`（有界同步 `fetch` 预算，防浏览器侧连接耗尽）。二者是 route 模块真实有效的并发护栏。
- **独立 IO / 调度线程不是安全保证，反而是风险源**：`RouteIoExecutor` 仅用于提交 DELAY 调度任务（随即返回），本身不操作 `route`；但 Playwright `Route`/`Request`/`Response` 与底层 `Connection` 的回调/对象表（HashMap）**非线程安全**，协议调用（`route.fulfill`/`resume`/`fetch`）必须落在**单一拥有线程**。`RouteDelayScheduler` 的延迟回调在默认"直发"模式下落在**调度线程**（非拥有线程），构成跨线程驱动连接的竞态窗口——正是 §2 铁律与 §6 零容忍要杜绝的协议异常来源。**修复（强制）**：DELAY 到点回调必须经拥有线程重入（`setDelayDeliveryMainQueue(true)`，把 `fulfill/resume` 入队由拥有线程 `drainMainThreadActions` 执行），**禁止**在独立 IO / 调度线程上直接操作 `route`；启用该模式的前提是用 DELAY 的 scenario 改用 `RouteDsl.waitForUiFunction`（不能用原生 `page.waitForFunction`，否则 main 阻塞无人 drain → 15s 超时）。
- route 并发隔离与 SSO 登录并发隔离**正交**（互不重叠）。

### 8.4 优化项：消除调度线程对 `route` 的驱动（单一拥有线程）
**目标**：把"调度线程（RouteDelayScheduler）/ 独立 IO 线程直接操作 `route`"彻底消除，让 `route` 的协议调用（`fulfill`/`resume`/`fetch`）只落在**单一拥有线程**，闭合 §2 铁律与 §6 零容忍。

**更好设计（已采纳）**：
1. **`RouteDelayScheduler` 降级为纯计时线程**：到点回调里**只**做 `runtime.enqueueMainAction(deliver)`，绝不调用 `route.fulfill/resume/fetch`。计时仍走模块自有 daemon 池（有界、不污染 `ForkJoinPool.commonPool`），但角色从"驱动线程"变为"计时线程"——这正是"消除调度线程（作为 `route` 操作者）"的本意。
2. **DELAY 交付一律重入拥有线程**：入队的 `deliver` 由 **main 业务线程** `drainMainThreadActions()` 串行执行（`ResumeOptions/fulfillWithBody` 均为 sendMessageAsync，零泵连接）。drain 触发点已覆盖：step 边界（`PlaywrightListener` 每步后）、断言/采集查询、注册前、context 收尾 close——协议调用 100% 收敛到 main，与 `RouteRuntimeImpl` 既定原则一致。
3. **移除"直发"模式**：`delayDeliveryMainQueue` 开关（及其"在调度线程直接 resume"的默认分支）是多余且违规的**第三写入面**，与零容忍矛盾，永久删除；DELAY 交付不再有"落在调度线程"的路径。
4. **非-DELAY 路径（本项范围外）**：`fulfill/resume` 仍落在 Playwright 事件/pump 线程（其天然拥有者——`route` 由该线程送达，属合法单一拥有者），本项不动。其与 main 线程的"理论双写入面"作为**后续统一项**单列（全量 main 拥有线程需配套 `RouteDsl.waitForUiFunction` 迁移，风险大，不随本项实施）。
5. **前提契约（硬约束）**：使用 DELAY 的 scenario，其 UI 等待**必须**走 `RouteDsl.waitForUiFunction`（内部循环 drain + 短窗 `page.waitForFunction`），**禁止**原生 `page.waitForFunction`——否则 main 阻塞在等待中无人 drain，延迟交付卡到 15s 超时（run-b 实证）。框架可加 ArchUnit / 运行期 guard 拦截原生 `waitForFunction` + 活跃 route 的组合。

**验证**：§6.3 torture 已含 DELAY 路径；`DelayAppliesWaitTest#mainQueueModeDeliversOnlyAfterMainDrain` 已验证"交付只在 main drain 后发生"。

***

## 9. 与现有代码的接线（框架接入点为唯一真相）

### 9.1 框架接入点（业务层禁止接线）
**修正原设计稿反模式**：原稿把 gate 提到业务层 `SsoScenarioHooks`（`@Before` acquire / `@After` release）。这与框架"业务层禁止接线"决策冲突——业务自建分区键会形成"两把键不同的锁"且依赖业务 `@After` 释放，漏跑即泄漏 → 同身份后续场景卡到 fail-closed 超时。

**实际接入点（唯一真相）**：
- UI：`SessionStore.acquireSessionGate(sessionKey)` 覆盖 restore/save 入口，释放由 `PlaywrightListener.cleanupThreadLocals()` → `SessionStore.releaseSessionGate()` + `ConcurrencyGate.releaseAllForCurrentThread()` **无条件**执行。
- API：在 API 会话/凭证获取路径（如 `ApiTestContext` 绑定客户端时）复用 `ConcurrencyGate.acquire(ConcurrencyPartitionKey.of(Map.of("apiclient", ...)))`，释放同样由框架收尾。
- **业务测试层禁止自建分区键 acquire/release**（铁律）。

### 9.2 `SessionStore`
- 移除对 `BrowserContext` / `Page` 的缓存。
- 保留 `SsoSessionEntry`（storageState 路径 + token 元数据）。
- 落盘写走 §4.3 原子写（补 `.prev` 回滚位，见 §4.3 实现缺口）。

### 9.3 `BDDUtils.getDbbLoginInfo`
- `Given logon DBB "O63_SIT1" as user "WP7UAT2_2"` 中 `DBB` 为产品名，解析时忽略；键只取 `env` + `user`。
- 增加 `resolveUsername(env, base, forkIndex)`：从 `-Dfork.index` 和 `sso.fork.user.stride` 拼出当前 fork 可用 user 列表。
- `@User("WP7UAT2_2")` 或 `Given` 里写死的 user 必须在当前 fork 的 `mine` 列表里，否则 fail fast。

### 9.4 `FailureScreenshotHandler`
- 当前 URL 命中 `/logon` 时跳过截图或对密码框坐标打码（位图脱敏，文本脱敏器管不到）。

### 9.5 Surefire pom
```
<forkCount>2</forkCount>
<reuseForks>true</reuseForks>
<argLine>... -Dfork.index=${fork.index} -Dsso.fork.user.stride=4 ...</argLine>
```
CI matrix 注入 `fork.index=0 / 1`。

***

## 10. 分阶段上线
每阶段连续 20 次稳定运行，失败回退上一档，**不跳档**。
| 阶段 | fork | 每 fork 线程 | 总并发 | 验证重点 |
| -- | ---- | --------- | --- | --- |
| 0 | 1 | 1 | 1 | 单线程回归全绿，确认 gate 改造无行为变化 |
| 1 | 1 | 1 | 1 | gate / 原子写 / 三态基础 |
| 2 | 1 | 2 | 2 | 同 user gate 排队、不同 user 并行 |
| 3 | 2 | 1 | 2 | stride 分区不撞 user |
| 4 | 2 | 2 | 4 | 跨 JVM + 跨线程 |
| 5 | 4 | 4 | 16 | 容量压测（需 ≥16 个独立账号） |

> 前置约束：总并发上限 = `min(fork × 每 fork 线程, 可用 SSO 账号数)`。账号不足时，多出的 scenario 在 gate 排队而非并行。
> 当前 `parallel` profile 默认关闭（`-Pparallel` 才开），故阶段 0~2（单 JVM 串行/进程内并行）可立即验证；阶段 3~5（跨 fork）需 `forkCount>1` 才成立。

并发自检：
```
int active = COUNTER.incrementAndGet();
if (active > expectedMax) throw new IllegalStateException(
    "actual concurrency " + active + " > expected " + expectedMax);
// 每条日志打: pid / fork.index / threadName / scenarioId / user / browserGuid / contextGuid
```

***

## 11. 验收标准
| # | 场景 | 预期 |
| -- | --- | --- |
| 1 | 同 user 两 scenario 并发 | 第二个在 gate 等待 |
| 2 | 同 user 顺序执行 | 每次新 Context，复用 storageState |
| 3 | 不同 user 并发 | 完全并行 |
| 4 | 单 JVM 同 user 双 Context | 不允许出现 |
| 5 | 跨 fork 同 user | stride 分区，零冲突 |
| 6 | 协议异常 | **零容忍**：CI 通道压测断言运行期零协议异常；残余仅 SUSPECT 兜底，不删 SSO 缓存、不重登 |
| 7 | SSO 主动踢 | 仅独立证据触发重登 |
| 8 | token rotation | 刷新即导出；旧 token 不再用 |
| 9 | scenario 失败 | gate 释放、Context 关闭 |
| 10 | handler 抛错 | 不影响其他 scenario |
| 11 | `Object doesn't exist` | 已源端消除；残余按 §6.2 SUSPECT 兜底、不重登、不污染 |
| 12 | 连续 20 次 | 零互踢、零误重登、零残留进程 |
| 13 | 线程数提升 | 逐档验证 |
| 14 | handler 访问 Page | ArchUnit + 运行时检测双失败 |
| 15 | storageState 写 | 原子 move + prev 备份 |
| 16 | MULTI 策略下并行 | 无互踢（服务端真单会话则需改 SINGLE） |
| 17 | API 凭证并发 | 无令牌失效（apiclient 维度分区） |

***

## 12. 不做的事
- 不为 Browser 加 owner 线程（当前版本无证据需要）。
- 不在多线程间共享 `BrowserContext` / `Page` / 调用期对象。
- 不让 "未声明 user" 的 scenario 绕过 gate。
- 不用全局数据库锁跨 fork 协调账号。
- 不在 `current.json` 这种共享文件名上写 storageState。
- 不在协议异常时自动重登。
- **不在业务层自建分区键 / `@Before/@After` 接线**（以 `SessionStore` 框架接入点为唯一真相）。
- **不新造第二套闸门**（`SsoUserSessionGate`）；复用 `ConcurrencyGate`。

***

## 13. 一句话总结
> 同一账号（`env+user`，无 entity/无厂）的整个会话生命周期（gate acquire → newContext → scenario → storageState 导出 → context.close → release）完全互斥；不同账号并行；跨 fork 靠 stride 分区；**协议异常零容忍（Prevention-First）—— 源端治理杜绝通道对象所有权漏洞，CI 通道压测断言运行期零协议异常，残余仅作 SUSPECT 兜底且绝不破坏 SSO 缓存**；每 scenario 新建 Context，只复用认证材料；复用唯一 `ConcurrencyGate`，不新造第二套闸门、不在业务层接线。

***

## 14. 并发上限公式
```
最大有效并发 = min(
    Surefire forkCount × 每 fork scenario 线程数,
    可用 SSO 账号数,
    SSO 服务端允许的单用户并发会话数（单会话模式下 = 可用账号数）,
    浏览器进程可承载的 Context 数,
    CI 机器 CPU / 内存容量
)
```
账号不足时，多出的 scenario 在 gate 排队，不会并行 —— 这是设计行为，不是 bug。

***

## 15. 故障诊断速查表
| 现象 | 排查方向 |
| --- | --- |
| 测试卡住不动 | 是否所有账号都被别的 fork 占用，排队死等 |
| 偶发 `Object doesn't exist` | 协议异常；应已源端消除，残余按 §6.2 SUSPECT 兜底、不重登，并查 CI 通道压测是否归零 |
| 两个同 user 同时登录 | gate 未生效 / 跨 fork 未分区 |
| 一个 user 被踢后反复重登 | 三态判定未生效，SUSPECT 被当成 INVALID |
| storageState 读到旧 token | 原子写顺序错误（应先备份再覆盖主文件） |
| 单线程正常、多线程 401 | 同 user 两个 Context 同时持有 SSO 会话 |
| `.prev.json` 备份的是新内容 | 备份顺序写反 |
| scenario 失败后下一个 user 拿不到 gate | 框架收尾未兜底 release（应在 `PlaywrightListener` finally 释放） |
| 业务层自建 gate 卡死 | 违反"业务层禁止接线"——改经 `SessionStore` 框架接入点 |
