# cc-legal-hold

管理数据保留规则、法律保全和删除资格。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test

## 主要业务规则

### 数据对象与保留规则

- 数据对象包含唯一业务标识（`businessKey`，数据库唯一约束）、类别、创建时间和状态
  （`ACTIVE` / `PENDING_DELETION` / `DELETED`）。创建时间由服务端通过可替换的
  `DomainClock` 生成，客户端不能声明。
- 保留规则按类别配置最低保留天数；同一类别只有一条规则，更新规则后所有资格判断
  立即使用新规则。
- 删除资格由服务端按 `创建时间 + 当前有效规则` 与当前时间比较计算，
  截止时间 = `createdAt + minRetentionDays`（天）。客户端无法直接声明“可删除”。

### 法律保全

- 一个案件可一次把多个对象纳入保全或解除保全；每次操作生成唯一事件号，记录原因
  和生效时间。`hold_event` 只追加、不可修改。
- 一个对象可同时属于多个案件；`hold_membership` 的 `(caseNo, objectId)` 唯一
  约束防止重复保全，同一案件重复纳入幂等跳过。
- 只有全部有效保全都解除后，对象才可能满足删除条件。

### 两阶段删除

1. 申请：服务端重新计算保留期并检查有效保全。任一条件不满足返回 409 及阻断原因；
   通过后对象置为 `PENDING_DELETION`，签发短期有效（默认 10 分钟，可通过
   `legalhold.deletion-token.ttl-minutes` 配置）的一次性随机确认令牌。重新申请会
   作废旧令牌。
2. 确认：在数据库事务内对令牌行和对象行加悲观写锁，再次检查保留规则和全部有效
   保全。期间规则延长、出现新保全或令牌过期都会拒绝确认、令牌立即失效
   （`REJECTED` / `EXPIRED`），对象恢复为 `ACTIVE`。
3. 确认成功只把对象置为不可恢复的终态 `DELETED`，并写入 `DELETE_CONFIRMED`
   审计事件。重复确认返回原结果且 `alreadyConfirmed=true`，不会再次执行删除或
   重复写审计。

### 并发安全

- 保全加入/解除、删除申请/确认都在事务内先对对象行加悲观写锁；多对象操作按
  对象 ID 升序加锁，避免死锁。
- 因此“保全加入”与“删除确认”严格串行化：要么保全先生效（删除被拒绝且令牌
  失效），要么删除先生效（对象 `DELETED` 后保全无法再加入），不会出现“已有
  有效保全部件却删除成功”。

### 时间来源

- 生产环境使用 `SystemDomainClock`（`Instant.now()`）；测试使用
  `MutableTestClock` 可控推进时间，覆盖保留届满、令牌过期、规则变更等场景。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/objects` | 登记数据对象（body：`businessKey`,`category`） |
| POST | `/api/objects/retention-rules` | 新增/更新类别的最低保留天数 |
| GET | `/api/objects/{businessKey}/eligibility` | 查询删除资格及解释：保留截止时间、是否满足保留期、所有有效保全案件、阻断原因列表 |
| POST | `/api/legal-holds/apply` | 案件对多个对象纳入保全（body：`caseNo`,`businessKeys`,`reason`） |
| POST | `/api/legal-holds/release` | 案件解除对多个对象的保全 |
| POST | `/api/deletions/request/{businessKey}` | 申请删除，返回一次性令牌和过期时间 |
| POST | `/api/deletions/confirm/{token}` | 确认删除；重复确认返回原结果 |

资格被保留期或保全阻断时返回 `409 Conflict`，响应体 `reasons` 给出全部阻断原因。

## 测试

- `RetentionEligibilityTest`：保留期届满边界、规则更新即时生效、缺少规则阻断。
- `LegalHoldServiceTest`：多对象保全事件、同案幂等、多案件全部解除才可删除、
  无保全解除失败。
- `DeletionServiceTest`：两阶段删除全流程、重复确认幂等、期间新增保全/规则延长/
  令牌过期均拒绝并失效令牌、重新申请作废旧令牌、审计仅写一次。
- `HoldDeletionConcurrencyTest`：多线程并发“保全加入 vs 删除确认”，断言绝不出现
  有效保全与 `DELETED` 并存。
- `DeletionFlowControllerTest`：HTTP 层端到端流程、409 阻断解释、参数校验。
