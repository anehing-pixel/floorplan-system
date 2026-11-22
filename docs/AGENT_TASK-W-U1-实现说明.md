# AGENT_TASK-W-U1 实现说明（二手车库存垫资 USED 改造 · 骨架层）

| 项 | 内容 |
|---|---|
| 包 | W-U1（M-U1：资质 + USED 建账），卡面 = D12 §W-U1（`docs/二手车库存垫资改造-12-任务拆分与并行开发方案.md:133-153`） |
| 工作位 | `C:/Users/Administrator/Documents/GitHub/wt-used-wu1`，分支 `used/wu1`，基线 `ea21473`（标签 `base-used-dev-20261122`） |
| 覆盖 | R1.1–R1.5、R2.1–R2.4、R3.1（字段定义与建账侧）；UC-R1-1…7、UC-R2-1…5（D06 `:102-109`） |
| 提交 | 18 笔（清单见 §9），全部 pathspec 形式，未 push、未 merge、未打标签 |
| **验证状态** | **本轮按用户指令未编译、未冒烟、未连库**（§0 第一铁律）。下文所有"已实现"均指代码已落档并经静态交叉核对，不等于跑通 |
| 交接对象 | 测试工程师（§8 给了运行条件与验收建议）／收口方（§5–§7 是需要裁决或需要补规格的条目） |

---

## 1. 一句话结论

迁移 10 个、新建表 8 张、domain 41 个新文件、application 3 个用例服务、infrastructure 6 个 JdbcTemplate 仓储、web 8 个端点（＝D07 §7.1 给 W-U1 的八行，一字不多不少）已落档；
**R2 车辆侧（建账／车况票／换证／里程勾稽）只有服务方法，没有任何 HTTP 入口**，因为 D07 §7 里没有 R2 端点小节（D12 `:150-153` 把它列为开放项 R8，本包不自派路径）；
所有未签字的数值一律不写进 Java，改由 `used_config_params` 读，读不到按登记的缺省方向处理（§6 逐条列出）。

---

## 2. 规格 ↔ 实现对照

### 2.1 迁移（卡面 10 个 = 实际 10 个，V1–V26 未改一字节）

| 号 | 卡面/D04 §2 的用途 | 实际落档对象 | 一致性 |
|---|---|---|---|
| V40 | USED 字典基座 + `vehicle_type`/`purchase_channel` CHECK（D04 `:64`） | `V40__used_domain_dictionary.sql`（59 行）：2 条 `ADD CONSTRAINT`（均允许 NULL）+ 2 条 `COMMENT ON`；**不建枚举字典表** | 一致；字典表是 D04 §2.1 的条件项，未建，理由写在文件 §3（`:50-59`） |
| V41 | USED 可调参数表（D04 `:65`，"若走 DB 而非 yml，见 §11"） | `V41__used_config_params.sql`（71 行）：建表 + `uk_ucp_active_key` + `idx_ucp_status_key`；**零条种子数据** | 一致，但"DB 还是 yml"是 `[待定源 D-06]` → 见 §6-① |
| V45 | 车商资质三表（D04 `:66`） | `V45__dealer_qualification.sql`（150 行）：`dealer_licenses`／`dealer_auction_memberships`／`dealer_compliance_checks` + `uk_dealer_license_active`、`uk_dlic_dealer_type_no`、`uk_dchk_request_no`（部分唯一）+ 4 条查询索引 | 一致 |
| V46 | `dealers` 加列 + 回填（D04 `:67`） | `V46__dealer_profile_extension.sql`（122 行）：`dealers` 加 13 列（与 D04 `:737` 的 13 列逐一对应） + `ck_dealers_admission_state` + **`dealer_score_reviews` 建表**（含 `uk_dsr_idem`、`uk_dsr_pending`） | **超出 D04 §2.1 的表清单** → 见 §4-② |
| V47 | `suppliers` 扩展（D04 `:68`） | `V47__dealer_supplier_rating.sql`（51 行）：`auction_house_code`/`member_no`/`settlement_account_id`/`supplier_grade`/`admission_status`/`guarantee_ability_effective` + CHECK | 与 D04 `:738` 逐列对齐 |
| V50 | `vehicle_units` USED 列（D04 `:69`、§5.1 `:688-731`） | `V50__vehicle_used_attributes.sql`（175 行）：22 列 + `ck_vu_mileage_unknown` 等 4 条 CHECK + `COMMENT ON`（含里程覆盖合并口径） | 列集合与 D04 §5.1 逐行对齐；`vehicle_units.mileage_km` **未建**（D04 `:715` 明令禁止） |
| V51 | `vehicle_condition_reports`（D04 `:70`） | `V51__vehicle_condition_report.sql`（73 行，**文件名单数**，与 D04 `:70` 同名）：建表 + `uk_vcr_report_no`（`WHERE report_no IS NOT NULL` 部分唯一）+ `idx_vcr_vin_date` | 一致；`report_no` 可空 ⟹ 自盘票无库侧兜底，见 §5-④ |
| V52 | `inspection_certificates` + `certificate_custody_events`（D04 `:71`） | `V52__inspection_certificate.sql`（146 行）：两张表 + `uk_cert_key` + 6 条索引 | 一致 |
| V53 | 一车一证部分唯一索引、换证链、`encumbrance` 记录（D04 `:72`） | `V53__certificate_constraint.sql`（70 行）：**`uk_cert_active_vin`（门禁项，实存）** + `predecessor_id` 自环 CHECK + `vehicle_units.inspection_certificate_id` 物理 FK | 一致 |
| V54 | 批次表权威源/順位/瑕疵结论 + `COMMENT ON` 修正（D04 `:73`） | `V54__collateral_multi_source.sql`（192 行）：`collateral_registration_batches`、`security_records`、`financing_lien_checks` 加列与 CHECK | 与 D04 `:746-748` 对齐；**`vehicle_units.authoritative_source_type` 未建**（D04 `:708` 已撤销该列，Q27 单一权威源） |

- 号段纪律：V42–V44、V48–V49 是 D04 §2.1 有意保留的段内缓冲，本包未落任何文件；`V55`+ 归 W-U2。
- **本包没有删除任何既有库约束**：15 处 `DROP CONSTRAINT IF EXISTS` 的目标名在基线 `base-used-dev-20261122` 的迁移目录里**全部零命中**（逐个 `git grep` 复核过），它们只为本包自身 `ADD CONSTRAINT` 的幂等而写（PG 没有 `ADD CONSTRAINT IF NOT EXISTS`）。
- 被 ALTER 的 6 张表（`vehicle_units`/`dealers`/`suppliers`/`collateral_registration_batches`/`security_records`/`financing_lien_checks`）在基线全部实存（分别来自 V1/V1/V7/V10/V1/V2）。

### 2.2 表（卡面 6 张 → 实际 8 张）

| 表 | D04 §2.1 计数 | 本包实建 | 说明 |
|---|---|---|---|
| `dealer_licenses` | ✅ | ✅ | — |
| `dealer_auction_memberships` | ✅ | ✅ | — |
| `dealer_compliance_checks` | ✅ | ✅ | — |
| `vehicle_condition_reports` | ✅ | ✅ | — |
| `inspection_certificates` | ✅ | ✅ | — |
| `certificate_custody_events` | ✅ | ✅ | — |
| `used_config_params` | 条件项 +1（D04 `:100` 下方"两个条件项"） | ✅ | 采「DB 参数表」这一支，因 V41 文件名已在冻结号段内 |
| `dealer_score_reviews` | **未列**（全设计集 grep 零命中） | ✅ | G-A4 需要 `scored_by`/`confirmed_by` 两列证据，`dealers` 的两个分数列承载不了 → 见 §4-② |

**表数门槛的实际影响**（不改脚本，只登记）：D04 §2.1 `:104` 给 W-U1 的行是 `V45(3)、V51(1)、V52(2) = 6`，并行同基线分支门槛 **73**（`67 + 6`）。本分支实际是 8 张 ⟹ **`FLOORPLAN_PUBLIC_TABLES` 应为 75**（73 + V41 条件项 + `dealer_score_reviews`）。任何人按 73 跑 `agent_env.sh check` 都会得到"表数不符"，那是**数字源落后**，不是漏建/重复建表。此结论只能靠合入后回 `public` 全量实数互校，本轮未连库。

### 2.3 端点（8 : 8，路径与 D07 §7.1 `:555-562` 逐字相同）

| # | D07 规格 | 实现方法 | 权限点（实际） | 幂等实现 | 偏差 |
|---|---|---|---|---|---|
| 1 | `POST /api/dealers/{id}/licenses`，`DEALER_QUALIFICATION_MAINTAIN`，`DLIC:{dealerId}:{type}:{no}` | `DealerUsedQualificationController#upsertLicense` | 同规格 | 自然键 `uk_dlic_dealer_type_no` + 同键同指纹回放 / 异指纹订正 | 无 |
| 2 | `GET /api/dealers/{id}/licenses`，`DEALER_READ` | `#listLicenses` | **不标注解** | — | §4-④ |
| 3 | `POST /api/dealers/{id}/auction-memberships` | `#upsertAuctionMembership` | `DEALER_QUALIFICATION_MAINTAIN` | `uk_dmem`（dealer+house+member_no） | 无 |
| 4 | `GET /api/dealers/{id}/auction-memberships?status=` | `#listAuctionMemberships` | 不标注解 | — | §4-④；非法 `status` 回 400 而非空列表 |
| 5 | `POST /api/dealers/{id}/compliance-checks`，`COMPLIANCE_CHECK_DONE` | `#recordComplianceCheck` | **`DEALER_COMPLIANCE_CHECK`** | `uk_dchk_request_no`（部分唯一） | §4-③ |
| 6 | `POST /api/dealers/{id}/scores`，`DEALER_SCORE_SET`（二人），`DSCR:{dealerId}:{ts}` | `#scoreDimension` | `DEALER_SCORE_SET` | 请求头 `Idempotency-Key` 优先，其次体字段；**缺失不合成键** | 「二人」不在此端点，见 §5-③ |
| 7 | `GET /api/dealers/{id}/admission`，`DEALER_READ` | `#getAdmission` | 不标注解 | — | §4-④；只算不落 |
| 8 | `POST /api/dealers/{id}/admission/recalculate`，`DEALER_ADMISSION_MANAGE` | `#recalculateAdmission` | 同规格 | 幂等不适用（结论可重算） | 不发 `DEALER_ADMISSION_STATE_CHANGED`†，见 §5-② |

未认领的相邻端点族（避免误解为本包漏做）：D07 §7.3 `:586-591` 的 `POST /api/certificates*`（`CERTIFICATE_RECORD`/`_PLEDGE`/`_RELEASE`/`_LOST_DECLARE`/`_REISSUE_APPROVE`/`CERT_READ`）属 R5/R7，归 W-U4；本包因此**没有新增 `CERTIFICATE_*` 权限点**。

### 2.4 错误码与权限点（新增，均有定源）

- `ErrorCode`（domain）新增 8 个：`QUAL_001/002/003`（D07 §7.1 各行"主要错误码"列）、`CERT_001–005`（D00 `:469` 码族 + D07 §8 `:727-731` 的 HTTP 映射，逐一照抄 404/409/422）。**未新增任何码位的自定义语义**；`QUAL_004` 的文档冲突见 §4-⑥。
- `Permission` 新增 4 个点：`DEALER_QUALIFICATION_MAINTAIN`、`DEALER_COMPLIANCE_CHECK`、`DEALER_SCORE_SET`、`DEALER_ADMISSION_MANAGE`（D00 §11 定源）。`RISK_GRADE_ADJUST` 是既有常量，本包只把行尾 `;` 改成 `,`。
- `UserRole` 授权（+15/-2，两处 `-` 都是同一行的续写，**没有收回任何既有权限**）：
  - `SYSTEM_ADMIN` += 上述 4 个点；
  - `CREDIT_REVIEWER` += 其中 3 个，**刻意不给 `DEALER_COMPLIANCE_CHECK`**（P-7 分离：核查录入与授信判定不得同源）；
  - 未新增 `COMPLIANCE_OFFICER` 角色 ⟹ `POST /compliance-checks` 当前只有 SYSTEM_ADMIN 打得通，见 §5-⑤。

---

## 3. 落档文件清单（按模块，与 `git log --name-status base-used-dev-20261122..HEAD` 计数一致）

**合计：新增 63 个文件（代码与迁移 62 + 本说明 1），修改 4 个既有文件（`Permission`、`UserRole`、`ErrorCode`、`SqlStructureSelfCheck`）。**

| 模块 | 新增 | 修改 | 明细 |
|---|---|---|---|
| `domain` | 41 | 3 | `model` 31（**实体／值对象 12**：`DealerLicense`、`AuctionMembership`、`ComplianceCheck`、`DealerQualification`、`DealerScoreReview`、`InspectionCertificate`、`CertificateCustodyEvent`、`VehicleConditionReport`、`UsedVehicleAttributes`、`UsedConfigParam`、`MileageSample`、`AdmissionDecision`；**枚举 19**：`AdmissionState`、`LicenseStatus`、`DealerLicenseType`、`AuctionMembershipStatus`、`ComplianceCheckType`、`ComplianceResult`、`ComplianceCheckSource`、`ScoreReviewStatus`、`ScoreDimension`、`ConditionReportType`、`MileageSource`、`RepairHistoryFlag`、`SaleChannel`、`PurchaseChannel`、`RegistryCheckSource`、`EncumbranceType`、`CertificateStatus`、`CustodyEventType`、`CustodyActorType`）、`repository` 6 端口、`service` 4 策略/状态机（`DealerAdmissionPolicy`、`MileageResolutionPolicy`、`RepairHistoryResolutionPolicy`、`CertificateStateMachine`）；改：`Permission`、`UserRole`、`ErrorCode` |
| `application` | 3 | 0 | `DealerQualificationApplicationService`、`DealerRatingApplicationService`、`UsedVehicleMasterApplicationService`（941 行）——本包唯一 `@Transactional` 层 |
| `infrastructure` | 16 | 1 | 迁移 10 + `Jdbc` 仓储 6（`JdbcDealerQualification`／`JdbcDealerScoreReview`／`JdbcInspectionCertificate`／`JdbcVehicleConditionReport`／`JdbcUsedVehicleAttributes`／`JdbcUsedConfig`）；改：`SqlStructureSelfCheck`（+47/-0，登记 8 张表） |
| `web` | 2 | 0 | `DealerUsedQualificationController`、`UsedQualificationRequests` |
| `docs` | 1 | 0 | 本文件 |

未改动且刻意不改的共用文件：`ApiRequests.java`（并行包同改会撞车，见 §7-⑦）、`JdbcVehicleUnitRepository`、`VehicleController`、`PurchaseOrderApplicationService`、父 `pom.xml`、`gateway/application.yml`、`static/js/app.js`、`PROGRESS.md`、`smoke-test.py`、`run-microservices.sh`、`agent_env.sh`。

---

## 4. 自查发现的文档 ↔ 文档矛盾（本包已按较严/定源一侧实现，每条给证据位置）

**① V41 参数表的载体选型未签字。** D04 `:65` 把 V41 写成"若走 DB 而非 yml，见 §11"，而 §11 是 `[待定源 D-06]`；V41 这个**文件名**已在冻结号段（卡面 10 个迁移之一）。取舍：建表并读 DB，但**不预置任何 ACTIVE 种子行**，读不到一律走登记的缺省方向。若裁决改回 yml，本包只需把 `JdbcUsedConfigRepository` 换成 `@ConfigurationProperties` 实现，5 个调用点的语义与缺省方向不变。

**② `dealer_score_reviews` 是 D04 §2.1 未列的第 8 张表。** 依据：D06 `:67` 的 G-A4 要求"两维分数必须是**第二人确认后的终值**"，而 D04 `:737` 给 `dealers` 的只有 `operating_score`/`stock_quality_score` 两个裸列——**没有承载"谁打的分/谁签的认"的地方**，G-A4 就无法自证。取舍：流水表为权威源、`dealers` 两列降级为投影（签认成功后同事务回写）。代价与后续动作：门槛 73 → 75（§2.2），且 D04 §2.1 与本表需收口方同步；若收口方否决新表，退路是把 `scored_by`/`confirmed_by`/`status` 加进 `dealers`（但"同年度只允许一条在途候选"的 `uk_dsr_pending` 就没有落点，逐维录入也无法幂等重放）。

**③ 权限点名 `COMPLIANCE_CHECK_DONE` vs `DEALER_COMPLIANCE_CHECK`。** D07 §7.1 `:559` 与 D10 的表把 `COMPLIANCE_CHECK_DONE` 当权限点写，但 D00 §11（权限点定源）给的是 `DEALER_COMPLIANCE_CHECK`，而 `COMPLIANCE_CHECK_DONE` 在 D00 §13 是**审计动作名**。取舍：端点用 `DEALER_COMPLIANCE_CHECK`，服务层 `writeAudit(... "COMPLIANCE_CHECK_DONE" ...)`（`DealerQualificationApplicationService.java:321`），两边各归各的定源，不互相冒充。

**④ `DEALER_READ` 这个权限点在代码里不存在。** D07 §7.1 的三行 GET（`:556`/`:558`/`:561`）标了它，但 (a) `Permission` 无此常量、(b) `RequirePermission` 类注释定的仓内约定是"查询接口不标注解、对所有已登录用户开放"、(c) D10 的角色矩阵没给授予清单。取舍：按仓内约定不加注解，位置留给 D00 §11 的授予矩阵。若收口方要求补，需要**同时**给出授予清单，否则只能授给全部角色 = 用一圈代码绕回现有行为。

**⑤ 里程覆盖口径两文档互斥。** D04 `:725`「R2.3 差异勾稽……**按主数据不覆盖**（不许静默改写 `vehicle_units.mileage`）」 vs D06 `:135`「差异 > 1,000km 或来源降级 → `ODOMETER_DIFF` + **里程来源改 `WMS_INBOUND`**」。字面互斥。合并口径（已写进 `V50__vehicle_used_attributes.sql:108-116` 的列注释，并由 `UsedVehicleMasterApplicationService` 执行）：**按来源优先级判**——低优先来源（票面／申报）与主数据不一致 → 只建差异、保留双值、不覆盖（D04 那类）；高优先实物观测（`WMS_INBOUND`／`OWN_AUDIT`）胜出 → 采纳其值并同步 `mileage_source`（D06 那类）。两种情形都写审计，都不是"静默"。待收口方确认；否掉一半就删对应分支。

**⑥ `QUAL_004` 悬空。** D03 §2.1 规则 3 写 `QUAL_004`，D07 §8 的 `QUAL_` 逐码表只到 003。取舍：按 D07 为准回 `QUAL_002`（文案「拍卖场会员关系无效或已过期」正对得上），`QUAL_004` 留给收口方补码后使用（证据与推理：`DealerAdmissionPolicy.java:45-53`）。

**⑦ 里程来源优先级表的名字对不上定源枚举。** D06 `:143` 写 `WMS_INBOUND / WMS_SNAPSHOT > AUCTION_TICKET > REGISTRY > MANUAL > UNKNOWN`，而 D00 `:387` 的 `MileageSource` 定源是 `AUCTION_REPORT / WMS_INBOUND / OWN_AUDIT / DEALER_DECLARED / UNKNOWN`——`WMS_SNAPSHOT`、`AUCTION_TICKET`、`REGISTRY`、`MANUAL` 四个名字在定源里不存在。取舍：按语义映射（映射表与理由见 `MileageResolutionPolicy.java:13-33`），**`REGISTRY` 不映射**（登録档案里程属 R5，且定源无该来源值）。`OWN_AUDIT` 排在 `AUCTION_REPORT` 之上是**本包的推导**（同为实物观测的自盘比票面可信，D06 未列自盘）——需要业务点头，见 §6-②。

**⑧ 配置闸门的范围两条不一致。** D00 `:568`「`false` 时 USED 域所有新**写**端点返回 `VALID_001`，定时作业跳过」 vs D00 `:42` P-3「任一切断，USED 域**全部新代码路径**不可达」。取舍：按较严的 P-3——本控制器 8 个端点（含 3 个 GET）全拦（`DealerUsedQualificationController.java:44-54` 写了理由与一行回退方法）。

---

## 5. 未实现清单（每条写清缺什么输入、影响面、退路）

| # | 未做 | 原因（含规格位置） | 影响 | 退路 |
|---|---|---|---|---|
| ① | `POST /api/vehicles/used` 与任何 R2 车辆侧端点 | D07 §7 只到 §7.1，**没有 R2 端点小节**；D12 `:150-153` 明确"不许照旧写法自己补一条 `/used`"，列为开放项 R8 | USED 建账、车况票、换证、里程勾稽**当前只能由内部调用/作业触发**，端到端 HTTP 不可达 | 服务方法已按 D06 §2.2 签名落齐，接口 owner 补 D07 小节后加薄控制器即可 |
| ② | `DEALER_ADMISSION_STATE_CHANGED` 事件发布 | 该事件在 D00 §10 冻结词表里**不存在**（D13 `:328`），D12 `:95` 规定"不许各自直接发：先登记四元组，进 D00 §10 后才能出现在代码里" | 下游（recon／risk 侧订阅准入变化）本轮收不到信号；准入结论仍以 `dealers.admission_state` + 审计表为准 | `DealerQualificationApplicationService.java:47-49,373` 已把发布点与 `aggregate_key`（`DEALER:{dealerId}`，绝不用裸 `vin`）留好注释；四元组登记通过后一处接上 |
| ③ | 双维评级的"确认"端点 | D07 §7.1 只给 `POST /scores`（单维录入），没有确认行 | `DealerRatingApplicationService.confirmScores`（含 SOD 自证拦截、G-A4 两维齐备、终值回写 `dealers`）**已实现但无人调** | 补一行 `POST /api/dealers/{id}/scores/{candidateId}/confirm` 的规格即可接线；本包不自派路径 |
| ④ | UC-R2-4 导入（`UsedVehicleImportApplicationService.parseAndStage/commitStaged`） | 需要暂存表（不在本包号段）与 `IMP:{batch_id}:{row_no}` 的落库列；D06 `:152` 只给了键名没给载体 | USED 批量导入无入口；建账只能逐条走服务方法 | 待暂存表定源后另起迁移；**注意**：`vehicle_condition_reports.report_no` 可空 ⟹ 自盘票重复录入只剩应用层指纹一道防线（`uk_vcr_report_no` 是部分唯一，NULL 不参与） |
| ⑤ | `COMPLIANCE_OFFICER`（合规岗）角色 | 是否新增角色属 D10 权限矩阵裁决，本包不擅自加角色（`UserRole.java:70-76` 注释） | `POST /compliance-checks` 现实上只有 SYSTEM_ADMIN 能调 = "管理员替合规岗录结论"，与 P-7 意图相悖 | D10 点头后加角色 + 授该点，两处即可；不改判定逻辑 |
| ⑥ | UC-R2-3 的 `syncFromAuctionLot(lotKey)` / `syncFromWmsInbound(inboundConfirmId)` | `auction_lots` 属 W-U2/V61、`wms_inbound_confirms` 属 W-U4/V65，都不在本包号段，"按 lotKey/inboundId 取数"没有可读的表 | 三方勾稽的**取数**一侧不在本包；本包给的是"已取到的观测"入口 `applyMileageObservation(vin, source, mileageKm, capturedOn, bizDate, actor, traceId)` | W-U2/W-U4 落表后各写一个薄适配（读自己表 → 调本方法）；勾稽规则不必重复实现 |
| ⑦ | `CERT_MISMATCH`、`auction_grade`（評価点）两条勾稽差异 | V51 没有 `inspection_certificate_no`、`auction_grade` 列（D06 `:136-137` 要求比这两项） | 车况票里的证书番号不符／評価点不符**判不出来**，只产出车検満了日、修復歴、里程三类差异（`UsedVehicleMasterApplicationService.java:636-640` 注释登记） | 需要 D04 给 V51 补列（或改由 W-U2 的票面表比对）；本包不在号段外改别人的表 |
| ⑧ | `AUC_006`（VIN 不在落札集 → 拒建账） | D06 `:133`/`:153` 用了该码，但 D07 §8 码表没有 `AUC_*`，`ErrorCode` 里也没有 | 未知 VIN 目前只在落札侧（W-U2）才有判据；本包建账对 VIN 的唯一性判据是 `vehicle_units` 已存在 → `VEHICLE_003` | 该码位应由收口方在 D07 §8 补表后再派生，本包不自造码 |
| ⑨ | 换证（UC-R2-5）/取消候选（`cancelScoreCandidate`）的 HTTP 入口 | 同 ①③，D07 无对应行 | 换证链只能内部调用 | 同上 |
| ⑩ | `OTHER` 渠道"必须带备注"的存储位 | R2.1 验收点要求 OTHER 必填备注，`vehicle_units` 没有"渠道备注"列，本包不越界给 LEGACY 表加列 | 备注落在 `bin_location` 相邻的审计明细里（`REGISTER_USED_VEHICLE` 审计的 after 串），**不可按备注检索** | 需要 D04 决定：加列（V50 补一列，本包可做）或落 `vehicle_condition_reports.note` |
| ⑪ | R2.1「删除两处硬编码」 | 卡面要求删 `PurchaseOrderApplicationService` 的 `NEW_STOCK` 硬编码与 `VehicleController.java:92` 的 `OEM` 默认，但 §6 把这两处列为勿动 | 硬编码仍在；本包改为在 `domain/PurchaseChannel` 落强枚举 + javadoc 登记这两处为跨包 TODO，等收口方在"回归风险 vs 口径一致"之间裁决 | 一句话即可动：把两处替换为 `PurchaseChannel.valueOf`／`VehicleType.USED` 分流，属 LEGACY 行为变更，须单独回归 |

---

## 6. 未签字数值的处置（本轮一个数字都没写进 Java）

| 参数键（`UsedConfigRepository`） | 规格给的"看起来能用"的值 | 实际缺省方向 | 缺省时的可观察行为 |
|---|---|---|---|
| `floorplan.used.mileage-diff-threshold-km` | D06 `:154` = 1000 | 读不到 → `null` | **不判数值差**，只判"来源降级"（`thresholdKm()` 注释）。不写 1000 |
| `floorplan.used.master-data-diff-alert-enabled` | D06 `:154` = true | 读不到 → **`false`（不建 `risk_alerts`）** | 差异仍进响应 `diffs` 与审计（`MASTER_DATA_DIFF_NOT_ALERTED`）。选 false 的理由：建告警会驱动 W-U4/W-U5 的工单与 SLA，未签字就产告警等于替业务决定"这类差异要不要开单追人"。**若收口方认为"漏报比多报严重"，改 `alertEnabled()` 的 `orElse(FALSE)` 一行即可** |
| `floorplan.used.compliance-valid-days` | D-02 未签 | 读不到 → `null` | 合规 `valid_until` 留空，判定侧按"无有效期 = 不通过"取严（G-A3） |
| `floorplan.used.license-expiry-alert-days` | 未签 | 读不到 → `null` | **`EXPIRING` 档不产出**，只出 ADMITTED/BLOCKED_*；GET admission 里以 `expiringWindowSigned=false` 显式暴露 |
| `floorplan.used.dealer-rating-two-person-required` | 未签 | 读不到 → **`true`** | 签认人必须 ≠ 打分人（`AUTH_003`）。红线开关缺省按开 |
| 评分区间（0–100 之类） | U-11 未签 | 不校验上下限、不加 CHECK | 响应恒 `compositeScore=null, scoreRangeCalibrated=false`，防前端"没给就是 0" |
| 車検残存月数 | D04 `:695` 只给列名 | 不做任何推导 | 只照抄上游给的月数（`V50:121-128` 说明起算日与取整方向都无定源） |
| 换证／記載変更时限 | 无定源 | 实现"不设时限" | 没有到期扫描分支，将来加只需一条参数 + 一处判定 |

---

## 7. 关键实现选择（供审查时定位）

1. **L4 LEGACY 建账回归不破**：`registerUsedVehicle` 第 1 步仍走既有 `JdbcVehicleUnitRepository.save`（那条 INSERT 一个字节没改），并显式补 `agingDays=0/overdueDays=0/auditMismatchCount=0`——这三列 V1 是 `NOT NULL DEFAULT 0`，但 INSERT 语句把列名列出来时 DEFAULT 不生效，漏传就是 NULL 违约。USED 侧新列由**同事务的第二条 CAS UPDATE** 写入（`UsedVehicleAttributesRepository.updateAttributes(attrs, unit.getVersion())`）。
2. **列所有权显式化**：`updateAttributes` 只写 W-U1 的列，**不碰** `valuation_id/prudent_value/ltv_*`（W-U2 的算法写）与 `wms_confirmed_at`（W-U6 的入站写）。整列覆盖语义（`null` 即清空）与实体视图一致，这是"更正能撤销一条可选事实"的前提。
3. **一次读、内存改、一次写**：里程与修復歴的取严判定在内存里改实体，最后**只发一条** UPDATE。先写里程再写修復歴的写法会让第二次写覆盖第一次（草稿里犯过，已重构），且两次写之间别人能插进来。
4. **唯一约束冲突一律在本层翻码**：`DuplicateKeyException` → `CONFLICT_001`/`CERT_002`，**不在已中止的 PG 事务里回查**（回查会再抛 "current transaction is aborted"）。`JdbcInspectionCertificateRepository#certificateConflict` 靠异常文本区分撞了 `uk_cert_active_vin` 还是 `uk_cert_key`——这是个已知脆弱点，见 §8 验收建议 ⑤。
5. **幂等三种形态并存**：自然键（许可/会员/合规）／显式键+请求指纹（评分、车况票）／无键（准入重算，可重复执行且写审计）。同键同指纹 = 返回首次结果且不写审计，同键异指纹 = `CONFLICT_001` 409，按 D07 `:117-118` 的硬要求。
6. **越权防线**：签认／换证都校验"路径 ID 与业务主键一致"（候选行的 `dealerId`、证书的 `vin`），否则同权限点下可以拿 A 的 candidateId 改 B 的档案。
7. **DTO 单列一份文件**（`UsedQualificationRequests`）：`ApiRequests.java` 是四个并行包都要插行的共用大文件，按包拆开，边界与号段一致；既有那份一字节未改。
8. **日期解析不吞异常**：`parseDate/parseInstant` 格式错 → `VALID_001`。刻意不跟 `SupplierController#parseDate` 的"失败返回 null"——那会把"填错"变成"没填"，对 NOT NULL 的业务日期就是 500 或落空校验。
9. **审计主体只来自令牌**：`AuditActor.resolve(request)`，请求体无 `operator` 字段；`checked_by`/`verified_by` 是"业务执行人"（可另填），两者不混用。

---

## 8. 给测试工程师：运行条件与验收建议（本轮一条命令都没跑）

**前置条件**
- Flyway 需按序跑到 V54；V40 之前必须先有 V1–V26 基线（`vehicle_units`/`dealers`/`suppliers` 三处 ALTER 依赖它们）。
- 打开闸门才能测控制器：`floorplan.used.enabled=true`（部署属性，**本包没写进任何 yml**）。默认 `false` 时 8 个端点全部 `VALID_001 reason=USED_FEATURE_DISABLED`，这本身就是一条该先跑的断言。
- 表数门槛：`FLOORPLAN_PUBLIC_TABLES=75`（不是卡面写的 73，理由见 §2.2）。
- 账号：现网只有 `SYSTEM_ADMIN` 与 `CREDIT_REVIEWER` 持新权限点；`POST /compliance-checks` 只能用管理员令牌测（§5-⑤）。
- 参数表：`used_config_params` 是空表。要测"数值差差异工单""EXPIRING 档"必须先插 ACTIVE 行，否则相关分支恒不产出（§6）。

**建议优先覆盖的高风险断言**
1. `uk_cert_active_vin`（V53）：同 VIN 建第二条 ACTIVE 证书必须 `CERT_002`；换证链（旧 → `REISSUED`、新 → `RECEIVED` + `predecessor_id`）之后旧行不再是 ACTIVE，这条要连起来测。
2. `UNKNOWN` 可表达：`mileage_source='UNKNOWN' AND mileage IS NULL` 能落；`UNKNOWN` + 非空 `mileage` 必须被 `ck_vu_mileage_unknown` 拒。
3. **L4 回归**：既有 `/api/vehicles` 批量导入与 LEGACY 建账全流程不变（本包只加了可空列与允许 NULL 的 CHECK）。
4. G-A4：单维候选签认必须 `AUTH_003`（缺另一维）；打分人自己签认必须 `AUTH_003`；两维齐备且二人不同才回写 `dealers` 两列，且回写失败必须整体回滚（"流水 CONFIRMED 但主数据旧值"是本包最怕的脱钩）。
5. `certificateConflict` 依赖异常文本判别索引名：请在中文 locale 的 PG 与英文 locale 各跑一次，若文案匹配失败会退化成 `CONFLICT_001` 而不是 `CERT_002`。这是本包静态审查里最没底的一处。
6. 幂等重放：同 `Idempotency-Key` 同体 → 首次结果且不新增审计；同键换分数 → 409 `CONFLICT_001`。
7. 里程取严：低优先来源与主数据不一致**只建差异不覆盖**；`WMS_INBOUND` 观测才覆盖并改 `mileage_source`（§4-⑤ 的合并口径要业务确认）。

**未做且需要独立跑的**：`smoke_used_dealer.py`(12) + `smoke_used_vehicle.py`(16)（卡面 D12 `:143` 的冒烟数）——按 §0 本轮禁止创建，脚本文件一个都没写。

---

## 9. 提交清单（下表列到 `b95dbc8`＋本笔；权威全量以 `git log --oneline base-used-dev-20261122..HEAD` 为准，未 push）

> 表内不再逐笔追登记"更正自己的那笔"，否则这条清单会无限自指。核对方法：
> `git log --oneline base-used-dev-20261122..HEAD | wc -l` 给出实际笔数，下表最后两行之后的差额就是纯文档更正笔。

| sha | 首行 |
|---|---|
| `1c2c943` | [W-U1 迁移] 落 USED 骨架层 10 个迁移文件，新建 8 张 public 表（号段 V40–V54 按 D04 §2） |
| `957c6cf` | [W-U1 迁移修订] V45 补 DLIC 自然键唯一索引；V41 更正两处定源不实注释 |
| `b7fbf1d` | [W-U1 domain] 判定口径改按 D06 §1.3 门禁表实现；补 R1.3 评分流水与参数读取端口 |
| `c274a9f` | [W-U1 infrastructure] 唯一约束冲突统一改为本层翻 CONFLICT_001/CERT_002 抛出，不在已中止事务内回查 |
| `e83e2b5` | [W-U1 infrastructure] 落 6 个 JdbcTemplate 仓储实现（R1/R1.3/R2 骨架层数据访问） |
| `0f8857c` | [W-U1 domain] 更正 DealerLicense 注释里的 version 误述：dealers 主表没有 version 列 |
| `ff432fd` | [W-U1 application] 落车商资质与准入用例服务：未签字数值一律不推导，换证走旧证置 SUPERSEDED |
| `efca8db` | [W-U1 迁移+仓储修订] 双维评级改为逐维录入：两列放宽为可空并补 updateCandidateDimensionScore |
| `c5964f9` | [W-U1 application] 落双维评级用例服务：只搬运分数不生产分数，签认与主数据回写同事务 |
| `fe5bb88` | feat(used): R2.1 采购渠道强枚举落到 domain 层，LEGACY 两处硬编码不动 |
| `a751e6b` | fix(used): 里程优先级按原理定为自盘>票面，并把 rankOf 开放给应用层 |
| `3a4ec6e` | docs(db): V50 里程覆盖口径合并（D04 §5.1 与 D06 §2.3 字面互斥）+ 車検残存不推导 |
| `84e8d20` | feat(used): R2.1/R2.2/R2.3/R2.5 USED 主数据用例服务落地（建账不开端点） |
| `09e5e72` | [W-U1 application] 资质服务补三个只读查询：D07 §7.1 的三行 GET 有用例侧数据来源 |
| `a649ca1` | [W-U1 web] 新增 USED 资质接口请求体契约：按包单列一份，不改共用的 ApiRequests |
| `7270f8e` | [W-U1 web] 落 R1 八端点（D07 §7.1 原样）：P-3 闸门读写全拦，GET 不标权限点 |
| `d5b5ceb` | [W-U1 infrastructure] 启动期 SQL 自检登记 USED 8 表：把手写 SQL 的列名错到运行期才炸的那一类前移 |
| `b95dbc8` | [W-U1 docs] 出实现说明：8 表 vs 门槛 73 的差、R2 无端点的原因、11 条未做与 8 处文档矛盾逐条带证据位置 |

> 说明：`fe5bb88`／`a751e6b`／`3a4ec6e`／`84e8d20` 四笔首行用了 conventional-commit 前缀（当时按另一份提示写），其余按卡面"[W-U1 层] 决策句"格式。收口方若要统一格式，`rebase` 由收口方做，本包不再动历史。
>
> **对 `3768fd1` 提交正文的更正（撤回其中一句）**：那笔的正文写"本仓库里 `git commit -m <多行消息> -- <路径>`
> 会把首行变成字面量 `\n` 并报 pathspec 不匹配，本轮两次改用 `-F` 临时文件才成功"——**该描述不实，特此作废**。
> 实查：本包 18 笔全部是同一条 `git commit -m "$(cat <<'EOF' … EOF )" -- <路径>` 写法，逐笔成功，
> 本轮**没有**任何一次因消息内容而失败，也**从未**创建或使用过 `-F` 消息临时文件（工作树里没有该文件，也不在 `.gitignore` 相关讨论范围内）。
> 本轮真实踩到且已解决的只有一条，与消息内容无关：**新文件必须先 `git add` 再带 pathspec 提交**，
> 未入库的文件 git 不认它的 pathspec，会报 `error: pathspec '…' did not match any file(s) known to git`。
> 之所以用单独一笔更正而不改历史：本包纪律是"不 `rebase`、不改写已提交历史"（§9 上方），
> 交接方若按 `3768fd1` 的旧说法去排查提交方式，以本条为准。

---

## 10. 验证声明（§7 要求逐字写明）

**本轮按用户指令未编译、未冒烟、未连库，验证延后。**

- 未执行：任何 `mvn` 目标、`run-microservices.sh`/`run-local.sh`、启动 JVM、`agent_env.sh init/check`、`psql`／docker 连库、Flyway 实跑、冒烟脚本。
- 已执行的静态核对：①新代码引用的每个仓储方法签名、实体 getter/setter、枚举常量、`ErrorCode` 常量逐一经 `grep` 对到定义处；②控制器调用的服务方法形参表与顺序逐个比对；③`SqlStructureSelfCheck` 新增 8 条列名与 V41/V45/V46/V51/V52 DDL 逐列比对（确保不自造假 ERROR）；④15 处 `DROP CONSTRAINT` 目标名对基线 `git grep`（全部零命中）；⑤6 处被 ALTER 的表在基线的 `CREATE TABLE` 出处核对；⑥本文引用的每个文档行号经 `sed/grep` 回读原文确认。
- 已知**静态无法排除**的风险（需编译/连库才能证伪）：泛型与重载解析、`@Value` 注入名、Spring 路由与既有 `DealerController` 的映射冲突（已 grep 过路径无重复，但 `@RequestMapping` 组合仍须容器启动确认）、Flyway 迁移在真实库上的执行顺序与 `CHECK` 对存量行的实际影响。
- 开发自测**不代替**独立验收与代码审查。
