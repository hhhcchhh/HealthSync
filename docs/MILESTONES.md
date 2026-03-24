# HealthSync 里程碑（docs/MILESTONES.md）

> 原则：**先把数据层闭环做扎实**。宁可只做心率，也要把同步引擎/状态机/恢复/测试做完整。

---

## Milestone 0：工程可构建（门槛）
- **完成标准**
  - 项目可直接运行 `./gradlew assembleDebug`
  - `minSdk = 26`
  - 100% Compose（无 XML 布局）
  - 引入并可用：DI（Hilt）、Room、Coroutines + Flow、单元测试框架（JUnit + coroutines-test）

---

## Milestone 1：数据层最小闭环（先只做心率）
- **目标**：从模拟数据源产生心率 → Room 落库 → UI 实时显示。
- **完成标准**
  - 定义统一数据源抽象 `HealthDataSource`
    - `dataEvents: Flow<HealthEvent>`（数据事件流）
    - `connectionState: Flow<ConnectionState>`（连接状态流：CONNECTED/DISCONNECTED/RECONNECTING）
    - `start()/stop()` 幂等
  - 定义事件模型 `HealthEvent`（sealed class），至少包含 `HeartRateSample(timestamp,bpm,sourceId)`
  - 实现 `SimulatedBluetoothSource`
    - 每 2 秒发出 `HeartRateSample(bpm=60..120)`
    - `connectionState` 可正常对外发出 CONNECTED
  - Room 作为事实来源（SoT）
    - 心率表（`HeartRateEntity`）可落库并可响应式查询（最近 5 分钟/最近一条）
  - Repository：订阅 `dataEvents` 并将心率事件映射为 Entity 写入 Room
  - UI（Compose）+ VM：订阅 Room Flow/StateFlow，实时心率数字随数据更新

---

## Milestone 2：同步引擎 v1（离线优先 + 状态可见 + 重试）
- **目标**：Room 为事实来源，后台把 outbox 推送到 mock 云端。
- **完成标准**
  - 同步状态机字段落地（至少心率）
    - `syncState`：`LOCAL_PENDING/SYNCING/SYNCED/SYNC_FAILED/CONFLICT`
    - `attemptCount/nextAttemptAt/lastError/remoteId`
  - mock `CloudApi`（REST 合约可在代码或文档中固定）
    - 支持随机/可配置延迟（例如 200–1000ms）
    - 支持可配置失败率（用于重试路径）
  - `RetryPolicy`
    - 指数退避：~2s/~4s/...（可带 10% jitter），最大 3 次
    - 超过上限后由引擎置为 `SYNC_FAILED`
  - `SyncEngine` 核心推进流程
    - 扫描待同步记录：`syncState in (LOCAL_PENDING, SYNC_FAILED)` 且 `nextAttemptAt <= now`
    - 抢占式标记 `SYNCING`（必须在 `@Transaction` 内完成 `SELECT → UPDATE` 原子抢占）
    - 上传成功回写：`SYNCED + remoteId` 并清理错误字段
    - 上传失败回写：递增 `attemptCount`、计算 `nextAttemptAt`、写入 `lastError`
  - 可观测性
    - UI/VM 可展示“待同步/同步中/失败”的汇总数量（至少 1 个 badge 文案：`X 条待同步`）

---

## Milestone 3：可恢复同步（杀进程后恢复）
- **目标**：同步中杀 App，不丢任务；重启后继续。
- **完成标准**
  - 可恢复性（Room 持久化）自洽
    - 同步队列与重试计划完全在 Room（仅靠 DB 即可恢复）
    - 进程被杀后，不出现“永远卡在同步中”的记录
  - 启动恢复（`SyncEngine.recover()`）
    - 将 `syncState == SYNCING` 的记录重置为 `LOCAL_PENDING`（保留 `attemptCount`）
    - 恢复后立即触发一轮同步扫描/推进
  - 触发机制双轨落地（与设计一致）
    - WorkManager 周期任务兜底（Periodic，最小周期 15 分钟），使用 `@HiltWorker` 注入
    - App 前台持续推进循环（协程）：反复“扫描→抢占→上传→回写”，并对齐最近的 `nextAttemptAt`（避免 30s 扫描量化掉 2s/4s 退避）
    - 手动触发一次同步（下拉刷新/按钮等）
  - 并发/重复触发治理
    - 进程内单实例运行锁（例如 Mutex/atomic flag）：同步运行中再次触发只唤醒/加速，不启动第二个循环
    - WorkManager 使用 UniqueWork（避免重复排程）

---

## Milestone 4：异常与断连（隐藏考点）
- **目标**：模拟蓝牙断连/异常不崩溃，可观测且优雅降级。
- **完成标准**
  - `SimulatedBluetoothSource` 可模拟断连
    - `connectionState` 切换为 `DISCONNECTED` 时暂停产生数据
    - 一段时间后可自动 `RECONNECTING → CONNECTED` 并恢复产生数据
  - UI/VM 可观测且优雅降级
    - 断连时 UI 不崩溃、不白屏，仍展示 Room 中历史数据
    - 断连/异常提示清晰（Banner/Snackbar/状态文案均可）

---

## Milestone 5：睡眠录入 + 冲突策略落地
- **目标**：实现手动睡眠记录并完成冲突处理链路。
- **完成标准**
  - 事件与本地模型（睡眠为可编辑业务对象）
    - `HealthEvent.SleepRecord(id(UUID), startTime, endTime, quality, sourceId)`
    - `SleepRecordEntity` 字段包含：`remoteVersion/baseRemoteVersion/localVersion/serverSnapshot`（用于冲突检测与保留双方）
  - `ManualInputSource` / UseCase：新增与修改睡眠记录
    - 支持离线修改“已同步（SYNCED）”记录，触发 `SYNCED → LOCAL_PENDING`
  - Mock 云端冲突能力
    - `PUT /api/sleep-records/{id}` 支持乐观锁（基于 `baseRemoteVersion`）
    - 发生冲突返回 HTTP 409 + 服务端当前数据（用于写入 `serverSnapshot`）
  - 冲突处理链路可落地、可观测
    - 409 后本地记录进入 `CONFLICT`，并保存 `serverSnapshot`
    - 提供 `resolveConflict(id, resolution)`（至少支持 `KEEP_LOCAL/KEEP_REMOTE/MERGE` 之一的落地实现）
    - 解决后状态回到 `LOCAL_PENDING`（或采用远端直达 `SYNCED`）
  - 文档一致性
    - `docs/DESIGN.md` 已明确：备选方案→选择理由→trade-off（与实现一致）

---

## Milestone 6：UI 补齐（用于证明链路与性能）
- **目标**：Compose 端展示三块 UI，验证数据层可用。
- **完成标准**
  - 实时心率面板（Compose）
    - 大字当前 BPM + 异常视觉提示（>100 / <60）
    - 最近 5 分钟折线图（Canvas 手绘，不引入第三方图表库）
  - 今日概览卡
    - 步数环形进度（Canvas `drawArc`，目标 10000）
    - 最近一次睡眠时长（end-start）
    - 同步状态汇总（待同步/同步中/失败条数或统一 badge）
  - 历史页（性能与链路验证）
    - 按天分组 + `LazyColumn` 稳定 key（`key={record.id}`）
    - 下拉刷新触发一次 `syncNow()`

---

## Milestone 7：单元测试覆盖率 > 60%（数据层核心）
- **目标**：证明同步引擎/冲突处理/并发写入“可靠且可回归”。
- **完成标准**
  - 覆盖范围（高价值路径优先）
    - `RetryPolicy`（指数退避 + jitter 范围 + 最大重试次数）
    - `SyncEngine` 状态流转：`LOCAL_PENDING → SYNCING → SYNCED`
    - 失败路径：重试直到 `SYNC_FAILED`，以及手动重试回到 `LOCAL_PENDING`
    - 启动恢复：`SYNCING` 重置为 `LOCAL_PENDING`（attemptCount 保留）
    - 冲突：409 进入 `CONFLICT` + `serverSnapshot` 存储；`resolveConflict` 后能继续同步
    - 并发写入一致性：多协程同时写入不丢数据
  - 测试稳定性约束
    - 不依赖真实 `delay`（使用 `kotlinx-coroutines-test` 虚拟时间）
    - API/时间/调度可 fake（`FakeMockCloudApi`、TestDispatcher 等）
    - 关键逻辑优先 JVM 单测，必要时少量 instrumented test

---

## Milestone 8：交付完善
- **完成标准**
  - `docs/DESIGN.md` 完整（架构图/关键决策≥3/数据流/冲突策略）
  - `docs/AI_LOG.md` 完整且真实（你如何用 AI、如何验证与取舍）
  - README：如何构建/运行、已实现范围、已知限制
  - 推送到 GitHub

