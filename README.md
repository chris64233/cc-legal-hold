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

### 案件范围版本化（不可变版本）

- 案件范围按**对象标识、类别、创建时间区间**三类条件的并集生成：命中显式
  `businessKeys`，或类别命中 `categories` 且创建时间落在
  `[createdFrom, createdTo)`（边界可空表示不限）的对象进入目标范围。
- 每次扩大或缩小范围都**创建一个新版本**（`case_scope_version`，案件内版本号
  递增），旧版本永不覆盖、永不修改；目标集合物化为不可变的
  `case_scope_member` 快照，并按相对基准版本标记 `ADDED / RETAINED / REMOVED`。
- 版本状态机：`PENDING_MATERIALIZE`（分批计算中，对外不可见）→
  纯扩围直接 `EFFECTIVE`；存在移除对象（`SHRINK`）或关闭案件（`CLOSE`，
  目标范围为空集）→ `PENDING_APPROVAL` → `EFFECTIVE` 或 `REJECTED`；
  被更新生效版本取代的旧版本置为 `SUPERSEDED`。案件任一时刻只有一个
  `EFFECTIVE` 版本，对外只能看到完整生效的范围。
- 与目标范围完全一致（无新增也无移除）的变更直接判为 `REJECTED`，不占用案件，
  也不触碰任何保全。

### 双人审批与释放前最终复核

- 缩小范围、关闭案件都必须由**两名互不相同、且都不是申请人**的人员分别
  `APPROVE`；同一审批人重复投票幂等，只计一票。任一人 `REJECT` 立即整批拒绝。
- 进入待审批时，对每个待释放对象写入**约束快照**（其他案件保全清单、保留规则
  天数与届满状态、案件当前生效版本号、本案保全是否仍在）。
- 第二名批准触发**最终确认**：在同一事务内对每个待释放对象重新核验，期间出现
  其他案件保全、保留规则或届满状态变化、案件当前版本变化、本案保全已不存在等
  任一情况，都判定“条件变化”，**整批拒绝**——一个对象都不释放。
- 复核通过后才删除 `hold_membership` 并为每个对象追加 `RELEASE` 事件（含变更
  业务号与版本号）；关闭案件同时置 `closed`，之后不得再变更范围。

### 大批量分批计算、安全重试与原子可见性

- 范围计算按对象 ID 键集分页，每批一个**独立短事务**提交，游标记录在版本行
  （`materialize_after_id` / `removed_after_id`），目标对象 ID 高水位在变更
  开始时固化，保证重试扫描的是同一集合。
- 每批写入都靠 `(版本, 对象)` 唯一约束去重；执行失败或服务重启后，用**同一
  变更业务号重复提交**或调用 `resume` 即可安全续跑，不产生重复成员、重复保全。
- 物化完成前版本一直是 `PENDING_MATERIALIZE`，对外不生效；生效（建立/释放保全）
  在定稿事务内整批完成。因此绝不会出现“只有部分对象被保全或释放”的中间态。
- 批大小与单调用批次数可配：
  `legalhold.scope.materialize-batch-size`（默认 500）、
  `legalhold.scope.max-batches-per-call`（默认 20）。

### 范围变更的幂等与并发安全

- 每个变更由客户端提供唯一**变更业务号 `changeNo`**（数据库唯一约束）。重复
  提交只会续跑/返回同一个版本（响应中 `idempotentReplay=true`），绝不产生第二个
  版本或重复效果；同一 `changeNo` 不能用于不同案件。
- 每个审批事件按 `(changeNo, reviewer, vote)` 唯一约束幂等。
- 所有范围变更与审批生效都先对**案件行加悲观写锁**，同案件的并发扩围、缩围与
  关闭严格串行：一个变更未终结（仍在物化或待审批）时，其他变更返回 409。
- 生效时再按对象 ID 升序对对象行加悲观写锁、对保留规则行加锁，与删除确认、规则
  更新串行。因此并发扩围/缩围/删除确认下，处于有效保全范围内的对象绝不会被删除
  （见 `ScopeConcurrencyTest`）。

### 范围相关查询

- 版本差异：任意两个版本间的新增/移除/保留对象（默认对比相邻版本）。
- 对象所受全部保全：跨案件列出，含建立该保全的范围版本与案件是否已关闭。
- 释放原因：对象被各案件释放的 `RELEASE` 事件（变更业务号、版本号、原因、时间）。
- 审批进度：所需批准数、已批准的不同人员、全部投票事件、拒绝原因。

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
| POST | `/api/case-scopes/changes` | 提交范围变更（body：`caseNo`,`changeNo`,`reason`,`createdBy`,`criteria`）；扩围/缩围自动判定，`changeNo` 幂等 |
| POST | `/api/case-scopes/close` | 关闭案件（目标范围为空，整案释放，双人审批） |
| POST | `/api/case-scopes/changes/{changeNo}/resume` | 续跑大批量物化（分批执行/重启恢复），幂等 |
| POST | `/api/case-scopes/approvals` | 提交审批事件（`changeNo`,`reviewer`,`vote=APPROVE/REJECT`,`comment`）；事件幂等 |
| GET | `/api/case-scopes/{caseNo}/versions` | 案件的全部不可变版本 |
| GET | `/api/case-scopes/{caseNo}/versions/current` | 当前唯一生效版本 |
| GET | `/api/case-scopes/{caseNo}/versions/{versionNo}` | 指定版本详情（含条件快照与增/留/删计数） |
| GET | `/api/case-scopes/{caseNo}/diff?from=&to=` | 版本差异（新增/移除/保留对象），缺省对比相邻版本 |
| GET | `/api/case-scopes/changes/{changeNo}/approval-progress` | 审批进度：所需/已批人数、审批人、投票事件、拒绝原因 |
| GET | `/api/case-scopes/objects/{businessKey}/holds` | 对象当前受到的全部保全（跨案件、含版本与案件关闭标记） |
| GET | `/api/case-scopes/objects/{businessKey}/release-reasons` | 对象被各案件释放的原因列表 |

资格被保留期或保全阻断时返回 `409 Conflict`，响应体 `reasons` 给出全部阻断原因。

## 测试

- `RetentionEligibilityTest`：保留期届满边界、规则更新即时生效、缺少规则阻断。
- `LegalHoldServiceTest`：多对象保全事件、同案幂等、多案件全部解除才可删除、
  无保全解除失败。
- `DeletionServiceTest`：两阶段删除全流程、重复确认幂等、期间新增保全/规则延长/
  令牌过期均拒绝并失效令牌、重新申请作废旧令牌、审计仅写一次。
- `HoldDeletionConcurrencyTest`：多线程并发“保全加入 vs 删除确认”，断言绝不出现
  有效保全与 `DELETED` 并存。
- `CaseScopeServiceTest`：按条件生成不可变版本、新旧版本并存与差异、扩围即时生效、
  缩围/关案双人不同人员批准、申请人不能自批、单人拒绝整批拒绝、最终复核发现保留
  规则变化或新增其他案件保全时整批拒绝、`changeNo` 与审批事件幂等、同案并发变更
  互斥、空范围拒绝、已删除对象不入范围。
- `ScopeBatchResumeTest`：批大小为 2、单调用只跑一批，验证大批扩围/缩围跨多批
  续跑、重启后用同一 `changeNo` 安全重试不重复、物化期间绝不部分释放、双人批准后
  整批释放。
- `ScopeConcurrencyTest`：多线程并发“关闭生效（整批释放）vs 删除申请/确认”，
  断言处于有效保全范围内的对象绝不会被删除，整批拒绝时所有对象仍受保全。
- `CaseScopeControllerTest`：范围变更/双人审批/差异/审批进度/对象保全与释放原因
  的 HTTP 端到端流程、幂等重放标记、申请人自批 409、空条件 409。
- `DeletionFlowControllerTest`：HTTP 层端到端流程、409 阻断解释、参数校验。
