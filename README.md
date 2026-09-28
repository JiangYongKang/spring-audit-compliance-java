# Spring Audit & Compliance（本地审计日志与合规规则评估）

一套纯本地运行的审计事件接入、合规规则判定与历史回放能力。所有数据保存在本地
H2 文件数据库中，不依赖任何外部中间件；事件记录、规则版本、判定结果全部可追溯、可验证。

- 事件接入：逐项校验 + 稳定错误码；`eventId` 幂等，重复提交绝不产生重复记录。
- 规则管理：每次更新生成不可变新版本；评估对版本行做快照，并发更新读不到半更新状态。
- 历史重放：可指定任意历史规则版本重放时间窗口，同一输入逐位可复现；每行结果带规则版本与判定依据。
- 查询/导出：按主体、时间、判定结果键集分页，并发写入不漏读、不重复；导出强制数据保留策略，过期数据带不出去。

## 1. 技术栈与运行

- Java 21、Spring Boot 3.4.x（Web + Data JPA）、H2 数据库、JUnit 5。
- 默认数据库为本地文件：`./data/auditdb`（重启数据保留）；测试使用隔离的内存 H2。

```bash
# 构建并运行全部测试
mvn clean test

# 打包并启动（默认端口 8080）
mvn clean package
java -jar target/spring-audit-compliance-java-1.0-SNAPSHOT.jar
# 自定义端口：--server.port=18099
```

关键配置（`src/main/resources/application.properties`）：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `app.retention.retention-days` | 90 | 数据保留天数；查询/导出只返回 `eventTime >= now-retentionDays` 的数据 |
| `app.retention.max-clock-skew-minutes` | 5 | 接入时允许的事件时间未来偏移，超出按"时间异常"拒绝 |

## 2. 事件模型（AuditEvent）

| 字段 | 含义 | 约束 |
| --- | --- | --- |
| `id` | 库内自增序号 | 单调递增，键集分页 tiebreaker，非业务标识 |
| `eventId` | 调用方事件 ID | **唯一**，幂等键 |
| `subject` / `action` / `resource` | 主体 / 动作 / 资源 | subject、action 必填 |
| `severity` | 严重级别 | 仅接受 `LOW/MEDIUM/HIGH/CRITICAL` |
| `eventTime` | 事件发生时间 | ISO-8601 UTC；需落在保留窗口与时钟偏移内 |
| `receivedAt` | 服务端接收时间 | 服务端生成 |
| `detailJson` | 原始明细 | 可空 |

### 接入校验与稳定错误码

错误响应统一为 `{"code","message","timestamp"}}`，同一类问题返回稳定 code：

| code | 触发条件 | HTTP |
| --- | --- | --- |
| `VALIDATION_MISSING_FIELD` | eventId/subject/action 等关键字段缺失 | 400 |
| `VALIDATION_INVALID_SEVERITY` | severity 不在四个合法值内 | 400 |
| `VALIDATION_TIME_MISSING` | eventTime 缺失 | 400 |
| `VALIDATION_TIME_ANOMALY` | 时间格式非法、未来超偏移、早于保留窗口起点 | 400 |
| `VALIDATION_INVALID_JSON` | 请求体不是合法 JSON | 400 |
| `RULE_NOT_FOUND` / `RUN_NOT_FOUND` | 规则/批次不存在 | 404 |
| `RULE_VERSION_CONFLICT` | 并发更新规则锁冲突，可基于最新版本重试 | 409 |

幂等语义：相同 `eventId` 重复提交返回 `200` 且 `duplicate=true`；并发重复提交由
数据库唯一约束兜底，冲突事务回滚后回查既有记录返回，库内永远只有一条记录。

## 3. 规则模型与版本（ComplianceRule / RuleLock）

规则判定为纯函数（`RuleEngine`）：当事件的 subject/action/resource 与规则的三个匹配
模式全部命中（精确值或 `*` 表示任意），且 `事件严重级别 >= minSeverity` 时，判为
`NON_COMPLIANT`，否则 `COMPLIANT`。

- 规则每次更新不是原地修改，而是插入一条新版本行，`(rule_id, version)` 唯一、行内容不可变；
  历史版本 `active=false` 但永久保留，供重放使用。
- 新增一张 `rule_lock` 锁点表（每规则一行）。更新事务先对锁点行取**悲观写锁**，
  从而把同一规则的并发更新完全串行化：不会出现重复版本号、也不会出现两个 active 版本；
  锁等待失败映射为 409 `RULE_VERSION_CONFLICT`。
- 评估事务只读不可变版本行，从不访问锁点表，因此并发更新不会让评估读到"半更新状态"——
  评估只会看到完整的旧版本或完整的新版本。

## 4. 评估批次与历史重放（EvaluationRun / EvaluationResult）

每次评估生成一个批次：

- `EvaluationRun`：时间窗口（start 含、end 不含）、模式 `LIVE/REPLAY`、状态、
  事件数/非合规数，以及 `ruleSnapshotJson`（如 `{"r1":2}`），记录本批次每个规则所用的**确切版本**。
- `EvaluationResult`：单事件 × 单规则 × 单批次一行，含 `ruleVersion`、`decision`
  与人类可读 `rationale`（逐维度匹配结果 + 严重级别比较 + 结论）。
- 批次在单个事务内完成，规则版本快照、结果写入、批次完成标记原子可见，外部看不到半成品批次。
- 事件按 `(eventTime, id)`、规则按 `ruleId` 排序，处理顺序确定；判定是纯函数，
  因此同一窗口 + 同一 `ruleVersions` 重放，结果（含依据文本）逐位可复现。
- 批次支持客户端 `runId` 幂等；`(run_id, event_id, rule_id)` 唯一约束保证重放不产生重复判定行。

## 5. 查询、分页与数据保留

- 分页为**键集分页**（keyset），排序键 `(eventTime, id)`，游标为 Base64 不透明 token，
  取 `pageSize+1` 判断是否有下一页。新写入只落在时间线尾部，跨页翻读时边界记录不漏、不重，
  同毫秒事件由自增 id 破平。
- 所有查询与导出都会计算保留截止线 `cutoff = now - retentionDays` 并下推到 SQL，
  过期行在数据库层就被排除；即使显式按过期批次 `runId` 导出，过期数据也不会出现在 CSV 中。
- 导出端点返回带 BOM 安全转义的 CSV。

## 6. HTTP API 速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/events` | 接入事件（幂等） |
| GET  | `/api/events?subject=&start=&end=&cursor=&size=` | 按主体分页查事件 |
| POST | `/api/rules` | 新建规则（版本 1） |
| PUT  | `/api/rules/{ruleId}` | 更新规则（追加新版本） |
| GET  | `/api/rules` | 当前生效规则 |
| GET  | `/api/rules/{ruleId}/versions` | 规则完整版本链 |
| POST | `/api/evaluations/runs` | 时间窗口（重）评估，可带 `ruleVersions`、`runId` |
| GET  | `/api/evaluations/runs/{runId}` | 批次元数据（含规则版本快照） |
| GET  | `/api/evaluations/results` | 判定结果分页（subject/decision/runId/时间窗） |
| GET  | `/api/evaluations/results/export` | 导出 CSV（强制保留策略） |

### 快速体验

```bash
# 1) 接入事件
curl -X POST localhost:8080/api/events -H 'Content-Type: application/json' -d '{
  "eventId":"e-1","subject":"alice","action":"delete","resource":"/db",
  "severity":"HIGH","eventTime":"2026-09-28T03:00:00Z"}'

# 2) 建规则
curl -X POST localhost:8080/api/rules -H 'Content-Type: application/json' -d '{
  "ruleId":"r1","name":"高危删除","matchSubject":"alice","matchAction":"delete",
  "matchResource":"*","minSeverity":"MEDIUM"}'

# 3) 重放窗口
curl -X POST localhost:8080/api/evaluations/runs -H 'Content-Type: application/json' -d '{
  "windowStart":"2026-09-28T00:00:00Z","windowEnd":"2026-09-29T00:00:00Z"}'

# 4) 用历史版本 1 再次重放（即使规则已更新，仍可复现 v1 结论）
curl -X POST localhost:8080/api/evaluations/runs -H 'Content-Type: application/json' -d '{
  "windowStart":"2026-09-28T00:00:00Z","windowEnd":"2026-09-29T00:00:00Z",
  "ruleVersions":{"r1":1},"runId":"replay-r1-v1"}'

# 5) 查询 / 导出
curl "localhost:8080/api/evaluations/results?decision=NON_COMPLIANT"
curl "localhost:8080/api/evaluations/results/export" -o results.csv
```

## 7. 本地验证方法（测试）

`mvn test`，共 14 个用例（`src/test/java/.../tests`）：

| 测试类 | 覆盖点 |
| --- | --- |
| `EventIngestTest` | 合法接入；串行/8 线程并发重复提交仅一条记录；缺字段、非法级别、时间异常、时间缺失、非法 JSON 各自独立错误码 |
| `RuleConcurrencyTest` | 版本单调递增与历史保留；8 线程并发更新无重复版本号、仅一个 active；非法规则拒绝 |
| `ReplayTest` | v1/v2 规则下重放结论变化；指定 v1 重放与首次结果逐位一致；批次快照记录版本；批次 runId 幂等 |
| `ConcurrentUpdateEvaluationTest` | 2 更新者 × 6 评估者并发：每批次内规则版本唯一、行版本与快照一致、判定行数守恒、无重复 (event,rule) |
| `PaginationRetentionTest` | 25 条同毫秒事件页大小 7 深翻 4 页全收集不重不漏；过期数据查询与导出都无法带出 |

测试日志（控制台 / `target/surefire-reports`）对每个用例打印**输入、规则版本和判定依据**，例如：

```
用例[历史重放v1] 规则版本快照={"rule-replay":1}, 非合规数=1,
  结果详情=[ev-r1:v1:NON_COMPLIANT, ev-r2:v1:COMPLIANT]
用例[并发更新+评估] runId=run-ce-... 使用规则版本=v7, 判定行=10, 快照={"rule-conc-eval":7}
用例[同毫秒边界分页] 输入=25条同eventTime事件, 页大小=7, 翻页次数=4, 累计=25
```
