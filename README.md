# cc-legal-hold

管理数据保留规则、法律保全和删除资格，并支持案件范围版本化变更、双批准批量释放审核。

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

### 案件范围版本（不可变）

- 案件范围按条件生成版本，条件为三类选择器的并集：
  - 显式对象业务标识 `businessKeys`；
  - 类别集合 `categories`；
  - 创建时间窗口 `createdAfter` / `createdBefore`（含边界）。
  - 选择器仅在至少提供一个维度（类别或时间边界）时生效；只给显式标识时范围恰好
    等于这些标识，不会扩散到全部对象。
- 范围条件一经落库（`case_scope_version.criteria_json`）不可修改。扩大或缩小范围
  都必须新建版本，新版本生效时旧版本置为 `SUPERSEDED` 永久保留，绝不覆盖。
- 每个版本固化相对基线版本的成员差异：`ADDED`（新增）、`REMOVED`（移除）、
  `UNCHANGED`（不变），并记录三者计数；任意两个历史版本之间都可计算差异。
- 一个案件同一时刻只允许一个进行中（`COMPUTING` 或 `PENDING_APPROVAL`）的变更，
  保证版本严格按序生效。

### 双批准与整批拒绝

- 新建与扩大范围（无移除对象）无需审批，计算完成即生效。
- 缩小范围或关闭案件必须由**两名不同人员**批准，且批准人不能是变更申请人；
  同一人重复批准只计一次（`scope_approval` 对 `(change_no, approver)` 唯一）。
- 缩围/关闭提交、进入 `PENDING_APPROVAL` 时，为每个待释放对象固化“约束快照”：
  其他案件保全集合、保留规则是否存在、保留截止时间。
- 第二名批准人确认（终确认）时，在持有对象悲观写锁的同一事务内逐项重新核验：
  - 本案对该对象的有效保全是否仍存在；
  - 是否新增/消失了其他案件的保全；
  - 保留规则是否新增/删除；
  - 保留规则变化是否改变了截止时间；
  - 对象是否已被删除。

  任一对象任一条件变化，**整批拒绝**：版本置 `REJECTED` 并记录逐对象原因，
  不释放任何对象、不推进当前版本，且该版本不能再被批准。
- 关闭案件经双批准生效后，案件置 `CLOSED`，释放全部对象且不再接受范围变更。

### 分批计算、安全重试与原子生效

- 大范围按对象 ID 游标分批扫描（批大小由请求 `batchSize` 指定，默认
  `legalhold.scope.batch-size=200`）。每批在**独立事务**内完成“锁版本行 →
  读游标 → 扫描一批 → 写入命中成员 → 推进游标”。
- 游标与本批成员同事务提交：执行失败只回滚当前批次，重试从已提交游标继续；
  服务重启后由启动恢复器（`ScopeRecoveryRunner`，监听 `ApplicationReadyEvent`）
  自动续算所有 `COMPUTING` 版本。
- 计算期间版本停留在 `COMPUTING`，成员只暂存在 `scope_version_member`，
  对外的有效保全投影 `hold_membership` 不变——外部只能看到上一个完整生效版本。
- 生效只在单个事务内切换投影：一次性对全部受影响对象按 ID 升序加悲观写锁，
  然后批量新增/删除 `hold_membership` 并写只追加的 `hold_event`。因此绝不会出现
  “只有部分对象被保全或释放”。计算窗口内已被删除的对象在生效时跳过并从版本成员
  剔除，不写纳入事件。

### 幂等与并发安全

- 变更业务号 `changeNo` 全局唯一：重复提交返回同一版本，不产生第二条变更；
  跨案件复用同一业务号返回 409。唯一约束在数据库层兜底，并发重复提交映射为 409。
- 范围变更事务先对案件行（`case_scope`）加悲观写锁，串行化同一案件的扩围、
  缩围与关闭。
- 生效/释放与删除确认一样，先对对象行按 ID 升序加悲观写锁，因此“范围生效/释放”
  与“删除确认”严格串行化：要么保全先生效（删除被拒绝且令牌失效），要么删除先生效
  （对象 `DELETED` 后不再纳入），不会出现“对象已删除却仍处于有效保全范围内”。
- 每个生效事件携带 `changeNo`，`hold_event` 对 `(change_no, object_id)` 唯一，
  生效逻辑重放安全、不重复写事件、不重复增删成员投影。

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

### 时间来源

- 生产环境使用 `SystemDomainClock`（`Instant.now()`）；测试使用
  `MutableTestClock` 可控推进时间，覆盖保留届满、令牌过期、规则变更、审批窗口
  条件变化等场景。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/objects` | 登记数据对象（body：`businessKey`,`category`） |
| POST | `/api/objects/retention-rules` | 新增/更新类别的最低保留天数 |
| GET | `/api/objects/{businessKey}/eligibility` | 查询删除资格及解释：保留截止时间、是否满足保留期、所有有效保全案件、阻断原因列表 |
| POST | `/api/legal-holds/apply` | 案件对多个对象手工纳入保全（body：`caseNo`,`businessKeys`,`reason`） |
| POST | `/api/legal-holds/release` | 案件手工解除对多个对象的保全 |
| POST | `/api/deletions/request/{businessKey}` | 申请删除，返回一次性令牌和过期时间 |
| POST | `/api/deletions/confirm/{token}` | 确认删除；重复确认返回原结果 |
| POST | `/api/cases/{caseNo}/scope-changes` | 提交范围变更（body 见下），按条件生成不可变版本；`changeNo` 幂等 |
| GET | `/api/cases/{caseNo}/scope-changes/{changeNo}` | 查询单个变更版本（计算中断时先续算） |
| GET | `/api/cases/{caseNo}` | 案件状态、当前生效版本号与全部版本历史 |
| GET | `/api/cases/{caseNo}/versions/{toVersionNo}/diff?from={fromVersionNo}` | 两版本成员差异；省略 `from` 与空版本比较 |
| POST | `/api/cases/{caseNo}/scope-changes/{changeNo}/approvals` | 提交一个批准（body：`approver`,`comment`）；第二名不同人员批准触发终确认与生效 |
| GET | `/api/cases/{caseNo}/scope-changes/{changeNo}/approvals` | 审批进度：所需人数、已批准人列表、是否生效/拒绝及拒绝原因 |
| GET | `/api/cases/holds/objects/{businessKey}` | 对象当前受到的全部保全及来源（案件、是否范围版本管理、生效版本号、来源变更号） |
| GET | `/api/cases/holds/objects/{businessKey}/release-reasons` | 对象的保全/释放事件流，`RELEASE` 给出释放原因与生效时间 |

### 范围变更请求体

```json
{
  "changeNo": "CHG-2026-001",
  "requestedBy": "alice",
  "reason": "诉讼范围调整",
  "closeCase": false,
  "batchSize": 200,
  "criteria": {
    "businessKeys": ["obj-1", "obj-2"],
    "categories": ["DOC", "MAIL"],
    "createdAfter": "2026-01-01T00:00:00Z",
    "createdBefore": null
  }
}
```

- `closeCase=true` 关闭案件：条件被忽略、目标范围强制为空，必须双批准。
- 缩围/关闭的终确认条件变化返回 `409 Conflict`，响应体 `reasons` 给出逐对象变化
  原因；此时查询审批进度可见 `rejected=true`。

资格被保留期或保全阻断、整批拒绝、重复业务号/并发冲突均返回 `409 Conflict`，
响应体 `reasons` 给出全部原因。

## 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `legalhold.scope.batch-size` | `200` | 范围计算每批扫描对象数（请求体 `batchSize` 可覆盖） |
| `legalhold.deletion-token.ttl-minutes` | `10` | 删除确认令牌有效期（分钟） |

## 测试

- `RetentionEligibilityTest`：保留期届满边界、规则更新即时生效、缺少规则阻断。
- `LegalHoldServiceTest`：多对象保全事件、同案幂等、多案件全部解除才可删除、
  无保全解除失败。
- `DeletionServiceTest`：两阶段删除全流程、重复确认幂等、期间新增保全/规则延长/
  令牌过期均拒绝并失效令牌、重新申请作废旧令牌、审计仅写一次。
- `HoldDeletionConcurrencyTest`：多线程并发“保全加入 vs 删除确认”，断言绝不出现
  有效保全与 `DELETED` 并存。
- `CaseScopeServiceTest`：条件生成不可变版本、扩围直接生效且旧版本 SUPERSEDED、
  版本差异、显式标识/类别/创建时间窗口选择器、缩围/关闭双批准、申请人与同人重复
  批准规则、变更业务号幂等、其他案件保全出现或保留规则延长时整批拒绝、释放原因、
  对象全部保全来源、待批期间禁止新变更。
- `ScopeBatchResumeTest`：分批游标推进、批间“崩溃”后启动恢复续算、计算期间无部分
  保全、恢复后只有一个完整生效版本、恢复重放幂等、计算窗口内对象被删除时从生效
  版本剔除。
- `ScopeDeletionConcurrencyTest`：多线程并发“缩围释放/关闭确认 vs 删除确认”及
  “扩围生效 vs 删除确认”，断言有效保全范围内对象绝不会被删除。
- `CaseScopeControllerTest`：范围变更 HTTP 端到端流程、双批准、版本差异、对象全部
  保全与释放原因查询、幂等、整批拒绝 409、参数校验。
- `DeletionFlowControllerTest`：HTTP 层端到端流程、409 阻断解释、参数校验。
