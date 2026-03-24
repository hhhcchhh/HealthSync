# HealthSync 阶段总结（心率数据层闭环）

> 原则：**先把数据层闭环做扎实**。本阶段宁可只做心率，也要把同步引擎/状态机/恢复/测试做完整。

---

## 1. 本阶段目标与范围

### 目标（只做心率闭环）
- **数据产生 → Room 落库 → UI 实时显示**
- **离线优先 + outbox 状态机 + 重试**
- **可恢复同步（杀进程后恢复）**
- **测试可回归（核心逻辑单测覆盖）**

### 明确不做/延后
- **睡眠录入 + 冲突处理链路（Milestone 5）**：本阶段只做骨架（模型/DAO/ConflictResolver 预留），不做完整闭环。
- **UI 补齐（Milestone 6）**：本阶段只做“实时心率面板 + 同步状态可见”的最小 UI。

---

## 2. 里程碑达成情况（闭环到哪里）

本阶段完成了如下里程碑的“心率闭环必需部分”：

- **Milestone 0（工程可构建）**
  - 引入并配置：**Hilt、Room、WorkManager、Coroutines/Flow、JUnit + coroutines-test**
  - `minSdk=26`，100% Compose

- **Milestone 1（数据层最小闭环：先只做心率）**
  - `HealthDataSource` 抽象 + `HealthEvent` 事件模型
  - `SimulatedBluetoothSource`（每 2s 心率采样）
  - Room 作为事实来源：心率表 + DAO 响应式 Flow
  - Repository 订阅 `dataEvents` 并落库
  - UI/VM 实时展示最新 BPM 与最近 5 分钟折线图

- **Milestone 2（同步引擎 v1：状态可见 + 重试）**
  - 状态机字段落地：`syncState/attemptCount/nextAttemptAt/lastError/remoteId`
  - `MockCloudApi`：随机/可配置延迟 + 可配置失败率
  - `RetryPolicy`：指数退避（2s/4s/8s）+ 10% jitter，最多 3 次
  - `SyncEngine`：扫描→抢占（事务）→上传→回写（成功/失败）
  - UI 可见：同步 badge（“同步中/待同步/已同步”）

- **Milestone 3（可恢复同步：杀进程后恢复 + 双轨触发）**
  - `recover()`：启动时将 `SYNCING → LOCAL_PENDING`（attemptCount 保留）
  - WorkManager 周期任务兜底（UniquePeriodicWork，15 分钟）
  - 前台持续推进循环（协程）+ 进程内单实例锁（Mutex）
  - 手动触发一次同步（下拉刷新）

- **Milestone 7（单元测试覆盖：闭环核心路径）**
  - `RetryPolicy`：退避 + jitter + 最大重试次数
  - `SyncEngine`：状态流转/失败重试/3 次失败/恢复/nextAttemptAt 跳过
  - `Repository`：事件映射落库 + 并发写入一致性
  - `SyncCoordinator`：单实例锁 + recover + 并发触发

---

## 3. 已落地的关键设计点（与 DESIGN.md 对齐）

### Room 作为事实来源（离线优先）
- 所有数据先落库（`HeartRateDao.insert`），UI 订阅 Room Flow。

### Outbox + 状态机驱动同步
- 使用 `SyncState` 与重试字段把同步过程持久化，支持恢复与可观测。

### 幂等/去重（防止重放导致云端重复）
- 心率事件本地生成 `eventId=UUID`，Room 建唯一索引；
- mock 云端按 `eventId` 去重（重复上报不重复插入）。

### 双轨触发与重复触发治理
- **WorkManager**：兜底恢复与同步推进；
- **前台协程循环**：对齐 `nextAttemptAt`，避免 2s/4s 退避被粗粒度扫描吞掉；
- **进程内单实例锁**：Mutex 防止并行重复跑同步。

---

## 4. 关键入口与代码定位

### 应用启动链路
- `HealthSyncApplication`：
  - `recover()`（恢复 SYNCING）
  - 启动数据源收集（模拟心率）
  - 触发一次同步
  - 调度周期同步任务（WorkManager）

### 数据产生与落库
- `SimulatedBluetoothSource`：每 2 秒发 `HealthEvent.HeartRateSample`
- `HealthRepository.collectFrom(...)`：订阅 `dataEvents` 并写入 `HeartRateEntity(LOCAL_PENDING)`

### 同步引擎
- `SyncEngine`：`syncOnce()` 执行扫描/抢占/上传/回写
- `SyncCoordinator`：前台持续推进循环 + 单实例锁 + 手动触发
- `SyncWorker` / `SyncWorkScheduler`：WorkManager 兜底

### UI（最小证明链路）
- `HeartRateScreen`：大字 BPM + 折线图 + 同步 badge + 下拉刷新
- `HeartRateViewModel`：聚合 Room Flow + 同步状态为 `HeartRateUiState`

---

## 5. 如何运行与验证（建议流程）

### 构建要求
- 需要本机安装 **JDK（建议 17）** 才能运行 Gradle/AGP 构建。

### 运行验证（手动）
- 打开 App 后：
  - 首页 BPM 每 2 秒变化（来自模拟数据源）
  - 折线图持续追加点位（最近 5 分钟窗口）
  - 同步状态 badge 会随上传进展变化（待同步→同步中→已同步）
  - 下拉刷新会触发一次 `triggerSync()`（立即推进同步）

### 测试验证（自动）
- 运行 JVM 单测：
  - `./gradlew test`

---

## 6. 已知限制与后续计划

### 已知限制
- 目前同步引擎同时包含心率与步数的同步路径，但本阶段的“闭环重点”是心率；步数与睡眠完整 UI/业务链路将在后续里程碑补齐。
- `MockCloudApi` 为内存 mock（进程内），用于验证同步流程与重试逻辑，不代表真实后端持久化行为。

### 下一步建议（按里程碑顺序）
- **M4**：完善断连模拟与更一致的 UI 降级提示（当前已有基础 banner/snackbar）。
- **M5**：落地睡眠录入 + 409 冲突链路（CONFLICT + serverSnapshot + resolveConflict）。
- **M6**：概览/历史页 UI 与性能验证。

