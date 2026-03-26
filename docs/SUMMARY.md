# HealthSync 阶段总结（M0–M7 全里程碑）

> 目标回顾：以**数据层架构**为核心，完成"多数据源 + 离线优先 + 可恢复同步 + 冲突处理 + 可观测状态"的最小闭环；UI 用 Jetpack Compose 验证完整链路。

---

## 1. 整体进度概览

M0–M7 全部里程碑已完成。项目从"只有心率闭环"扩展到覆盖心率、步数、睡眠三类数据的完整链路，包括同步引擎、冲突处理、三屏 UI 和核心单元测试。

| 里程碑 | 状态 | 一句话 |
|---|---|---|
| M0 工程可构建 | **Done** | Hilt + Room + Compose + WorkManager + JUnit |
| M1 数据层最小闭环 | **Done** | 数据源抽象 → Repository → Room → UI（心率） |
| M2 同步引擎 v1 | **Done** | Outbox 状态机 + 指数退避 + Mock API |
| M3 可恢复同步 | **Done** | 杀进程恢复 + WorkManager 兜底 + Mutex |
| M4 断连与异常 | **Done** | 模拟蓝牙断连/重连 + UI 降级提示 |
| M5 睡眠录入 + 冲突 | **Done** | SleepRecord CRUD + 409 冲突 + 三种解决策略 |
| M6 UI 补齐 | **Done** | 概览/心率/历史三屏 + 导航 + 下拉刷新 |
| M7 单元测试 | **Done** | SyncEngine / ConflictResolver / Repository / SyncCoordinator |

---

## 2. 各里程碑达成详情

### M0 — 工程可构建
- `minSdk=26`，`compileSdk=36`，100% Compose（无 XML 布局）
- 引入并配置：Hilt、Room（KSP）、WorkManager、Coroutines/Flow、Navigation Compose、Gson、JUnit + coroutines-test

### M1 — 数据层最小闭环（心率）
- 统一数据源抽象 `HealthDataSource`（`dataEvents: Flow<HealthEvent>`、`connectionState: Flow<ConnectionState>`、幂等 `start()/stop()`）
- 事件模型 `HealthEvent`（sealed class）：`HeartRateSample`、`StepCountIncrement`、`SleepRecord`
- `SimulatedBluetoothSource`：每 2s 心率采样 + 每 30s 步数增量
- Room 作为事实来源：三张表（`HeartRateEntity`、`StepCountEntity`、`SleepRecordEntity`）+ 响应式 DAO Flow
- `HealthRepository`：订阅 `dataEvents` → 映射为 Entity → 写入 Room（`LOCAL_PENDING`）

### M2 — 同步引擎 v1（状态可见 + 重试）
- 五态状态机：`LOCAL_PENDING → SYNCING → SYNCED / SYNC_FAILED / CONFLICT`
- 重试字段：`syncState / attemptCount / nextAttemptAt / lastError / remoteId`
- `MockCloudApi`：可配置延迟（200–1000ms）+ 可配置失败率 + eventId 去重
- `RetryPolicy`：指数退避 2s/4s/8s + 10% jitter，最多 3 次失败
- `SyncEngine.syncOnce()`：扫描 → 事务抢占 → 上传 → 回写状态
- UI 可观测：`SyncStatusBadge`（待同步条数 / 同步中 / 全部已同步）

### M3 — 可恢复同步（杀进程后恢复 + 双轨触发）
- `SyncEngine.recover()`：启动时 `SYNCING → LOCAL_PENDING`（保留 `attemptCount`）
- WorkManager 周期任务兜底（`UniquePeriodicWork`，15 分钟最小周期）
- 前台持续推进循环（`SyncCoordinator.startForegroundLoop`）：对齐最近 `nextAttemptAt`，使 2s/4s 退避不被粗粒度扫描吞掉
- 进程内单实例锁（`Mutex`）：并发触发只唤醒不重复执行
- 手动触发（下拉刷新 / `TriggerSyncUseCase`）

### M4 — 断连与异常处理
- `SimulatedBluetoothSource` 模拟断连：`connectionState` 切换 `DISCONNECTED → RECONNECTING → CONNECTED`
- 断连时暂停数据产生，UI 展示历史数据不崩溃、不白屏
- 断连/重连提示（Banner / Snackbar）

### M5 — 睡眠录入 + 冲突策略落地
- `SleepRecordEntity` 完整字段：业务主键 UUID + 冲突检测（`remoteVersion / baseRemoteVersion / localVersion / serverSnapshot`）
- `SaveSleepRecordUseCase`：新建（UUID 生成）+ 编辑已同步记录（`SYNCED → LOCAL_PENDING`，`localVersion++`，`baseRemoteVersion = remoteVersion`）
- `MockCloudApi.uploadSleepRecord`：PUT 语义 + 乐观锁冲突检测（`baseRemoteVersion < currentRemoteVersion` → HTTP 409）
- `ConflictResolver.handleConflict`：标记 `CONFLICT` + 保存 `serverSnapshot`（JSON）
- `ConflictResolver.resolveConflict`：三种策略
  - `KEEP_LOCAL`：保留本地 → `LOCAL_PENDING`，重新同步
  - `KEEP_REMOTE`：采用服务端 → `SYNCED`
  - `MERGE`：时间区间取并集 + 本地质量 → `LOCAL_PENDING`
- `ResolveConflictUseCase`：Domain 层入口

### M6 — UI 补齐（三屏 + 导航）
- `MainActivity`：底部三 Tab 导航（概览 / 心率 / 历史）
- **概览页**（`OverviewScreen`）：当前心率（异常变色 >100 红 / <60 蓝）、步数环形进度条（Canvas `drawArc`，目标 10000）、最近睡眠时长与质量、同步状态徽章
- **心率页**（`HeartRateScreen`）：大字 BPM + 5 分钟折线图（Canvas 手绘）+ 同步 badge + 下拉刷新
- **历史页**（`HistoryScreen`）：三类数据统一为 `TimelineItem`（映射在 ViewModel 完成），按日分组 + `LazyColumn` + `stickyHeader` + 稳定 key + `contentType` 区分 header/item + `PullToRefreshBox` 触发同步

### M7 — 单元测试覆盖
- **6 个测试文件，覆盖数据层核心路径**：
  - `RetryPolicyTest`：指数退避值 + jitter 范围 + `shouldRetry` 边界
  - `SyncEngineTest`：正常流转 / 失败重试 / `SYNC_FAILED` / 恢复 / 跳过未到时间记录 / 睡眠正常同步 / 409 冲突 / 睡眠失败重试 / 睡眠恢复
  - `ConflictResolverTest`：`handleConflict` + `KEEP_LOCAL` / `KEEP_REMOTE` / `MERGE`
  - `HealthRepositoryTest`：心率/步数/睡眠事件映射落库 + 并发写入一致性
  - `SyncCoordinatorTest`：triggerSync / recover / 并发触发不重复处理
- **测试基础设施**：`FakeHeartRateDao` / `FakeStepCountDao` / `FakeSleepRecordDao`（ConcurrentHashMap + MutableStateFlow 模拟 Room invalidation）
- **约束**：无真实 delay（`kotlinx-coroutines-test`）、无 Android Framework 依赖（纯 JVM）、API 可 fake

---

## 3. 已落地的关键设计点（与 DESIGN.md 对齐）

### Room 作为事实来源（离线优先，DESIGN §3.1）
- 所有数据先落库，UI 订阅 Room Flow；同步引擎只从 Room 扫描待同步数据。

### Outbox + 状态机驱动同步（DESIGN §3.2）
- 五态 `SyncState` 与重试字段把同步过程完整持久化，支持可恢复与可观测。

### 冲突策略：保留双方并标记冲突（DESIGN §3.3）
- 不丢弃任何一方数据；`CONFLICT` 状态 + `serverSnapshot` 存储服务端快照；用户/逻辑决定解决方式。

### 幂等/去重（DESIGN §6.5）
- 心率/步数：客户端 `eventId=UUID` + 云端按 `eventId` 去重。
- 睡眠：业务主键 UUID + PUT 语义，天然幂等。

### 双轨触发与并发治理（DESIGN §6.1）
- **WorkManager**（15 分钟周期）：兜底恢复与推进。
- **前台协程循环**：对齐 `nextAttemptAt`，保证 2s/4s 退避精度。
- **Mutex**：进程内单实例锁，防止并行重复。

### 分层架构严格遵循（DESIGN §2.1）
- UseCase 通过 Repository 统一写入，不直接依赖 DAO。
- ViewModel 只聚合 Flow 为 UI 状态，不操作数据库。
- Screen 只渲染 ViewModel 暴露的 StateFlow。

---

## 4. 关键入口与代码定位

### 应用启动链路
- `HealthSyncApplication`：`recover()` → 启动数据源收集 → 触发一次同步 → 调度 WorkManager 周期任务

### 数据产生与落库
- `SimulatedBluetoothSource`：每 2s 心率 + 每 30s 步数
- `ManualInputSource`：睡眠记录手动录入
- `HealthRepository.collectFrom()`：订阅 `dataEvents` → Entity(`LOCAL_PENDING`) → Room

### 同步引擎
- `SyncEngine.syncOnce()`：心率批量上传 / 步数批量上传 / 睡眠逐条 PUT（含冲突处理）
- `SyncCoordinator`：前台循环 + Mutex + 手动触发
- `SyncWorker` / `SyncWorkScheduler`：WorkManager 兜底

### 冲突处理
- `ConflictResolver.handleConflict()`：409 → `CONFLICT` + `serverSnapshot`
- `ConflictResolver.resolveConflict()`：`KEEP_LOCAL` / `KEEP_REMOTE` / `MERGE`
- `ResolveConflictUseCase`：Domain 层入口

### UI 三屏
- **概览**：`OverviewScreen` + `OverviewViewModel`（心率/步数/睡眠/同步状态）
- **心率**：`HeartRateScreen` + `HeartRateViewModel`（实时 BPM + 折线图 + 下拉刷新）
- **历史**：`HistoryScreen` + `HistoryViewModel`（TimelineItem 映射 + 按日分组 + 下拉刷新）

---

## 5. 如何运行与验证

### 构建要求
- **JDK**：11+（建议 17）
- **Android SDK**：compileSdk 36，minSdk 26
- **Gradle**：使用 Gradle Wrapper，无需手动安装

### 构建与测试
```bash
./gradlew assembleDebug    # 构建 debug APK
./gradlew test             # 运行 JVM 单元测试
```

### 运行验证（手动）
打开 App 后：
- **概览页**：当前心率（每 2s 更新）+ 步数环形进度 + 最近睡眠 + 同步状态
- **心率页**：大字 BPM + 5 分钟折线图持续追加 + 下拉刷新触发同步
- **历史页**：三类数据按日分组时间线 + 冲突/失败状态标记 + 下拉刷新
- 同步 badge 随上传进展变化（待同步 → 同步中 → 已同步）

---

## 6. 已知限制

- **MockCloudApi**：后端为进程内内存模拟，不代表真实网络/持久化行为。
- **SimulatedBluetoothSource**：随机生成心率/步数，非真实蓝牙。
- **无 Health Connect 集成**：数据源仅为模拟实现。
- **睡眠记录 UI**：新建/编辑表单未实现；流程通过 `SaveSleepRecordUseCase` 以编程方式工作。
- **冲突解决 UI**：历史页显示 CONFLICT 标记，但完整的冲突解决对话框未实现。
- **可扩展性架构**：Repository/SyncEngine 目前使用 `when` 逐类型分发（DESIGN §4.4.3 已标注为技术债务），新增第四种数据类型时应重构为 EventHandler/SyncAdapter + Hilt multibinding。

---

## 7. 后续建议

- 实现睡眠记录的新建/编辑 UI 表单
- 实现冲突解决 UI 对话框（展示本地 vs 服务端差异，让用户选择）
- 新增数据类型时重构为 EventHandler/SyncAdapter 可插拔架构
- 接入真实蓝牙 / Health Connect 数据源（只需新增实现 + DI 注册）
- 接入真实 REST API 替换 MockCloudApi
