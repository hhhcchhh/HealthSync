# HealthSync 设计文档（docs/DESIGN.md）

> 目标：以**数据层架构**为核心，完成"多数据源 + 离线优先 + 可恢复同步 + 冲突处理 + 可观测状态"的最小闭环；UI 用 Jetpack Compose 验证完整链路。

---

## 1. 背景与范围

### 1.1 背景
- 产品：智能手环 + 手机 App，同步心率/步数/睡眠数据。
- 本项目：不接真实蓝牙，使用模拟数据源；云端使用 mock REST API。

### 1.2 目标（Goals）
- **可扩展数据源**：新增真实蓝牙/Health Connect 数据源时，不修改同步引擎核心代码（只新增实现 + DI 注册）。
- **离线优先**：所有数据先写入 Room；网络同步异步进行。
- **可恢复同步**：同步中杀进程/重启后，可恢复未完成任务。
- **同步状态可见**：每条数据有状态（本地/同步中/已同步/失败/冲突），UI 可汇总待同步数量。
- **冲突可处理**：实现并记录冲突策略（睡眠记录离线修改导致冲突）。
- **可测试**：数据层核心逻辑单元测试覆盖率 > 60%（重试/状态机/冲突/并发写入）。

### 1.3 非目标（Non-Goals）
- 不实现真实蓝牙协议、真实云端、账号体系。
- 不追求 UI 视觉完美；优先证明数据链路与可靠性。

---

## 2. 架构总览

### 2.1 分层与职责
- **UI（Jetpack Compose）**：只渲染 `ViewModel` 暴露的 `StateFlow`；触发"下拉刷新同步"等意图。
- **ViewModel（MVVM）**：聚合多个 Flow 为 UI 状态；发起同步/录入睡眠等 UseCase。
- **Domain（UseCases）**：定义业务入口（开始/停止数据源、触发同步、保存睡眠、订阅汇总指标）。
- **Data Layer**：
  - `DataSource`：统一抽象"数据产生"，提供数据流与连接状态流。
  - `Repository`：统一入口，写入 Room、读取 Flow、聚合统计（待同步数量等）。
  - `SyncEngine`：扫描 outbox/待同步记录；上传 mock API；指数退避重试；冲突处理；恢复策略。
  - `Room`：事实来源（source of truth），承载状态机字段与重试计划字段。
  - `CloudApi`：mock REST，模拟网络延迟与失败。
- **DI（Hilt）**：绑定接口到实现；新增数据源只需新增 `@Binds` / `@Provides` 注册。

### 2.2 依赖关系图（Mermaid）
```mermaid
flowchart TD
  UI[Compose_UI] --> VM[ViewModels]
  VM --> UC[UseCases]
  UC --> Repo[HealthRepository]

  Repo --> DB[(Room_Database)]
  Repo --> DS[DataSources]
  Repo --> Sync[SyncEngine]

  DS --> Sim[SimulatedBluetoothSource]
  DS --> Manual[ManualInputSource]

  Sync --> DB
  Sync --> Api[MockCloudApi]
  Sync --> Conflict[ConflictResolver]
  Sync --> Retry[RetryPolicy]

  WM[WorkManager] --> Sync
```

---

## 3. 关键设计决策

> 写作模板：**备选方案** → **为什么选择** → **trade-off**。

### 3.1 决策 1：Room 作为事实来源（离线优先）
- **备选**：
  - A. 内存缓存为主，Room 只做持久化镜像
  - B. 网络直写 + 本地回填
- **选择**：Room 为唯一事实来源，任何数据先落库；同步引擎只从 Room 扫描待同步数据。
- **trade-off**：需要更多表字段与状态维护，写入路径必须稳定可靠；但换来完整的离线能力和可恢复性——进程被杀后数据不丢失，重启即可继续同步。

### 3.2 决策 2：Outbox + 状态机驱动同步（而非 UI/VM 直接调 API）
- **备选**：
  - A. ViewModel 触发上传并在内存持有队列
  - B. 仅依赖 WorkManager 一次性任务，无本地状态机
- **选择**：在 Room 持久化 `syncState / attemptCount / nextAttemptAt / lastError`，同步引擎周期扫描并推进状态。
- **trade-off**：实现更复杂（需维护状态转换与事务），但带来三大收益：**可恢复**（进程重启不丢任务）、**可观测**（UI 直接查询同步状态）、**可测试**（状态机的每个转换都是确定性的）。

### 3.3 决策 3：冲突策略 = "保留双方并标记冲突"
- **备选**：
  - A. LWW（Last Write Wins）——客户端优先或服务端优先
  - B. 字段级三方合并（类似 git merge）
- **选择**：遇到冲突时不丢弃任何一方数据，将记录置为 `CONFLICT`，同时存储服务端快照（`serverSnapshot` 字段或单独表），由用户/后续逻辑决定如何解决。
- **trade-off**：需要额外的 `CONFLICT` 状态与解决入口（即使本项目不做完整 UI，也要有 UseCase 支持解除冲突）；但**不丢数据**是离线优先系统最重要的保证，LWW 在睡眠记录场景会静默覆盖用户手动修改，体验不可接受。

### 3.4 决策 4：选择 Hilt 而非 Koin 作为 DI 框架
- **备选**：
  - A. Hilt（基于 Dagger，编译时生成代码）
  - B. Koin（纯 Kotlin DSL，运行时解析）
- **选择**：Hilt。
- **理由**：
  - Hilt 是 Android Jetpack 官方推荐的 DI 方案，与 ViewModel、WorkManager 等组件有原生集成（`@HiltViewModel`、`@HiltWorker`）
  - 编译时校验依赖图，缺少绑定会在编译期报错而非运行时崩溃——对本项目多接口/多实现的数据源体系尤为重要
  - `@InstallIn` 的 scope 机制天然匹配 Android 组件生命周期
- **trade-off**：编译速度略慢于 Koin；Dagger 注解学习曲线较陡；但本项目规模可控，编译开销可接受。

---

## 4. 数据源抽象（可扩展）

### 4.1 统一接口

```kotlin
interface HealthDataSource {
    val dataEvents: Flow<HealthEvent>
    val connectionState: Flow<ConnectionState>
    suspend fun start()
    suspend fun stop()
}

enum class ConnectionState {
    CONNECTED, DISCONNECTED, RECONNECTING
}
```

### 4.2 HealthEvent 事件模型（sealed class）

```kotlin
sealed class HealthEvent {
    data class HeartRateSample(
        val timestamp: Long,
        val bpm: Int,
        val sourceId: String
    ) : HealthEvent()

    data class StepCountIncrement(
        val timestamp: Long,
        val steps: Int,
        val sourceId: String
    ) : HealthEvent()

    data class SleepRecord(
        val id: String,
        val startTime: Long,
        val endTime: Long,
        val quality: SleepQuality,
        val sourceId: String
    ) : HealthEvent()
}

enum class SleepQuality { POOR, FAIR, GOOD, EXCELLENT }
```

所有数据源产生的事件统一为 `HealthEvent` 子类型，Repository 负责将其分发到对应的 DAO 写入。未来新增数据类型（如血氧）只需：新增 `HealthEvent` 子类 + 对应 Entity + DAO，不修改 `HealthDataSource` 接口。

### 4.3 数据源实现
- **SimulatedBluetoothSource**
  - 每 2 秒：发出 `HeartRateSample(bpm = 60..120 随机)`
  - 每 30 秒：发出 `StepCountIncrement(steps = 1..20 随机)`
  - 支持模拟断连：内部 `connectionState` 流切换为 `DISCONNECTED`，暂停数据产生；一段时间后自动切回 `RECONNECTING → CONNECTED` 恢复产生
- **ManualInputSource**
  - 用户录入/修改睡眠记录（新增、编辑都走同一写入通道）
  - `connectionState` 始终为 `CONNECTED`（手动输入不依赖外部连接）
  - 必须能在离线状态下修改"已同步"的记录，以触发冲突链路

---

## 5. 本地数据模型与同步状态机

### 5.1 同步状态枚举

```kotlin
enum class SyncState {
    LOCAL_PENDING,  // 本地新增/修改，待同步
    SYNCING,        // 同步引擎已抢占处理中
    SYNCED,         // 已与云端一致
    SYNC_FAILED,    // 超过最大重试次数
    CONFLICT        // 检测到冲突，保留双方版本等待处理
}
```

状态转换图：

```mermaid
stateDiagram-v2
    [*] --> LOCAL_PENDING: 数据写入
    LOCAL_PENDING --> SYNCING: SyncEngine抢占
    SYNCING --> SYNCED: 上传成功
    SYNCING --> LOCAL_PENDING: 上传失败且未超限
    SYNCING --> SYNC_FAILED: 超过3次重试
    SYNCING --> CONFLICT: 检测到版本冲突
    SYNCING --> LOCAL_PENDING: 进程恢复重置
    SYNCED --> LOCAL_PENDING: 用户离线编辑
    CONFLICT --> LOCAL_PENDING: 用户解决冲突
    SYNC_FAILED --> LOCAL_PENDING: 用户手动重试
```

### 5.2 核心 Entity 定义

#### HeartRateEntity

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long (PK, autoGenerate) | 本地主键 |
| `timestamp` | Long | 采样时间戳（ms） |
| `bpm` | Int | 心率值 |
| `sourceId` | String | 数据源标识 |
| `syncState` | SyncState | 同步状态 |
| `attemptCount` | Int | 已重试次数（从 0 开始） |
| `nextAttemptAt` | Long | 下次允许重试的时间戳 |
| `lastError` | String? | 最近一次同步错误信息 |
| `remoteId` | String? | 云端记录 ID（同步成功后回写） |

#### StepCountEntity

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long (PK, autoGenerate) | 本地主键 |
| `timestamp` | Long | 记录时间戳 |
| `steps` | Int | 步数增量 |
| `sourceId` | String | 数据源标识 |
| `syncState` | SyncState | 同步状态 |
| `attemptCount` | Int | 已重试次数 |
| `nextAttemptAt` | Long | 下次允许重试的时间戳 |
| `lastError` | String? | 最近一次同步错误信息 |
| `remoteId` | String? | 云端记录 ID |

#### SleepRecordEntity

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String (PK) | 业务主键（UUID，保证离线创建不冲突） |
| `startTime` | Long | 睡眠开始时间 |
| `endTime` | Long | 睡眠结束时间 |
| `quality` | SleepQuality | 睡眠质量 |
| `sourceId` | String | 数据源标识 |
| `syncState` | SyncState | 同步状态 |
| `attemptCount` | Int | 已重试次数 |
| `nextAttemptAt` | Long | 下次允许重试的时间戳 |
| `lastError` | String? | 最近一次同步错误信息 |
| `remoteId` | String? | 云端记录 ID |
| `remoteVersion` | Int | 上次同步时的云端版本号 |
| `baseRemoteVersion` | Int | 本次编辑基于的云端版本号（用于冲突检测） |
| `localVersion` | Int | 本地编辑版本号（每次编辑 +1） |
| `serverSnapshot` | String? | 冲突时存储的服务端数据 JSON 快照 |

> 设计说明：心率和步数为只增数据（append-only），不存在用户编辑场景，因此不需要 `localVersion / baseRemoteVersion / serverSnapshot` 等冲突字段；睡眠记录作为可编辑数据，需要完整的冲突检测字段。SleepRecordEntity 使用 UUID 作为主键，避免离线创建时的主键冲突。

### 5.3 并发写入保证
原则：**所有写入通过 Repository/DAO，避免 UI 或数据源直接操作数据库**。
- Room 本身线程安全（底层 SQLite WAL 模式），但需要：
  - 合理主键策略（心率/步数用 autoGenerate，睡眠用 UUID）
  - 同步引擎"抢占任务"时使用 `@Transaction`，保证 `SELECT → UPDATE syncState` 的原子性
  - 两个数据源同时写入不同表时互不阻塞；写入同一表时 Room/SQLite 串行化保证不丢数据

---

## 6. 同步引擎（SyncEngine）

### 6.1 触发方式

采用 **WorkManager 周期任务 + 手动触发** 双轨机制：

- **WorkManager PeriodicWorkRequest**：每 15 分钟（WorkManager 最小周期）执行一次同步扫描。使用 `@HiltWorker` 注入依赖。即使 App 被杀，系统也会按计划唤起 Worker 执行同步。
- **App 内协程轮询**：App 在前台时，`SyncEngine` 通过 `applicationScope` 启动协程，每 30 秒扫描一次待同步记录，提供比 WorkManager 更及时的同步体验。
- **手动刷新**：历史页下拉刷新、UI 按钮触发一次即时同步。
- **启动恢复**：Application.onCreate 时调用 `SyncEngine.recover()`，将停留在 `SYNCING` 状态的记录重置为 `LOCAL_PENDING`（保留 `attemptCount`），然后立即触发一轮同步扫描。

> 为什么双轨：WorkManager 保证进程被杀后仍能恢复同步，但最小周期 15 分钟对前台实时体验不够；协程轮询在前台提供快速响应，两者互补。

### 6.2 同步流程（高层）
1. 查询待同步记录：`syncState in (LOCAL_PENDING, SYNC_FAILED)` 且 `nextAttemptAt <= now`
2. 抢占任务：`@Transaction` 内将记录标记为 `SYNCING`（避免 WorkManager 和协程轮询重复上传同一条）
3. 调用 `MockCloudApi` 上传（模拟 delay 与失败）
4. 成功：写回 `SYNCED` + `remoteId` + 清理错误字段
5. 冲突（HTTP 409）：进入冲突处理流程（见 Section 7）
6. 其他失败：计算指数退避，更新 `attemptCount / nextAttemptAt / lastError`
7. 超过 3 次：置为 `SYNC_FAILED`

### 6.3 指数退避策略（RetryPolicy）

```kotlin
class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 2_000L,
    val maxDelayMs: Long = 30_000L
) {
    fun nextDelay(attemptCount: Int): Long {
        val delay = baseDelayMs * (1L shl attemptCount) // 2^n
        val jitter = (delay * 0.1 * Random.nextDouble()).toLong()
        return (delay + jitter).coerceAtMost(maxDelayMs)
    }

    fun shouldRetry(attemptCount: Int): Boolean = attemptCount < maxAttempts
}
```

- 第 1 次失败后等待 ~2s，第 2 次 ~4s，第 3 次超限置为 `SYNC_FAILED`
- 加入 10% jitter 避免多条记录同时重试产生"惊群"

### 6.4 杀进程恢复策略

启动恢复时处理 `SYNCING` 状态的记录：
- **策略**：`Application.onCreate` → `SyncEngine.recover()` 将所有 `syncState == SYNCING` 的记录重置为 `LOCAL_PENDING`，保留 `attemptCount`。
- **原因**：应用被杀时无法确认上传是否已到达服务端；回到 `LOCAL_PENDING` 让同步引擎重新推进。配合 `remoteId` 幂等性，即使服务端已收到，重复上传也不会产生副作用。
- **WorkManager 兜底**：即使 App 未被用户重新打开，WorkManager 的周期任务也会执行恢复 + 同步。

---

## 7. 冲突检测与解决（睡眠记录）

### 7.1 冲突检测机制

使用版本号方案：
- 本地：每次编辑时 `localVersion++`，并记录 `baseRemoteVersion`（编辑时看到的云端版本）
- 云端：维护 `remoteVersion`，每次成功写入 +1
- 同步上传时，请求携带 `baseRemoteVersion`；若服务端发现 `baseRemoteVersion < currentRemoteVersion`，返回 HTTP 409 + 服务端当前数据

### 7.2 解决策略：保留双方并标记冲突

同步时收到 409 响应：
1. 将该条记录 `syncState` 置为 `CONFLICT`
2. 将 409 响应体中的服务端数据 JSON 存入 `serverSnapshot` 字段
3. DAO 通过 `Flow<List<SleepRecordEntity>>` 将冲突记录推送到 UI 层

后续解决（UseCase 层提供）：
- `resolveConflict(id, resolution: ConflictResolution)` —— resolution 可选：`KEEP_LOCAL`、`KEEP_REMOTE`、`MERGE`
- 解决后将记录置回 `LOCAL_PENDING`（采用本地/合并）或直接 `SYNCED`（采用云端）

### 7.3 为什么选这个策略
- **优点**：不丢数据，可审计，适合离线编辑场景；用户对睡眠记录的手动修改是有意识行为，静默覆盖（LWW）会损害用户信任
- **缺点**：需要额外状态与解决入口，实现与测试更复杂
- **对比 LWW**：LWW 实现简单但会静默丢弃一方修改；对于心率/步数这类 append-only 数据可以用 LWW（实际上不会冲突），但睡眠记录必须用更安全的策略

---

## 8. Mock Cloud API 合约

### 8.1 Endpoint 定义

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/heart-rates` | 批量上传心率数据 |
| POST | `/api/step-counts` | 批量上传步数数据 |
| PUT | `/api/sleep-records/{id}` | 上传/更新单条睡眠记录 |
| GET | `/api/sleep-records/{id}` | 获取单条睡眠记录（冲突时用） |

### 8.2 请求/响应示例（睡眠记录）

**正常上传**：

```
PUT /api/sleep-records/uuid-123
Request:
{
  "startTime": 1700000000000,
  "endTime": 1700028800000,
  "quality": "GOOD",
  "baseRemoteVersion": 2
}

Response 200:
{
  "remoteId": "uuid-123",
  "remoteVersion": 3
}
```

**版本冲突**：

```
Response 409:
{
  "error": "VERSION_CONFLICT",
  "currentRemoteVersion": 4,
  "serverData": {
    "startTime": 1700000000000,
    "endTime": 1700025200000,
    "quality": "FAIR",
    "remoteVersion": 4
  }
}
```

### 8.3 Mock 实现

`MockCloudApi` 使用内存 Map 模拟服务端存储，通过 `delay()` 模拟网络延迟（200-1000ms 随机），通过可配置的失败率模拟网络错误。冲突检测逻辑在 mock 内部实现，比对请求中的 `baseRemoteVersion` 与内存中的 `currentRemoteVersion`。

---

## 9. 端到端数据流（心率样本）

### 9.1 文字描述

1. **产生**：`SimulatedBluetoothSource` 每 2 秒通过 `dataEvents: Flow` 发出 `HealthEvent.HeartRateSample(timestamp, bpm, sourceId)`
2. **落库**：`HealthRepository` 收集事件流，将 `HeartRateSample` 转换为 `HeartRateEntity(syncState = LOCAL_PENDING, attemptCount = 0)` 并通过 `HeartRateDao.insert()` 写入 Room
3. **展示**：
   - `HeartRateDao.getRecentFlow(since: Long): Flow<List<HeartRateEntity>>` 提供最近 5 分钟的响应式数据流
   - `HeartRateViewModel` 将 DAO Flow 映射为 `UiState`（当前心率值 + 图表数据点列表 + 是否异常）
   - Compose UI 订阅 `StateFlow` 实时重组：大字体显示当前 BPM，Canvas 绘制折线图，异常时变色提示
4. **同步**：
   - `SyncEngine` 扫描 `syncState == LOCAL_PENDING` 且 `nextAttemptAt <= now` 的心率记录
   - 批量调用 `MockCloudApi.uploadHeartRates(batch)` 上传
   - 成功：写回 `SYNCED` + `remoteId`
   - 失败：按 `RetryPolicy` 计算退避并更新字段

### 9.2 序列图

```mermaid
sequenceDiagram
    participant BT as SimulatedBTSource
    participant Repo as HealthRepository
    participant DB as Room_Database
    participant VM as HeartRateViewModel
    participant UI as Compose_UI
    participant Sync as SyncEngine
    participant API as MockCloudApi

    BT->>Repo: emit HeartRateSample via Flow
    Repo->>DB: insert HeartRateEntity(LOCAL_PENDING)
    DB-->>VM: Flow emit 新记录
    VM-->>UI: StateFlow update(当前BPM + 图表点)
    UI->>UI: recompose 显示心率

    Note over Sync: 定时扫描(30s) 或 WorkManager触发
    Sync->>DB: query LOCAL_PENDING records
    Sync->>DB: @Transaction 标记 SYNCING
    Sync->>API: uploadHeartRates(batch)
    alt 上传成功
        API-->>Sync: 200 + remoteIds
        Sync->>DB: update SYNCED + remoteId
    else 上传失败
        API-->>Sync: error
        Sync->>DB: update attemptCount, nextAttemptAt
    end
    DB-->>VM: Flow emit 状态变更
    VM-->>UI: 更新同步状态汇总
```

---

## 10. UI 设计概要（Jetpack Compose）

### 10.1 实时心率面板

- **当前心率**：大字号 `Text`（48sp+）居中显示 BPM 数值
- **异常提示**：当 BPM > 100 或 < 60 时，数值颜色切换为红色/蓝色，并在下方显示警告文本；使用 `animateColorAsState` 实现平滑过渡
- **5 分钟折线图**：自定义 `Canvas` 绘制，不使用第三方图表库
  - X 轴：时间（最近 5 分钟，每 2 秒一个采样点，最多 150 点）
  - Y 轴：BPM（固定范围 40-160，或动态适应）
  - 绘制方式：`drawPath` 连接数据点，`drawCircle` 标记最新点
  - 异常区间（>100 / <60）用半透明背景色标记
  - 新数据到达时自动滚动/平移，使用 `Animatable` 做平滑位移

### 10.2 今日概览卡片

- **步数环形进度条**：自定义 `Canvas` 使用 `drawArc` 绘制，目标 10000 步，中心显示当前步数/目标文本
- **睡眠时长**：最近一条 `SleepRecordEntity` 的 `endTime - startTime` 格式化为 "Xh Ym"
- **同步状态汇总**：查询 `syncState in (LOCAL_PENDING, SYNCING, SYNC_FAILED)` 的总条数，显示"X 条待同步"；全部 `SYNCED` 时显示对号图标

### 10.3 数据历史页

- **按天分组**：ViewModel 将数据按 `timestamp` 的日期分组，产出 `Map<LocalDate, List<HealthRecord>>`
- **LazyColumn 性能优化**：
  - 使用 `key = { record.id }` 为每个 item 提供稳定唯一 key，避免不必要的重组
  - 日期分组 header 使用 `stickyHeader` + `key = { date.toString() }`
  - 使用 `contentType` 区分 header 和 item，提高 View 复用率
- **下拉刷新**：使用 Material3 `PullToRefreshBox` 组件，触发 `SyncEngine.syncNow()` 一次即时同步

---

## 11. 异常场景处理（隐藏考点）

- **数据源断连（蓝牙断连模拟）**：
  - `HealthDataSource.connectionState` 流发出 `DISCONNECTED`
  - ViewModel 监听连接状态，UI 显示 Snackbar/Banner 提示"设备已断开"
  - App 继续展示 Room 中的历史数据，不崩溃、不白屏
  - `SimulatedBluetoothSource` 内部可自动尝试重连（延迟后切回 `CONNECTED`）
- **同步中杀进程**：
  - `SYNCING` 状态记录通过 `SyncEngine.recover()` 在启动时重置
  - 重试计划（`attemptCount / nextAttemptAt`）持久化在 Room，不因进程死亡丢失
  - WorkManager 作为兜底，即使用户不重新打开 App 也会恢复执行
- **并发写入**：
  - 两个数据源同时写入不同表（心率/步数）：Room WAL 模式下读写并发无阻塞
  - 同步引擎抢占任务时使用 `@Transaction` 保证原子性，避免两个 Worker/协程同时处理同一条记录
  - 不使用 `allowMainThreadQueries`，所有 DAO 操作均在 `Dispatchers.IO` 上执行

---

## 12. 测试策略（数据层核心逻辑）

### 12.1 必测用例

| 测试目标 | 测试内容 | 验证点 |
|---|---|---|
| RetryPolicy | 指数退避计算 | delay 值符合 `baseDelay * 2^n` + jitter 在范围内 |
| RetryPolicy | 最大重试限制 | `attemptCount >= 3` 时 `shouldRetry` 返回 false |
| SyncEngine 正常流程 | `LOCAL_PENDING → SYNCING → SYNCED` | 状态正确转换，`remoteId` 回写 |
| SyncEngine 失败流程 | 连续失败直到 `SYNC_FAILED` | `attemptCount` 递增，`nextAttemptAt` 符合退避 |
| SyncEngine 恢复 | 启动时 `SYNCING` 记录被重置 | 重置为 `LOCAL_PENDING`，`attemptCount` 保留 |
| 冲突检测 | 远端版本变化 + 本地离线编辑 | 进入 `CONFLICT`，`serverSnapshot` 存储正确 |
| 冲突解决 | `resolveConflict` 各种 resolution | 状态正确回到 `LOCAL_PENDING` 或 `SYNCED` |
| 并发写入 | 两个协程同时 insert | 数据完整，条数正确，无异常 |
| Repository | 事件流收集与写入 | `HealthEvent` 正确映射为对应 Entity 并落库 |

### 12.2 测试约束
- 避免真实 `delay`：使用 `kotlinx-coroutines-test` 的 `TestDispatcher` + `advanceTimeBy` 虚拟时间推进
- API 使用 fake 实现：`FakeMockCloudApi` 可配置成功/失败/409 冲突/延迟
- Room 测试使用 `Room.inMemoryDatabaseBuilder` 内存数据库
- 不依赖 Android Framework 的纯逻辑（RetryPolicy、ConflictResolver）用纯 JUnit 测试

---

## 13. 目录结构

```
app/src/main/java/com/healthsync/
├── di/                          # Hilt modules
│   ├── DataSourceModule.kt
│   ├── DatabaseModule.kt
│   └── NetworkModule.kt
├── data/
│   ├── source/                  # HealthDataSource 接口与实现
│   │   ├── HealthDataSource.kt
│   │   ├── SimulatedBluetoothSource.kt
│   │   └── ManualInputSource.kt
│   ├── local/                   # Room
│   │   ├── HealthDatabase.kt
│   │   ├── dao/
│   │   │   ├── HeartRateDao.kt
│   │   │   ├── StepCountDao.kt
│   │   │   └── SleepRecordDao.kt
│   │   └── entity/
│   │       ├── HeartRateEntity.kt
│   │       ├── StepCountEntity.kt
│   │       └── SleepRecordEntity.kt
│   ├── remote/                  # Mock API
│   │   └── MockCloudApi.kt
│   ├── sync/                    # 同步引擎
│   │   ├── SyncEngine.kt
│   │   ├── SyncWorker.kt
│   │   ├── RetryPolicy.kt
│   │   └── ConflictResolver.kt
│   └── repository/
│       └── HealthRepository.kt
├── domain/
│   └── usecase/
│       ├── StartDataSourceUseCase.kt
│       ├── TriggerSyncUseCase.kt
│       ├── SaveSleepRecordUseCase.kt
│       ├── ResolveConflictUseCase.kt
│       └── GetHealthSummaryUseCase.kt
└── ui/
    ├── heartrate/
    │   ├── HeartRateScreen.kt
    │   └── HeartRateViewModel.kt
    ├── overview/
    │   ├── OverviewScreen.kt
    │   └── OverviewViewModel.kt
    ├── history/
    │   ├── HistoryScreen.kt
    │   └── HistoryViewModel.kt
    └── components/
        ├── HeartRateChart.kt     # Canvas 折线图
        ├── StepRingProgress.kt   # Canvas 环形进度条
        └── SyncStatusBadge.kt    # 同步状态指示
```
