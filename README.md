# HealthSync

离线优先的 Android 健康数据聚合应用，支持后台同步、指数退避重试和冲突解决。

Offline-first Android health data aggregation app with background sync and conflict resolution.

---

## 项目简介

HealthSync 模拟"智能手环 + 手机 App"场景，通过模拟蓝牙数据源产生心率/步数数据，支持手动录入睡眠记录。所有数据以 Room 为事实来源（离线优先），后台通过 SyncEngine 推送到 mock 云端，支持进程恢复、指数退避重试和版本冲突处理。

**核心特点**：
- **离线优先**：数据先写入 Room，同步异步进行，杀进程后不丢任务
- **Outbox 状态机**：每条数据有完整的同步状态（LOCAL_PENDING / SYNCING / SYNCED / SYNC_FAILED / CONFLICT）
- **冲突可处理**：睡眠记录使用乐观锁，冲突时保留双方版本，支持 KEEP_LOCAL / KEEP_REMOTE / MERGE
- **可观测**：UI 实时展示同步进度和待同步数量

---

## 架构

### 分层设计

```
UI (Jetpack Compose)
  └── ViewModel (MVVM, StateFlow)
        └── UseCase (Domain)
              └── Repository (统一读写入口)
                    ├── Room Database (事实来源)
                    ├── DataSource (数据产生)
                    └── SyncEngine (同步引擎)
                          ├── MockCloudApi
                          ├── ConflictResolver
                          └── RetryPolicy
```

- **UI**：只渲染 ViewModel 暴露的 `StateFlow`，不直接操作数据库
- **ViewModel**：聚合多个 Flow 为 UI 状态；发起同步/录入等意图
- **UseCase**：业务入口（开始数据源、触发同步、保存睡眠、解决冲突、订阅汇总指标）
- **Repository**：统一写入/读取入口，写入 Room、聚合统计
- **SyncEngine**：扫描 outbox → 事务抢占 → 上传 → 回写状态/重试计划
- **DI**：Hilt 绑定接口到实现

### 关键设计决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 事实来源 | Room（离线优先） | 进程被杀后数据不丢失，重启即可继续同步 |
| 同步驱动 | Outbox + 状态机 | 可恢复、可观测、可测试 |
| 冲突策略 | 保留双方 + 标记 CONFLICT | 不丢数据，可审计，适合离线编辑场景 |
| DI 框架 | Hilt | 编译时校验，与 ViewModel/WorkManager 原生集成 |
| 幂等/去重 | 客户端 eventId + 服务端去重 | 防止杀进程恢复后重放导致云端重复 |

> 详细设计文档见 [docs/DESIGN.md](docs/DESIGN.md)

### 包结构

```
com.example.healthsync
├── data
│   ├── local/          # Room Database, DAOs, Entities, Converters
│   ├── remote/         # MockCloudApi (模拟后端)
│   ├── repository/     # HealthRepository (统一读写入口)
│   ├── source/         # HealthDataSource, SimulatedBluetooth, ManualInput
│   └── sync/           # SyncEngine, SyncCoordinator, ConflictResolver, RetryPolicy, SyncWorker
├── di/                 # Hilt modules (AppModule, DatabaseModule)
├── domain/usecase/     # GetHealthSummary, SaveSleepRecord, ResolveConflict, TriggerSync, StartDataSource
└── ui/
    ├── components/     # HeartRateChart, SyncStatusBadge, StepRingProgress
    ├── heartrate/      # HeartRateScreen + ViewModel
    ├── overview/       # OverviewScreen + ViewModel
    ├── history/        # HistoryScreen + ViewModel
    └── theme/          # Material 3 theme
```

---

## 数据流

```
SimulatedBluetoothSource ──emit──→ HealthRepository ──insert──→ Room (LOCAL_PENDING)
                                                                     │
                                                          ┌──────────┴──────────┐
                                                          ▼                     ▼
                                                   ViewModel/UI            SyncEngine
                                                  (订阅 Flow)        (扫描→抢占→上传→回写)
                                                                          │
                                                                   MockCloudApi
                                                                    │         │
                                                              成功(SYNCED)  失败/409
                                                                          │
                                                                   RetryPolicy /
                                                                 ConflictResolver
```

---

## 构建要求

| 依赖 | 版本 |
|---|---|
| JDK | 11+（建议 17） |
| Android SDK | compileSdk 36, minSdk 26 |
| Gradle | 使用 Gradle Wrapper，无需手动安装 |

---

## 构建与运行

```bash
# 构建 debug APK
./gradlew assembleDebug

# 运行 JVM 单元测试
./gradlew test

# 运行 instrumented 测试（需要模拟器/设备）
./gradlew connectedAndroidTest
```

---

## 功能页面

### 概览页（Overview）
- 当前心率（大字 BPM，>100 红色 / <60 蓝色预警）
- 今日步数环形进度条（Canvas `drawArc`，目标 10,000 步）
- 最近一次睡眠时长与质量
- 同步状态徽章（待同步条数 / 全部已同步）

### 心率页（Heart Rate）
- 大字当前 BPM + 异常视觉提示
- 最近 5 分钟折线图（Canvas 手绘，不使用第三方图表库）
- 下拉刷新触发一次即时同步

### 历史页（History）
- 心率/步数/睡眠三类数据统一为时间线
- 按天分组 + `LazyColumn` + `stickyHeader` + 稳定 key + `contentType`
- 冲突（CONFLICT）和同步失败（SYNC_FAILED）状态标记
- 下拉刷新触发同步

---

## 同步引擎

### 状态机

```
[新数据写入] → LOCAL_PENDING → SYNCING → SYNCED
                    ▲              │
                    │         ┌────┴────┐
                    │     失败(未超限)  超过3次
                    │         │         │
                    └─────────┘    SYNC_FAILED
                    ▲              │
                    │         409冲突
                    │              │
                    │         CONFLICT
                    │              │
                    └──(用户解决冲突)──┘
```

### 触发方式
- **WorkManager 周期任务**（15 分钟）：兜底，即使 App 被杀也会恢复
- **前台持续推进循环**：对齐 `nextAttemptAt`，保证退避精度
- **手动触发**：下拉刷新 / UI 按钮
- **启动恢复**：`Application.onCreate` → 重置 `SYNCING → LOCAL_PENDING`

### 重试策略
- 指数退避：~2s → ~4s → ~8s（含 10% jitter）
- 最多 3 次失败后进入 `SYNC_FAILED`
- 用户可手动重试

### 冲突处理（睡眠记录）
- 基于 `baseRemoteVersion` 的乐观锁
- 服务端检测到版本落后 → HTTP 409 + 服务端数据
- 客户端标记 `CONFLICT` + 保存 `serverSnapshot`
- 支持三种解决策略：`KEEP_LOCAL` / `KEEP_REMOTE` / `MERGE`

---

## 测试

### 测试覆盖

| 测试文件 | 覆盖内容 |
|---|---|
| `RetryPolicyTest` | 指数退避值、jitter 范围、`shouldRetry` 边界 |
| `SyncEngineTest` | 正常流转、失败重试、SYNC_FAILED、恢复、跳过未到时间记录、睡眠同步/冲突/失败 |
| `ConflictResolverTest` | handleConflict、KEEP_LOCAL、KEEP_REMOTE、MERGE |
| `HealthRepositoryTest` | 心率/步数/睡眠事件映射落库、并发写入一致性 |
| `SyncCoordinatorTest` | triggerSync、recover、并发触发不重复 |

### 测试约束
- 无真实 `delay`：使用 `kotlinx-coroutines-test` 虚拟时间
- API 可 fake：`MockCloudApi` 可配置成功/失败/409/延迟
- 纯 JVM 单测，不依赖 Android Framework
- Fake DAO 使用 `ConcurrentHashMap` + `MutableStateFlow` 模拟 Room invalidation

---

## 里程碑

| 里程碑 | 描述 | 状态 |
|---|---|---|
| M0 | 工程脚手架（Hilt, Room, Compose, WorkManager） | Done |
| M1 | 数据源 → Repository → Room 管线 | Done |
| M2 | SyncEngine outbox + 重试 + 指数退避 | Done |
| M3 | SyncCoordinator（前台循环 + WorkManager 兜底） | Done |
| M4 | 断连/重连处理（模拟蓝牙） | Done |
| M5 | 睡眠记录 CRUD + 冲突检测与解决 | Done |
| M6 | 三屏 UI（概览 / 心率 / 历史）+ 导航 | Done |
| M7 | 单元测试（SyncEngine, ConflictResolver, Repository） | Done |
| M8 | 文档与交付 | Done |

> 详细里程碑定义见 [docs/MILESTONES.md](docs/MILESTONES.md)

---

## 文档

| 文档 | 说明 |
|---|---|
| [docs/DESIGN.md](docs/DESIGN.md) | 架构设计文档（分层/决策/数据模型/同步引擎/冲突策略/API 合约/测试策略） |
| [docs/MILESTONES.md](docs/MILESTONES.md) | 里程碑定义与完成标准 |
| [docs/SUMMARY.md](docs/SUMMARY.md) | 阶段总结（各里程碑达成详情/设计对齐/代码定位） |

---

## 已知限制

- **MockCloudApi**：后端为进程内内存模拟，无真实网络调用
- **SimulatedBluetoothSource**：随机生成心率/步数，非真实蓝牙连接
- **无 Health Connect 集成**：数据源仅为模拟实现
- **睡眠记录 UI**：新建/编辑表单未实现，流程通过 `SaveSleepRecordUseCase` 编程方式工作
- **冲突解决 UI**：历史页显示 CONFLICT 标记，完整解决对话框未实现
- **可扩展性**：Repository/SyncEngine 使用 `when` 逐类型分发（已标注为技术债务，见 DESIGN §4.4.3）

---

## 技术栈

| 类别 | 技术 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM + UseCase + Repository |
| DI | Hilt (Dagger) |
| 本地存储 | Room (KSP) |
| 异步 | Coroutines + Flow |
| 后台任务 | WorkManager |
| 导航 | Navigation Compose |
| 序列化 | Gson |
| 测试 | JUnit + kotlinx-coroutines-test |
