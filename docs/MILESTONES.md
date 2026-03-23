# HealthSync 里程碑（docs/MILESTONES.md）

> 原则：**先把数据层闭环做扎实**。宁可只做心率，也要把同步引擎/状态机/恢复/测试做完整。

---

## Milestone 0：工程可构建（门槛）
- **完成标准**
  - 项目可直接运行 `./gradlew assembleDebug`
  - `minSdk = 26`
  - 100% Compose（无 XML 布局）
  - 引入并可用：DI（Hilt 或 Koin）、Room、Coroutines + Flow、单元测试框架

---

## Milestone 1：数据层最小闭环（先只做心率）
- **目标**：从模拟数据源产生心率 → Room 落库 → UI 实时显示。
- **完成标准**
  - 定义 `DataSource` 抽象（至少含“数据流 + 连接状态流”）
  - 实现 `SimulatedBluetoothSource`：每 2 秒心率（60–120）
  - Repository：将心率写入 Room，并提供查询 Flow（最近 5 分钟/最近一条）
  - UI：实时心率数字可随 Flow 更新

---

## Milestone 2：同步引擎 v1（离线优先 + 状态可见 + 重试）
- **目标**：Room 为事实来源，后台把 outbox 推送到 mock 云端。
- **完成标准**
  - Room 表字段包含：`syncState/attemptCount/nextAttemptAt/lastError`（至少心率）
  - mock `CloudApi`：支持 delay、可配置失败
  - `SyncEngine`：扫描待同步记录、标记 `SYNCING`、上传、成功置 `SYNCED`
  - 失败自动重试：指数退避、最多 3 次；超过次数置 `SYNC_FAILED`
  - UI/VM 能展示：待同步数量（X 条）

---

## Milestone 3：可恢复同步（杀进程后恢复）
- **目标**：同步中杀 App，不丢任务；重启后继续。
- **完成标准**
  - 同步队列与重试计划完全持久化在 Room（重启可从 DB 还原）
  - 启动恢复策略清晰：例如将停留在 `SYNCING` 的记录重置为 `LOCAL_PENDING` 并继续推进
  - 不出现“永远卡在同步中”的状态

---

## Milestone 4：异常与断连（隐藏考点）
- **目标**：模拟蓝牙断连/异常不崩溃，可观测且优雅降级。
- **完成标准**
  - `SimulatedBluetoothSource` 可模拟断连（停止发数据 + 发出 disconnected 状态）
  - UI 不崩溃，仍可展示历史值；并能提示“断开/异常”

---

## Milestone 5：睡眠录入 + 冲突策略落地
- **目标**：实现手动睡眠记录并完成冲突处理链路。
- **完成标准**
  - `ManualInputSource`：新增/修改睡眠记录（支持离线修改已同步记录）
  - 云端 mock 支持版本号（remoteVersion）或等价机制，能制造冲突
  - 冲突检测与策略实现：本项目建议“保留双方并标记冲突（CONFLICT）”，并可从状态看出冲突已发生
  - `docs/DESIGN.md` 写清：为什么选该策略、trade-off

---

## Milestone 6：UI 补齐（用于证明链路与性能）
- **目标**：Compose 端展示三块 UI，验证数据层可用。
- **完成标准**
  - 实时心率面板：大字当前值 + 最近 5 分钟折线图（Canvas 手绘）+ 异常视觉提示（>100 或 <60）
  - 今日概览卡：步数环形进度（目标 10000）+ 最近一次睡眠时长 + 待同步汇总
  - 历史页：按天分组列表 + 下拉刷新触发同步 + LazyColumn 稳定 key

---

## Milestone 7：单元测试覆盖率 > 60%（数据层核心）
- **目标**：证明同步引擎/冲突处理/并发写入“可靠且可回归”。
- **完成标准**
  - 覆盖：重试策略、状态流转、并发写入、冲突检测与处理、断连/网络失败路径
  - 测试不依赖真实 delay（使用测试调度器/虚拟时间）
  - 关键逻辑在 JVM 单测可跑（优先），必要时再做少量 instrumented test

---

## Milestone 8：交付完善
- **完成标准**
  - `docs/DESIGN.md` 完整（架构图/关键决策≥3/数据流/冲突策略）
  - `docs/AI_LOG.md` 完整且真实（你如何用 AI、如何验证与取舍）
  - README：如何构建/运行、已实现范围、已知限制
  - 推送到 GitHub

