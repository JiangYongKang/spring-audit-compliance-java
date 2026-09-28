# spring-audit-compliance-java

本地审计日志与合规规则评估服务：操作事件的接入、校验、幂等持久化、规则判定、
历史重放与查询导出，全程可追溯、可验证。

## 事件模型

`AuditEvent`（`com.github.highcumontoa.audit.model`）：

| 字段 | 说明 |
|---|---|
| `eventId` | 事件唯一标识，**幂等去重键**：重复提交同一 eventId 返回 `DUPLICATE`，不产生重复记录 |
| `subject` | 操作主体（必填） |
| `action` | 操作类型（必填） |
| `occurredAt` | 事件发生时间（必填，UTC Instant） |
| `severity` | 严重级别，合法值 `LOW/MEDIUM/HIGH/CRITICAL` |
| `attributes` | 扩展属性（规则关键字匹配的载体） |

接入结果 `IngestResult` 三态稳定：`ACCEPTED / DUPLICATE / REJECTED`。
拒绝原因 `RejectionReason` 可区分：

- `MISSING_FIELD`：缺少关键字段；
- `TIME_ANOMALY`：发生时间超出允许的未来时钟偏移（默认 5 分钟）或早于最远过去时间（默认 365 天）；
- `INVALID_SEVERITY`：严重级别非法。

REST 上拒绝返回 `422` 且响应体带 `rejectionReason` 与 `detail`，参数错误返回 `400`。

## 规则版本与重放

- 规则 `ComplianceRule` 不可变；`POST /api/rules` 新增（版本 1），`PUT /api/rules/{id}` 更新（版本 +1，单调递增）。
- 全部规则保存在原子引用指向的不可变 Map 中，更新是一次引用替换；
  评估/重放开始时取一次快照，**并发更新规则时评估永远读不到半更新状态**。
- 判定记录 `EvaluationRecord` 携带 `ruleId / ruleVersion / basis（判定依据）/ replayRunId`：
  - `replayRunId = 0`：事件接入时的首次评估；
  - `replayRunId >= 1`：`POST /api/replay?from=...&to=...` 触发的历史重放批次，整批共用同一规则快照。
- **可复现性**：同一规则版本 + 同一事件集合，重放产出的判定结果（decision/ruleVersion/basis）完全一致；
  规则变化后重放，可从记录中直接看出每次判定使用的规则版本。

## 数据保留策略

- 保留期由 `audit.retention-days` 配置（默认 90 天），`RetentionPolicy.cutoff(now) = now - retention`。
- 导出 `GET /api/export` 会把请求的 `from` 钳制到 cutoff：**早于 cutoff 的过期数据一律不带出**，
  即使调用方显式请求更早的时间。

## 查询与分页

- `GET /api/events?subject=&from=&to=&decision=&pageSize=&cursor=` 按主体/时间/判定结果过滤。
- 排序键固定为 `(occurredAt, eventId)`，采用 keyset（游标）分页：游标编码上一页最后一条的排序键，
  后续页只取排序键严格大于游标的记录。基于写时快照遍历，**并发写入时不漏边界记录、不重复边界记录**。

## 本地持久化

事件与判定记录以 JSONL 追加写入 `audit.data-dir`（默认 `./audit-data`）下的
`audit-events.jsonl` 与 `evaluation-records.jsonl`，服务重启后自动加载。

## 本地验证

```bash
# 运行全部测试（覆盖：重复提交、可区分拒绝、并发更新与评估、历史重放、边界分页、保留策略导出）
mvn test

# 启动服务（随机端口，见启动日志）
mvn spring-boot:run

# 接入事件
curl -X POST localhost:8080/api/events -H 'Content-Type: application/json' -d '{
  "eventId":"evt-1","subject":"alice","action":"export",
  "occurredAt":"2026-09-28T09:00:00Z","severity":"HIGH",
  "attributes":{"note":"customer data"}}'

# 新增 / 更新规则（版本 1 -> 2）
curl -X POST localhost:8080/api/rules -H 'Content-Type: application/json' -d '{
  "ruleId":"R1","version":0,"subjectPattern":null,"action":"export",
  "minSeverity":"HIGH","keyword":null,"description":"high severity export"}'

# 对时间窗口历史重放
curl -X POST 'localhost:8080/api/replay?from=2026-09-01T00:00:00Z&to=2026-09-29T00:00:00Z'

# 查看某事件的全部判定轨迹（含各批次使用的规则版本与依据）
curl localhost:8080/api/events/evt-1/evaluations

# 分页查询与导出（导出受保留策略约束）
curl 'localhost:8080/api/events?subject=alice&pageSize=10'
curl 'localhost:8080/api/export?subject=alice'
```

单测日志会打印测试输入、规则版本与判定依据（`EvaluationService` 的 `evaluate`/`replay` 日志，
以及各测试类的 `log.info`），可用 `mvn test | grep -E "ruleVersion|basis"` 查看。
