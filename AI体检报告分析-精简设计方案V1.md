# AI 体检报告分析与菜品推荐 — 精简设计方案 V1

> 技术栈：Java 8 / Spring Boot 2.7.x / MyBatis-Plus / PDFBox / Apache POI / ofdrw / TinyPinyin / **MySQL 8.0** / Redis / xxl-job / OCR / Dify
>
> 对应需求：《体检报告分析需求.md》
> 本文档只描述当前设计，不含版本历史与废止内容。任何一句话都是现行有效的。

---

## 0. 设计原则

1. **原文即事实。** 展示层严格复刻报告原文，模型负责"定位与分类"，不负责"生成与推断"。
2. **能程序判定的不交给模型**，程序判不了的也不伪装成已判定。
3. **不确定性只在写入时消费一次。** 菜品打标在离线批处理完成并落库，模型波动不影响每次读取。
4. **模型没返回 ≠ 报告里没有。** Schema 强制必填，缺字段走重试或失败，不得折叠成空值。
   但**"Schema 通过"也不等于"报告已完整识别"**——模型返回 `"allergens": []` 结构上完全合法，
   所以高风险内容另有关键词交叉扫描兜一层（§4.4）。
5. **过敏是唯一安全红线**，误判代价不对称：漏标可能造成过敏反应，误标只是少一个选择。因此过敏一律偏向高召回。

---

## 1. 整体流程

```
① 用户逐份上传文件（每份只返回 fileId，不创建任务）
        │
        ▼
② 点击「生成体检报告」，提交有序 fileIds → 创建 taskId、绑定文件、入队
        │
        ▼
③ Worker：文件解析 → LLM-A 抽取 → 同一性校验 → Java 校验与归一化
        │
        ▼
④ 组装四模块（纯 Java + 读离线打标，不再调用任何模型）
        │
        ▼
⑤ 前端轮询 taskId 取结果

离线：每日凌晨 LLM-B 对当日在架菜品按 20 个维度打标 → MySQL ct_dish_tag（真源）+ Redis（读缓存）
      营养补充 9 个维度不打标，在线由 Java 确定性交集匹配（§8.7）
```

**在线链路只调用 LLM-A，不调用 LLM-B。** LLM-A 本身按 ≤8 页/批分批，一份 22 页报告是 3 批（§4.1）。
LLM-B 只允许离线预热任务调用，在线链路对它的调用次数必须为 0，对标签的写入次数也必须为 0。

---

## 2. 上传与任务（需求 §3）

### 2.1 上传接口

`POST /api/health-report/file` —— 单文件上传，返回 `fileId`。

| 校验 | 规则 | 失败提示 |
|---|---|---|
| 格式 | 按 §3.1 的逐格式判定，**不信任扩展名** | 「暂不支持该文件格式，支持PDF、JPG、PNG、OFD、DOC/DOCX格式」 |
| 单文件大小 | PDF/OFD/DOC/DOCX ≤ 20MB，JPG/PNG ≤ 10MB | 「文件大小超过限制，请压缩后重新上传」 |
| 可读性 | 按 §3.1 的逐格式可读性判定 | 「文件无法读取，请检查文件是否完整」 |

存 S3 私有 Bucket，落一条 `ct_health_report_file`（`status = UPLOADED`，`task_id = NULL`，
`user_id = 当前用户`，`expire_at = now + 30min`）。

**HEIC 由前端转码为 JPEG 后上传**，后端不引 Native 库。

### 2.2 创建任务接口

```http
POST /api/health-report/analyze
{"fileIds": ["...", "..."]}
```

**逐文件校验（缺一不可，§3.9）：**

```
file.userId   == 当前已认证 userId     ← 不校验这条 = 拿到别人的 fileId 就能读到别人的报告
file.status   == UPLOADED
file.expireAt >  now
file.taskId   IS NULL
```

`taskId` 层面的鉴权**弥补不了**创建阶段的缺失：攻击者若能把他人的 `fileId` 绑到自己的
`taskId` 上，后续所有归属校验都会正常通过。

**其余校验：** `fileIds` 数量 1~5、累计大小 ≤ 60MB（§12-4）、累计页数（§3.3）、队列深度。

**绑定规则：一个文件同时只能属于一个「活着的」任务。**

```
可绑定 = task_id IS NULL                              ← 新上传，从未用过
       ∪ 原 task 处于 FAILED 且 reanalyzable = 1     ← 上次是服务端出错，允许换个新任务再来
```

第二条就是需求 §3-9「使用已上传的文件重新解析」的落地方式：**用同一批 `fileIds`
再调一次本接口即可**，不需要单独的重试接口（§2.5）。

**事务内两步，防并发抢占：**

```sql
-- ① 锁定并校验（MySQL 8：FOR UPDATE OF f 只锁文件行，不锁任务行）
SELECT f.*, t.status, t.reanalyzable, t.deleted_at
  FROM ct_health_report_file f
  LEFT JOIN ct_health_report_task t ON t.task_id = f.task_id
 WHERE f.file_id IN (?) AND f.user_id = ?
 FOR UPDATE OF f;
-- 逐行校验 status='UPLOADED'、expire_at>now、以及上面的可绑定条件
-- 不加 OF f 会连带锁住 ct_health_report_task 行，与 Worker 的状态 CAS 争锁

-- ② 条件更新，把 oldTaskId 带进 WHERE 防止两个请求同时抢
UPDATE ct_health_report_file
   SET task_id = :newTaskId, file_index = ?, expire_at = ?
 WHERE file_id = ? AND user_id = ? AND status = 'UPLOADED'
   AND (task_id IS NULL OR task_id = :oldTaskId);
-- 不写 update_time，由 ON UPDATE CURRENT_TIMESTAMP 维护（§9.1.1）
-- 受影响行数必须 == 1，否则整个事务回滚，返回 FILE_ALREADY_BOUND
```

**`FILE_ALREADY_BOUND` 必须把已绑定的 `taskId` 一并返回。**

```json
{"code": "FILE_ALREADY_BOUND", "taskId": "已绑定的那个"}
```

这是为了兜住"任务创建成功但响应丢包"的情况：前端没拿到 taskId，用户再点一次，
文件已经绑给一个 `QUEUED` 的任务了——如果只回一个错误码，用户就只能重新上传。
带上 taskId，前端直接转去轮询它即可。归属已在 §2.2 开头校验过，不额外泄露信息。

`fileIds` 的提交顺序即 `fileIndex`（0 起）。
被解绑的旧 `FAILED` 任务行不再持有任何文件，随 `expire_at` 自然回收（§2.7）。

### 2.3 任务状态机与持久化

**任务状态的真源是 MySQL，不是 Redis。** 创建任务、文件绑定、状态 CAS、心跳
全部走 MySQL 事务与条件更新；Redis 只承载队列消息和四模块结果（§9.1）。

#### 2.3.1 状态与删除标志是两个正交的东西

```
QUEUED ──→ PARSING ──→ EXTRACTING ──→ ASSEMBLING ──→ SUCCEEDED
             │             │              │
             └─────────────┴──────────────┴──────────────→ FAILED

deleted_at  正交标志，任何状态下都可置，一旦置上不可撤销
```

**状态机是纯单向的，没有任何回边。** `SUCCEEDED` 与 `FAILED` 都是终态，一旦到达不再迁移。

用户的「重新解析」**不是让旧任务回到队列**，而是拿同一批文件创建一个**全新的 taskId**（§2.2、§2.5）。
这样：

- 不需要 `attempt_no` 来区分第几次执行
- 旧任务的残留队列消息天然失效——它的 `status` 已是 `FAILED`，§2.3.4 的 CAS 直接挡下
- 状态机没有回边，可穷举、可画图、可单测

**删除不是状态迁移**，用 `deleted_at` 标志表达。

#### 2.3.2 DDL

**建表硬约束：每个字段必须声明字段级中文 `COMMENT`，每张表必须有中文 `COMMENT`。**
行尾 `--` 备注、Java 注释或另外的数据字典不能代替 MySQL 字段 `COMMENT`。

```sql
CREATE TABLE ct_health_report_task (
  task_id        VARCHAR(36)  NOT NULL COMMENT '任务ID，使用UUID',
  user_id        VARCHAR(64)  NOT NULL COMMENT '归属用户ID，用于鉴权',
  status         VARCHAR(16)  NOT NULL COMMENT '任务状态：QUEUED/PARSING/EXTRACTING/ASSEMBLING/SUCCEEDED/FAILED',
  stage          VARCHAR(16)  NULL COMMENT '前端进度阶段，见§2.4',
  progress       TINYINT      NOT NULL DEFAULT 0 COMMENT '任务进度百分比，取值0至100',
  fail_code      VARCHAR(32)  NULL COMMENT '任务失败错误码，成功或未失败时为NULL',
  reanalyzable   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否允许重新解析，同时是§2.2的文件解绑条件',
  partial        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否为部分结果，命中时模块三四降级',
  partial_reason VARCHAR(32)  NULL COMMENT '降级原因：PAGE_TRUNCATED/BATCH_UNREADABLE/ALLERGEN_SUSPECT_MISS',
  heartbeat_at   DATETIME     NULL COMMENT 'Worker最近心跳时间',
  deadline_at    DATETIME     NULL COMMENT '任务执行硬截止时间',
  expire_at      DATETIME     NOT NULL COMMENT '任务及关联数据过期时间',
  deleted_at     DATETIME     NULL COMMENT '用户删除任务的时间，未删除时为NULL',
  version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由数据库维护',
  create_by      VARCHAR(50)  NULL COMMENT '创建人标识',
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护',
  update_by      VARCHAR(50)  NULL COMMENT '更新人标识',
  PRIMARY KEY (task_id),
  KEY idx_user (user_id),
  KEY idx_sweep (status, heartbeat_at),
  KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体检报告分析任务';

CREATE TABLE ct_health_report_file (
  file_id        VARCHAR(36)  NOT NULL COMMENT '文件ID，使用UUID',
  user_id        VARCHAR(64)  NOT NULL COMMENT '归属用户ID，用于鉴权',
  task_id        VARCHAR(36)  NULL COMMENT '关联任务ID，未绑定任务时为NULL',
  file_index     INT          NULL COMMENT '文件在任务内的顺序，从0开始',
  status         VARCHAR(16)  NOT NULL COMMENT '文件状态：UPLOADED',
  origin_name    VARCHAR(255) NOT NULL COMMENT '用户上传时的原始文件名',
  content_type   VARCHAR(64)  NOT NULL COMMENT '按§3.1判定的真实文件格式，非扩展名',
  size_bytes     BIGINT       NOT NULL COMMENT '文件大小，单位字节',
  content_hash   CHAR(64)     NOT NULL COMMENT '文件内容SHA-256哈希',
  cloud_file_key VARCHAR(255) NOT NULL COMMENT '云存储文件键，用于定位原始文件',
  expire_at      DATETIME     NOT NULL COMMENT '原始文件过期删除时间',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由数据库维护',
  create_by      VARCHAR(50)  NULL COMMENT '创建人标识',
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护',
  update_by      VARCHAR(50)  NULL COMMENT '更新人标识',
  PRIMARY KEY (file_id),
  KEY idx_task (task_id),
  KEY idx_user (user_id),
  KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传的体检报告文件';
```

Bucket/容器名由 `S3FileStorage` 的部署配置提供，不逐文件落库；文件表只保存
`cloud_file_key`，Java 实体字段固定为 `cloudFileKey`，不得保留 `s3_bucket` / `s3_key`。

**没有 outbox 表，也没有 Dispatcher 线程。** 入队是创建事务的一部分（§2.3.3）。

**Worker 的 lease 不单独建表**，用 `heartbeat_at` + `deadline_at` 表达。

> **表命名与审计列遵循项目约定（§9.1.1）**：表名一律 `ct_` 前缀；
> 四个审计列 `create_time` / `create_by` / `update_time` / `update_by` 每张表都有；
> **两个时间列由数据库默认值和 `ON UPDATE` 维护，代码永远不赋值。**

#### 2.3.3 创建与入队：XADD 在事务内，失败就回滚

```
① 开启事务
② INSERT ct_health_report_task (status = 'QUEUED', ...)
③ 绑定文件（§2.2 的两步）
④ XADD q:analysis {taskId}          ← 在事务内执行
     失败 → 抛异常，整个事务回滚 → 返回 SERVER_ERROR
⑤ 提交事务
⑥ 返回 taskId
```

**两种失败路径都不会留下卡死的任务：**

| 失败点 | 后果 |
|---|---|
| ④ XADD 失败 | 事务回滚，task 行不存在，文件仍未绑定。用户重点一次「生成体检报告」即可 |
| ④ 成功但 ⑤ 提交失败 | 消息指向一个不存在的 task 行 → Worker 的 CAS 影响 0 行 → 直接丢弃（§2.3.4）。文件仍未绑定 |

XADD 与 MySQL 事务不是原子的，但**不一致的方向是安全的**：
可能有孤儿消息（无害，被 CAS 丢弃），不可能有孤儿任务（有任务行但没消息 = 永久卡死）。
顺序反过来就不成立了，所以 **XADD 必须在提交之前**。

> 早期设计用 outbox 表 + Dispatcher 退避重投来保证投递。按"服务端出错直接返回错误"的
> 口径，那套机制连同 `ct_health_report_task_outbox` 表整个删除——一次 Redis 抖动
> 现在是一次用户可见的失败，用户重点即可，不再由系统兜着悄悄重投。

#### 2.3.4 Worker 幂等与陈旧消息

```sql
UPDATE ct_health_report_task
   SET status = 'PARSING', heartbeat_at = now(), deadline_at = now() + 10min, version = version + 1
 WHERE task_id = ? AND status = 'QUEUED' AND deleted_at IS NULL
```

受影响行数为 0 时直接 `XACK + XDEL` 丢弃。四种情况被这一条覆盖：

- 消息重复投递
- 任务已被删除（`deleted_at` 非空）
- 旧任务失败后用户重新发起分析，而它的残留消息才刚到（旧任务已是 `FAILED`）
- **孤儿消息**：XADD 成功但事务提交失败，task 行根本不存在（§2.3.3）

**心跳：** Worker 每 30s 更新 `heartbeat_at`。巡检任务扫 `status ∈ 执行中` 且
`heartbeat_at < now - 15min` 的，强制置 `FAILED / SERVER_ERROR`（`reanalyzable = 1`），
覆盖 Worker 进程崩溃。**客户端超时不写终态。**

### 2.4 进度（需求 §3-7）

`GET /api/health-report/task/{taskId}` 返回 `{status, stage, progress, failCode, reanalyzable}`：

| stage | 对应状态 | progress | 前端文案 |
|---|---|---|---|
| UPLOADING | QUEUED | 0~30% | 正在上传文件... |
| PARSING | PARSING / EXTRACTING | 30~80% | 正在识别报告内容... |
| ASSEMBLING | ASSEMBLING | 80~100% | 正在生成分析结果... |

每阶段进入时写区间起点，前端在区间内做平滑动画。

### 2.5 失败处理与重新解析（需求 §3-9）

**服务端出错就直接失败返回，不自行重跑。** 全案不存在任何"执行失败后自动再跑一遍"的逻辑
——LLM-A 批次失败不重试、Schema 校验失败不重试、疑似漏抽不定向重试。
失败即写终态、即返回，把决定权交给用户。

| failCode | 谁的问题 | 页面提示 | `reanalyzable` |
|---|---|---|---|
| `NOT_HEALTH_REPORT` | 用户传错文件 | 「未识别到体检报告内容，请确认文件是否为体检报告后重新上传」 | false |
| `UNREADABLE` | 用户拍得糊 | 「报告内容识别不清，请上传更清晰的文件」 | false |
| `IDENTITY_MISMATCH` | 用户混了别人的报告 | 「检测到上传的文件属于不同人员，请核对后重新上传」 | false |
| `PAGE_LIMIT_EXCEEDED` | 文件太大 | 「报告页数过多，请分次上传」 | false |
| `FILE_EXPIRED` | 超时未操作 | 「上传的文件已过期，请重新上传」 | false |
| `SERVER_ERROR` | **我们自己的** | 「服务暂时不可用，请稍后重试」 | **true** |
| `EXECUTION_TIMEOUT` | **我们自己的** | 同上 | **true** |

> 需求 §3-9 的「体检报告不完整（缺页等）」V1 不实现，见 §12-2。

**用户文件的问题一律 `reanalyzable = false`。** 同一份糊照片重跑一百遍还是糊的，
只有换清晰文件、换对报告、换成同一个人的报告才有意义——这类失败必须回上传页重选。

#### 「重新解析」没有专用接口

`reanalyzable = true` 时前端展示「重新解析」按钮，**点击后用同一批 `fileIds`
再调一次 `POST /analyze`**（§2.2 的绑定规则允许从可重解析的失败任务上解绑）。

```
返回一个全新的 taskId，前端换用新 taskId 轮询
旧任务保持 FAILED，其文件已被解绑，随 expire_at 回收
```

**为什么不复用原 taskId：** 复用就要引入 `attempt_no` 来区分第几次执行，
它会渗进 Worker CAS 和清理逻辑，还会给状态机加一条 `FAILED → QUEUED` 的回边。
开新任务把这些全部消掉，而且旧任务的残留队列消息天然失效（§2.3.4）。

**次数不设上限。** 天然约束已经够了：文件 30 分钟过期、队列深度背压、每次都要用户主动点。
加一个计数器只是多一处状态。

### 2.6 删除（需求 §3-10）

`DELETE /api/health-report/task/{taskId}` —— 用户关闭结果页时调用。

**删除是打标志，不是状态迁移：**

```
① UPDATE task SET deleted_at = now() WHERE task_id=? AND user_id=? AND deleted_at IS NULL
   —— 任何状态下都允许，包括 SUCCEEDED 和执行中
② 删除以下全部：
     S3 原始文件
     MySQL ct_health_report_file 行（整行删除）
     Redis result:{taskId}（四模块结果）
③ 队列中的消息不主动删除 —— Worker 取到后由 ④ 拦下
④ Worker 每次 CAS、每次写结果都带 deleted_at IS NULL 条件；
   置 SUCCEEDED 与写 Redis 结果必须在同一逻辑步骤内、且以该 CAS 成功为前提
```

**这一步防的是这个竞态：**

```
用户删除 → 结果被删 → Worker 随后执行完成 → Worker 把结果又写回去
                                            → 健康数据在"已删除"之后重新出现
```

`deleted_at` 在 MySQL 且不随 TTL 消失，比 Redis 墓碑可靠——墓碑 TTL 一过就失去保护。
`ct_health_report_task` 行由 §2.7 的清理任务统一回收。

### 2.7 数据生命周期与清理矩阵

**原始文件在任务整体 `SUCCEEDED` 后才删，不是解析成功后就删。**

原设计写"解析成功后立即删除"，但解析之后还有 LLM-A、组装、结果写入等步骤，任一步失败
都会产生 `reanalyzable = 1` 的 `SERVER_ERROR`——而此时原文件已经没了，
用户点「重新解析」会在 §2.2 的绑定校验处直接被拒，需求 §3-9 形同虚设。

| 任务状态 | 原始文件（S3） | `ct_health_report_file` 行 | `ct_health_report_task` 行 | Redis 结果 |
|---|---|---|---|---|
| 执行中 | 保留 | 保留 | 保留 | — |
| `SUCCEEDED` | **立即删除** | **立即删除** | 保留至 `expire_at` | TTL 2h |
| `FAILED` 且 `reanalyzable=1` | **保留至 `expire_at`** | 保留至 `expire_at` | 保留至 `expire_at` | 无 |
| `FAILED` 且 `reanalyzable=0` | 立即删除 | 立即删除 | 保留至 `expire_at` | 无 |
| `deleted_at` 非空 | 立即删除 | 立即删除 | 保留至 `expire_at` | 立即删除 |
| 孤儿上传（`task_id IS NULL`） | `expire_at` 到期删除 | 同左 | — | — |

清理任务每 5 分钟跑一次，**必须按上表逐类判定，不能笼统地"终态即删"**
——`FAILED` 也是终态，笼统删会在用户点「重新解析」之前把文件删掉。

文件被重新绑定到新任务后，其 `task_id` 已指向新任务，清理按新任务的状态走，
旧的 `FAILED` 任务行不再持有文件。

`ct_health_report_task` 行保留至 `expire_at`（30 分钟）是为了让 `deleted_at` 在整个 Worker
生命周期内可查，到期后物理删除。该表不含任何健康数据。

| 其他数据 | 生命周期 |
|---|---|
| 渲染图 / OCR 中间产物 | 仅存在于 Worker 内存，用完即释放，**不落盘、不入 S3** |
| 姓名 / 性别 | **仅在 Worker 内存用于同一性比对**，不写 Redis、不入日志、不返回前端 |
| 分析结果（Redis） | TTL 2 小时，过期返回 `RESULT_EXPIRED`，前端提示「本次分析结果已过期，请重新上传」 |

**日志红线：** `taskId` 可进日志用于排障，但**不得与姓名、报告原文、OCR 文本、过敏或医嘱原文、
模型完整请求响应同时记录**。异常堆栈、APM、崩溃转储同样受此约束（§11.1）。

## 3. 文件解析

### 3.1 格式判定与路由

**逐格式判定，不信任扩展名，也不能只看 magic number**（§6.1）：

| 格式 | 判定 | 可读性校验 |
|---|---|---|
| PDF | `%PDF-` 头 | PDFBox 能打开、页数 ≥ 1 |
| JPG/PNG | magic number + **实际解码** | 解码成功、宽高 ≥ 100px、总像素 ≤ 8000 万 |
| DOCX | ZIP 容器 + 内含 `word/document.xml` | POI 能打开、正文非空 |
| OFD | ZIP 容器 + 内含 `OFD.xml` | ofdrw 能打开、页数 ≥ 1 |
| DOC | OLE2 复合文档头 `D0CF11E0` + WordDocument 流 | POI 能打开、正文非空 |

**ZIP 不是支持的上传格式**，用户传 `.zip` 在此处直接被拒。
但 DOCX 与 OFD **自身就是 ZIP 容器**（OOXML 与 GB/T 33190 的规定，不是用户的选择），
所以两者的 magic number 都是 `PK\x03\x04`，只判 magic number 会互相误认，
必须解开查内部结构。DOC 是例外，它是老的二进制 OLE2 复合文档。

Word 没有统一的"页数"概念，可读性判据是**正文非空**而不是页数。

#### 3.1.1 解压炸弹防御（DOCX / OFD）

攻击路径不是"用户传了个 zip 炸弹"，而是：

```
攻击者构造一个 .docx，内含 word/document.xml → 格式校验通过，在我们眼里是合法 Word 文档
  → 解析时内部解压膨胀到几百 GB → Worker OOM 或写爆磁盘
  → 一个用户就能打掉整个 Worker，影响所有人的分析任务
```

**格式校验挡不住它，防御必须发生在解析阶段。**

| 限制 | 挡什么 |
|---|---|
| 条目数 ≤ 1000 | 几十万个空文件，解压逻辑本身耗尽 CPU |
| 单条目解压后 ≤ 50MB | 单个巨型文件 |
| 总解压体积 ≤ 200MB | 分散成多个中等文件绕过上一条 |
| 压缩比 ≤ 100:1 | 嵌套压缩 |

任一超限直接判为损坏文件，返回「文件无法读取，请检查文件是否完整」。

**两个实现陷阱：**

**① 不能用 `ZipEntry.getSize()` 判断解压后大小。** 该值来自压缩包自己的文件头，
**是攻击者可控的**，可以写一个假的小数字。必须边流式解压边计数：

```java
// 错误：信任声明值
if (entry.getSize() > MAX_ENTRY_SIZE) { reject(); }

// 正确：读多少算多少，超限立刻中断
long total = 0;
while ((n = in.read(buf)) > 0) {
    total += n;
    if (total > MAX_ENTRY_SIZE) { throw new CorruptFileException(); }
    out.write(buf, 0, n);
}
```

**② 直接把文件丢给 POI 或 ofdrw，解压是它们做的，上面的限制根本不会生效。**
两条路线二选一：

```
DOCX  →  配置 POI 自带的防护（ZipSecureFile.setMinInflateRatio /
         setMaxEntrySize、IOUtils.setByteArrayMaxOverride），不要自己造轮子。
         具体默认值随 POI 版本变化，落地时按实际依赖版本核对
OFD   →  ofdrw 是否有等价机制需实际查证；没有则在交给它之前自行预扫描一遍
```

> **优先级取决于威胁模型。** 上传接口若在企业内网、仅食堂员工可达，
> 与公网开放完全是两回事。最低成本的做法是先把 POI 那几个开关配上（几行代码），
> OFD 路径按 §11-5 的可行性验证结论再定。

**解析路由：**

| 格式 | 文本层 | 图像 |
|---|---|---|
| PDF（有文本层） | PDFBox 抽取 | 逐页渲染 |
| PDF（无文本层） | OCR | 逐页渲染 |
| OFD | ofdrw 抽取，失败则 OCR | 转图 |
| JPG / PNG | OCR | 原图 |
| DOC / DOCX | POI 抽取 | **无版面图** |

**PDF 是否有文本层：** 抽取字符数 / 页数 ≥ 50 且非空白字符占比 ≥ 30%，任一不满足走 OCR。

### 3.2 Segment：回切原文的唯一凭据

需求 §6-3 要展示「报告原文完整描述，便于用户与纸质报告核对」。要做到这点必须解决两件事：
**规范化会改字符**，以及**模型的复述不可信**。

#### 3.2.1 解析器把每个文件切成不可变的 segment

| 来源 | 一个 segment 是 |
|---|---|
| PDF 原生文本层 | 一个文本块；表格区为**一个单元格** |
| OCR | 一个识别块（附 bbox） |
| DOCX / DOC | 一个段落；表格区为**一个单元格** |

```
segmentId = f{fileIndex}-p{page}-s{seq}     例：f0-p2-s17
```

`page` 对 Word 是逻辑分块序号（§3.3.1）。`seq` 在文件内单调递增，**一经分配不再变化**。

每个 segment 保存：

| 字段 | 说明 |
|---|---|
| `rawText` / `normalizedText` | 两份文本，见 §3.2.2 |
| `textSource` | **`NATIVE`**（PDFBox / ofdrw / POI 抽出）或 **`OCR`**。决定 §3.2.3 的校验档位 |
| `bbox` | 仅 OCR 场景，P2 用于原图高亮，V1 不用 |

#### 3.2.2 原文与规范化文本

部分 PDF 生成器会把部首区字符混入正文（`⺩` U+2EA9 CJK RADICAL JADE 占了「王」的位置），
所以需要规范化。

> **NFKC 只解决一半问题。** 康熙部首区（U+2F00–U+2FD5）有兼容分解，NFKC 会自动还原；
> **CJK 部首补充区（U+2E80–U+2EFF）没有兼容分解，NFKC 对它们无效**，必须靠手工映射表。
> 这也是这张表只覆盖 U+2E80–U+2EFF 的原因。
但**规范化后的文本已经不是报告原文**——全角数字、波浪线、特殊单位字符都可能被改写。

| 字段 | 内容 | 用途 |
|---|---|---|
| `rawText` | 解析器抽出的**原始字符**，一个字都不动 | **展示、原文核对** |
| `normalizedText` | NFKC → 部首映射表（U+2E80-2EFF，约 30 条）→ 全角转半角 | 送模型、关键词匹配、去重、字符串比对 |

**模型只看到 `normalizedText`，且每段前缀 `segmentId`。** 模型返回定位时只返回 `segmentId`。

#### 3.2.3 两种回切，粒度不同

```
需要展示「报告原文完整描述」的字段（健康问题 rawText、饮食建议来源原文、textualFindings）
  → 直接取该 segment 的整段 rawText，不做字段级切分
  → segmentId 不存在 → 该条整条丢弃

指标卡片的五个短字段（name / value / unit / refRange / conclusionText）
  → 用模型返回值展示，但必须通过包含性校验（档位见下）
  → 任一字段校验不过 → 该指标整条丢弃
```

**包含性校验必须按 `textSource` 分档，不能一刀切严格匹配：**

| `textSource` | 校验方式 | 理由 |
|---|---|---|
| `NATIVE` | **严格子串**：规范化(字段值) 必须是 `normalizedText` 的子串 | 原生文本层就是真值，对不上说明模型编了 |
| `OCR` | **放宽**：去除全部空白后子串匹配；仍不中则归一化后编辑距离 ≤ 1 | OCR 本身有误差，严格匹配会杀掉正确结果 |

**为什么 OCR 必须放宽：** 扫描件和拍照件的文本是**从图片 OCR 出来的**，
是同一份信息的降级副本。OCR 把 `2.8` 认成 `2.6` 时：

```
segment.normalizedText  含「甘油三酯 2.6」    ← OCR 错了
模型看图，正确读出       value = "2.8"
严格子串校验             "2.8" 不在该 segment 内 → 这条正确的指标被丢弃
```

**OCR 的错误会把模型正确的抽取结果杀掉**，而拍照上传是主流形态。
放宽的代价是防幻觉强度略降——但模型编造的值通常与 OCR 文本差得远，
编辑距离 1 的窗口拦不住的极少。`ocrFuzzyMatchCount` 单独计数，用于观测放宽档被用了多少次。

**为什么不给指标做字符区间切分：** 规范化会改变字符数（NFKC 把 `㎎` 拆成 `mg`），
字段级 offset 需要维护 raw↔normalized 的逐字符映射表，实现和测试成本远高于收益。
包含性校验已经能保证"这五个值确实出自这个 segment"，而这正是"不是幻觉"的判据。

**一个 segment 含多个指标是正常的**（表格单元格粒度下少见，文本块粒度下常见），
多条指标共享同一 `segmentId` 不冲突——包含性校验逐条独立执行。

菜品数据（可能来自 Excel/PDF 导入）同样走规范化，但只用于匹配，展示菜名用原始值。

记录 `residualNonStandardCount` 计数用于发现新污染形态，**只记计数不记原文**。

### 3.3 容量限制与降级

#### 3.3.1 Word 不按页计算（§15 评审）

POI 拿不到 Word 渲染后的页数——分页由渲染引擎决定，与文档结构无关。
因此 DOC/DOCX **不参与页数模型**，用独立规则：

```
逻辑分块：每 40 个 segment 记为 1 个"等效页"，用于统一容量核算与 LLM-A 分批
表格：每个单元格一个 segment，不按行合并
内嵌图片：Word 里常见"扫描件贴进 Word"的形态
          ≥ 300×300px 的内嵌图片提取出来走 OCR，产出的识别块也是 segment
          < 300×300px 视为装饰图，忽略
上限：segment 数 ≤ 1200，内嵌图片 ≤ 30，超出按 PAGE_LIMIT_EXCEEDED 拒绝
```

**只抽正文会漏掉贴图形式的报告**，所以内嵌图片必须提取并 OCR，这是 Word 路径的必做项。

#### 3.3.2 页数上限与降级

单任务累计"等效页数"（PDF/OFD/图片按真实页数，Word 按 §3.3.1 折算）：

```
≤ 30 页   → 全部处理，四个模块正常输出
31~60 页  → 只处理前 30 页，置 partial = true / partial_reason = PAGE_TRUNCATED
> 60 页   → 创建任务时直接拒绝，failCode = PAGE_LIMIT_EXCEEDED
```

**`partial = true` 的后果按原因区分，见 §4.1.1 的降级矩阵。**
页数截断这一类的后果是：**模块三与模块四整体不输出。**

总检结论、医生建议、过敏原筛查结果**几乎总在报告末尾**。截断后继续出饮食建议和菜品推荐，
等于基于"确定缺失了关键部分"的信息给出饮食指导——可能漏掉过敏原、漏掉低盐低嘌呤医嘱，
而页面上看不出任何异常。

结果接口返回 `partial` 与 `processedPages / totalPages`，前端据此隐藏后两个模块。
降级文案需产品确认（§12-5）。

**LLM-A 按 ≤8 等效页/批**分批调用，图像统一渲染到长边 2000px。

## 4. LLM-A 抽取

### 4.1 分批与批次裁决

每批：该批 segment 的 `normalizedText`（每段前缀 `segmentId`）+ 对应页面图像。
DOC/DOCX 只有文本，加上从内嵌图片 OCR 出来的 segment（§3.3.1）。

```
每任务 LLM-A 调用次数 = ceil(等效页数 / 8)，上限 4 批
任一批调用失败（超时 / 429 / 5xx / 连接中断）→ 整任务立即 FAILED / SERVER_ERROR
```

**不做批次级重试。** 服务端出错直接返回错误，由用户决定要不要重新解析（§2.5）。
代价是瞬时抖动也会变成一次用户可见的失败，实际失败率需上线后观测（§11-13）。

**在线链路只使用 LLM-A**，但 LLM-A 本身分批，一份 22 页报告是 3 批
——"在线只有一次模型调用"的说法是错的，此处更正。

#### 4.1.1 批次状态与降级矩阵

每批返回 `batchStatus`，**三态，不是两态**（§6 评审）：

| batchStatus | 含义 |
|---|---|
| `OK` | 正常识别 |
| `NO_REPORT_FEATURE` | 读得清，但这几页看不出体检报告特征（封面、须知、广告页） |
| `UNREADABLE` | **读不清**，可能有内容但看不出来 |

这两者必须分开。`NO_REPORT_FEATURE` 是"确定没有"，`UNREADABLE` 是"不知道有没有"
——把后者当成前者，就是把"模型没看清"当成"报告里没有"，违反 §0-4。

**文件级与任务级裁决：**

```
某文件全部批次 NO_REPORT_FEATURE  → 该文件不是体检报告
全部文件都不是体检报告            → 整任务 FAILED / NOT_HEALTH_REPORT
全部批次都 UNREADABLE             → 整任务 FAILED / UNREADABLE
任一批次 UNREADABLE（但非全部）    → 该批内容丢弃，其余批照常
                                     partial = true，partial_reason = BATCH_UNREADABLE
```

**降级矩阵：**

| partial_reason | 模块一 指标 | 模块二 问题 | 模块三 饮食建议 | 模块四 菜品推荐 |
|---|---|---|---|---|
| `PAGE_TRUNCATED`（§3.3.2） | ✅ | ✅ | ❌ | ❌ |
| `BATCH_UNREADABLE` | ✅ | ✅ | ❌ | ❌ |
| `ALLERGEN_SUSPECT_MISS`（§4.4-②） | ✅ | ✅ | ✅ | ❌ |

前两类要关掉模块三，因为**读不清的那一批恰好可能就是含过敏原和医嘱的那一批**
——继续输出饮食建议和菜品推荐，与 §3.3.2 的截断降级原则自相矛盾。

结果接口下发 `partial`、`partialReason`、`suppressDietAdvice`、`suppressDishRecommend`，
前端据此隐藏对应模块。

#### 4.1.2 多批合并

| 项 | 规则 |
|---|---|
| 跨批章节排序 | 按 `fileIndex → page → 批内 sourceOrder`，不按批次号 |
| 跨批去重 | **只在批次输入确有重叠时执行**，判据是 `segmentId` 相同；见 §4.1.3 |
| 姓名只在部分批出现 | 取所有非空值参与 §4.5 比对；全空视为该文件未识别出姓名 |

#### 4.1.3 去重只认 segmentId（§14 评审）

原设计按 `sectionName + name + value + unit` 四元组去重，**这会误删真实检查结果**：

```
左眼视力 5.0  /  右眼视力 5.0          ← 双侧检查，数值本来就相同
静息心率 72   /  运动后心率 72          ← 不同时间点
2023 复查值   /  2024 本次值           ← 报告附历史对比
```

改为：**同一 `segmentId` 且同一 `itemIndex` 才判定为重复**（同一段原文被两批各抽了一次），
保留 `page` 较小的一条。**非重叠页面之间一律不去重。**

#### 4.1.4 并发与资源控制

**批次之间全部并行，不串行。** 这不是优化，是必需——串行跑不完自己的 deadline：

```
一份 30 页报告，串行：
  OCR 30 页              60 ~ 90s     （按 2~3s/页）
  LLM-A 串行 4 批        240 ~ 720s   （每批 60~180s，视觉模型带 8 页图不快）
  组装                   < 10s
  合计                   310 ~ 820s   ← 上界超过 §2.3.4 的 600s deadline

并发后：90 + max(180) + 10 ≈ 280s
```

批次结构上互相独立：每批拿自己的 segment 和图像，返回自己的抽取结果，
没有哪一批需要另一批的输出。合并（§4.1.2）按 `fileIndex → page → sourceOrder` 排序，
**不按批次完成顺序**，姓名合并、跨批去重、文件级裁决也都是拿到全部批结果之后才算，
乱序返回天然无影响。

**并发度的上界由模型服务的配额倒推：**

```
峰值在飞 LLM-A 调用数 = Worker 并发任务数 W × 单任务批次并发度 4

设模型服务允许的并发配额为 C，则   W = floor(C / 4)
```

**不设独立的全局信号量。** `W × 4` 已经是硬上界，再叠一层信号量就有两个旋钮要调，
而且 `W` 本身还受队列深度背压（§2.2）约束。信号量的价值是"小任务多跑几个"提高利用率
——但本系统每个用户一份报告只分析一次，吞吐压力极低，那点利用率不值得多一个组件。

> 只有当实测发现 `W = floor(C/4)` 明显跑不满配额、且确实存在排队时，才回来加信号量。

**这条尺寸现在是唯一的限流保护。** 全案零重试（§2.5），一个 429 就是一次用户可见失败，
所以 `W` 必须卡在配额之下，宁小勿大。模型服务的并发配额 `C` 需向服务方确认（§11-15）。

**内存：并发后必须只持有编码字节，不持有 `BufferedImage`。**

```
BufferedImage 2000×2800 × 3 字节 ≈ 16MB / 页
  32 页同时持有 ≈ 512MB / 任务          ← 几个任务并发就 OOM

渲染完立即编码 JPEG 并释放 BufferedImage
  编码后 ≈ 300~800KB / 页
  32 页 ≈ 20MB / 任务                   ← 差 25 倍
```

串行时同时只有 8 页，这个问题不明显；并发后是致命的。

**任一批失败时不取消其余批次**，让它们跑完再丢弃结果。取消传播要引入
`Future.cancel` + 中断处理 + 半途响应的清理，换来的只是几秒的模型调用成本。
整任务已经确定要 `FAILED`，早几秒晚几秒无差别。

**心跳独立线程。** Worker 每 30s 更新 `heartbeat_at`（§2.3.4）必须由独立的调度线程执行，
不能挂在批次处理的主流程里——批次并行等待期间主流程是阻塞的，心跳停了会被巡检误杀。

### 4.2 输出契约

> **本节是可读示例，不是契约本身。** 正式契约是 `schema/llm_a_output.schema.json`，
> 需定义类型、`null` 规则、字符串长度上限、数组条数上限、枚举取值、
> `additionalProperties: false`、各序号字段最小值、以及 `batchStatus != OK` 时各字段的取值要求。
> **该文件是开发前置交付物，并纳入契约测试。**

```jsonc
{
  "batchStatus": "OK | NO_REPORT_FEATURE | UNREADABLE",

  // 仅用于同一性校验，Java 用完即丢，不入任何存储
  "patient": { "name": "张三|null", "gender": "男|女|null" },

  // 报告自带的汇总数字，没有则为 null（不许模型自己算）
  "reportOverview": { "totalCount": 0, "abnormalCount": 0 },

  "sections": [{ "sectionName": "血脂检查", "fileIndex": 0, "sectionIndex": 2 }],

  // 有数值 + 有结论 → 健康指标模块（正常项也要提）
  "indicators": [{
    "name": "甘油三酯",
    "value": "2.8",
    "unit": "mmol/L",
    "refRange": "0.56~1.70",          // 报告没写则 null
    "conclusionText": "↑偏高",
    "status": "NORMAL | HIGH | LOW | ABNORMAL",
    "statusJudgedByModel": false,
    "sectionIndex": 2,
    "orderInSection": 3,
    "segmentIds": ["f0-p2-s17","f0-p2-s18"], "sectionSegmentId": "f0-p2-s15"          // ★ 唯一定位凭据，五个字段必须都能在该段内找到
  }],

  // 无数值 + 有结论 → 候选健康问题
  "textualFindings": [{
    "title": "脂肪肝",
    "conclusionText": "提示脂肪肝",
    "status": "NORMAL | ABNORMAL",
    "includeInHealthProblems": true,
    "sectionIndex": 5,
    "orderInSection": 1,              // ★ 补：§6.3 排序要用
    "segmentIds": ["f0-p3-s4"], "sectionSegmentId": "f0-p3-s1"
  }],

  // 总检结论 / 医生建议，逐条
  "summaryConclusions": [{
    "sourceOrder": 2,                 // ★ 0 起连续序号，排序真源
    "itemNo": 3,                      // 报告原文编号，无编号时为 null，仅用于来源标注文案
    "categories": ["HEALTH_PROBLEM", "DIET_ADVICE"],   // ★ 数组，一条可含多种语义
    "includeInHealthProblems": true,
    "sectionIndex": 9,
    "segmentIds": ["f0-p5-s12"], "sectionSegmentId": "f0-p5-s10"
  }],

  "allergens": [{
    "enumKey": "SHRIMP_CRAB | ... | OTHER",
    "isFoodBorne": true,
    "rawName": "虾蟹类",
    "rawResult": "阳性(+)",
    "resultStatus": "POSITIVE | NEGATIVE | BORDERLINE | UNKNOWN",
    "sectionIndex": 7,
    "segmentIds": ["f0-p4-s9"], "sectionSegmentId": "f0-p4-s8"
  }],

  // ★ 一条原文可拆成多个枚举条目，共享同一 segmentId，各自给不同 itemIndex
  //   「建议低脂低盐饮食」→ 两条：LOW_FAT 与 LOW_SODIUM
  "nutritionSupplements": [{
    "enumKey": "IRON | ... | OTHER",
    "itemIndex": 0,
    "segmentIds": ["f0-p5-s14"], "sectionSegmentId": "f0-p5-s10"
  }],

  "dietRequirements": [{
    "enumKey": "LOW_FAT | ... | OTHER",
    "itemIndex": 0,
    "segmentIds": ["f0-p5-s15"], "sectionSegmentId": "f0-p5-s10"
  }, {
    "enumKey": "LOW_SODIUM",
    "itemIndex": 0,
    "segmentIds": ["f0-p5-s15"], "sectionSegmentId": "f0-p5-s10"
  }]
}
```

**契约里没有任何"原文字符串"字段。** 展示用的原文一律由 Java 按 `segmentId`
从 `rawText` 取整段（§3.2.3），模型只负责指路。

### 4.3 提示词的硬约束

1. **只抽报告里写了的，不推断、不补充、不改写。**
2. **准入三分法**（需求 §5-2）：有数值+有结论 → `indicators`（**含结论为正常的**）；
   有数值+无结论 → 全部丢弃；无数值+有结论 → `textualFindings`。
3. **不许把"没找到"写成空数组。** 每个字段都必须出现；确实没有内容才给空数组，这是主动断言。
4. **枚举归一化只做精确语义匹配，宁可给 `OTHER` 也不要就近映射。**
   **一条原文含多个要求时拆成多条**，共享 `segmentId`，各自给不同的 `itemIndex`。
5. **饮食相关表述的抽取范围：总检结论 + 医生建议章节**（含「专家建议」「健康指导」
   「医师建议」等同义章节名）。**排除各科小结、检查须知、科普段落、检查前准备**
   ——「胃镜检查前禁食」「检查前三天低脂饮食」是临时要求，不是长期饮食建议（§12-6）。
6. **`status` 的判定权分级。** 报告原文已给出**明确方向标记**的（↑ ↓ 偏高 偏低 增高
   降低 升高 减低 正常 未见异常 异常 H L），必须照抄词表口径，`statusJudgedByModel = false`。
   **非方向性结论**——「阳性(+)」「阴性(-)」「弱阳性」「可疑」「临界」——由模型
   结合该指标的临床含义判断，`statusJudgedByModel = true`。

   > **「阴性」同样不能无条件当成正常（§16 评审）：**
   > ```
   > 过敏原-虾蟹类   阴性(-)  →  正常
   > 乙肝表面抗体    阴性(-)  →  不是"正常"，是没有抗体，报告通常提示接种疫苗
   > 大便隐血        阴性(-)  →  正常
   > ```
   > 原设计把「阴性」直接放进正常词表，与"阳性不一定异常"的论证是同一个错误的两面。
   > 现在阴性与阳性走同一条路径：报告有明确标记时照抄，没有时交给模型判断。

   判断依据只能是该指标本身的临床含义，不得参考其他指标、不得做整体健康评价。

7. **`textualFindings.status`**：无数值的文字结论**同样有正常项**——
   「肝胆B超：未见明显异常」「心电图：窦性心律，正常心电图」。
   `status = NORMAL` 的条目 `includeInHealthProblems` 必须为 `false`。
8. **`summaryConclusions.categories` 是数组**，一条可以同时命中多种：
   「超重，建议控制体重，低脂饮食」= `[HEALTH_PROBLEM, DIET_ADVICE]`。
   只要含 `HEALTH_PROBLEM` 或 `DIET_ADVICE`，`includeInHealthProblems` 才可以为 `true`。

   | category | 例子 |
   |---|---|
   | `HEALTH_PROBLEM` | 甲状腺结节，建议复查；超重 |
   | `DIET_ADVICE` | 建议低脂低盐饮食 |
   | `LIFESTYLE` | 建议戒烟限酒；保持规律作息 |
   | `ROUTINE` | 建议每年参加健康体检；建议继续保持适量运动 |
   | `NORMAL_STATEMENT` | 各项检查未见明显异常 |

9. **`allergens.resultStatus` 必须逐项给出。** 完整的过敏原筛查表会列 20~40 项，
   绝大多数是阴性。**抽取时全部列出并如实标注结果**，由 Java 按 §4.4 过滤，
   不许模型自行省略阴性项——省略了 Java 就无法核对模型是不是漏抽了阳性项。

### 4.4 Java 校验层

**① Schema 校验。** 任一必填字段缺失 → **直接** `FAILED / SERVER_ERROR`，不重试。

模型返回结构不合法是提示词或模型本身的问题，同样的输入再问一遍多半还是同样的结果；
重试只会把一次确定的失败拖成两倍延迟。`schemaMissCount` 升高时应改提示词或换模型，不是加重试。

> **"Schema 通过"不等于"报告已完整识别"。** 模型返回 `"allergens": []` 结构上完全合法。
> 字段必填只能区分"模型没返回这个字段"，区分不了"模型漏抽了内容"。

**② 高风险内容交叉扫描，命中即安全降级（§7 评审）。**
对全部 segment 的 `normalizedText` 做关键词扫描：

| 扫描目标 | 对应数组为空时 |
|---|---|
| 过敏原章节名（过敏原、变应原、IgE、致敏原） | **立即降级**，不定向重试 |
| 阳性标记（阳性、(+)、＋）出现在过敏章节 segment 内 | 同上 |
| 饮食医嘱词（低脂、低盐、低糖、低嘌呤、忌口、忌食） | 只记计数，不降级 |

```
过敏类命中而数组为空 → partial = true, partial_reason = ALLERGEN_SUSPECT_MISS
                       → 模块一二三照常，模块四整体不输出（§4.1.1）
                       → 记 allergenSuspectMissCount
饮食类命中而数组为空 → 只记 dietSuspectMissCount，不降级
                       （饮食医嘱词误报率高，科普段落里也会出现"低脂饮食"四个字）
```

原设计在这里安排了一次"定向重试"来压低误报率。去掉之后，误报会直接变成一次
模块四不输出——降级方向仍然安全，但触发频率会上升，需用评测集量化（§11-12）。

**原设计对过敏类只告警不阻断，与"过敏是安全红线"不一致，此处更正。**
不把整份报告判失败，而是关掉唯一会导致用户实际吃错东西的模块——
既躲开误拦正常报告，又不会在"可能漏了过敏原"的前提下继续推荐菜。

**③ 状态一致性校验（`ConclusionLabelWords` 常量表）：**

```
词表能命中方向词 conclusionText  →  模型 status 必须与词表一致
                                    不一致：以词表为准，记 statusConflictCount
词表命中不了                      →  采信模型 status，要求 statusJudgedByModel = true
```

词表是子串匹配，「轻度增高」含「增高」→ 直接命中 `HIGH`，不进模型判断路径。
**「阴性」「阳性」不在方向词表内**（§4.3-6）。

**④ 过敏原准入过滤：**

```java
// 仅以下两类进入过敏提醒区与菜品拦截链路：
resultStatus == POSITIVE
resultStatus == BORDERLINE      // 弱阳性/可疑/临界，按 §0-5 安全不对称从严（§12-7）

// 以下一律不进入，且不计入任何过敏计数：
resultStatus == NEGATIVE        // 阴性绝不能进 —— 一张 30 项的筛查表里 28 项是阴性
resultStatus == UNKNOWN         // 不得自动当成阳性，也不得当成阴性；只记 allergenUnknownCount
```

`isFoodBorne` 判断错误会直接驱动高风险推荐，因此 Java 用 `AllergenSectionWords` 常量表
对 `rawName` 反向校验：命中已知非食物词（螨、花粉、皮屑、霉、蟑螂、尘、屋尘）
而模型给了 `isFoodBorne = true` → 以词表为准改为 `false` 并告警。

**⑤ 原文回切（§3.2.3）。**

```
展示原文类字段：segmentId 不存在 → 该条整条丢弃，记 evidenceMissCount
指标五字段    ：包含性校验不过 → 该指标整条丢弃（档位按 textSource，§3.2.3）

★ 被丢弃的条目属于 allergens 时 → 同 ② 的处理：
   partial = true, partial_reason = ALLERGEN_SUSPECT_MISS，模块四不输出
```

过敏原条目回切失败**不能简单丢弃后继续推荐**——丢掉的可能正是那条要命的。

**⑥ 健康问题准入。** 只有 `includeInHealthProblems = true` 的条目进入模块二，
且 Java 反向兜底：整段 `normalizedText` 命中 `NormalStatementWords`（未见异常、正常、
阴性、未见明显异常、无异常）而模型给了 `true` → 强制改为 `false` 并告警。

### 4.5 多文件同一性校验

> **这是一条"发现冲突则拒绝"的弱校验，不是"确认同一人"的强校验**（§6.2 评审）。
> 它拦得住"两份报告写着不同名字"，拦不住"同名不同人"，也拦不住"两份都没识别出姓名"。
> 文档不把它称为同一性证明。

```
取所有 patient.name / patient.gender 非空的值比对
姓名：规范化（去空格、繁简转换）后完全相等即通过
性别：同上
任一不一致 → FAILED / IDENTITY_MISMATCH，不自动合并、不做确认弹窗
```

**已知放行的情况（产品已确认接受，§12-8）：**

| 情况 | 结果 |
|---|---|
| 全部文件都识别不出姓名 | 通过 |
| 只有一份识别出姓名 | 通过 |
| 姓名相同、性别一份缺失 | 通过 |
| 同名不同人 | 通过（无法识别） |
| OCR 把姓名读错一个字 | **拒绝**（误拦，用户重传即可） |

产品已确认：**一次只能分析一个人的报告，不支持代家人分析**，需求文档需补充该限制。

### 4.6 多文件合并（§5.9 评审）

**分组键用结构化 ID，不用章节名字符串：**

```
groupKey     = fileIndex + "-" + sectionIndex        ← 唯一键，跨文件绝不合并
displayName  = 单文件：sectionName
               多文件：「报告{fileIndex+1}-」+ sectionName
```

**去重只发生在同一个文件内**（同一文件跨批的重复条目，按 §4.1.3 的 `segmentId + itemIndex` 去重），
**跨文件一律不去重、不合并**——两份报告都有「血脂检查」时展示为两个独立分组。

`indicators` 合并后分配全局唯一 `indicatorId`，展示顺序不承担主键职责。
`summaryConclusions` 按 `fileIndex → sourceOrder` 排序（`itemNo` 可能为 null，不能当排序键）。

## 5. 模块一：健康指标（需求 §5）

### 5.1 卡片字段

| 字段 | 来源 | 说明 |
|---|---|---|
| 指标名称 | `indicators[].name` | 报告原文，不改写 |
| 检测值 + 单位 | `value` + `unit` | |
| 参考正常范围 | `refRange` | 报告没写时展示「报告未提供」，**禁止填充通用参考值** |
| 报告结论 | `conclusionText` | 报告原文，直接引用 |
| 状态标签 | 见 §5.2 | |

> 「参考正常范围」「报告结论」「指标名称」三项展示的都是**报告原文**，
> 一律由 Java 按 `segmentId` 定位，并做**包含性校验**——档位按该 segment 的
> `textSource` 区分，原生文本层严格子串、OCR 放宽（§3.2.3）。
> 任一字段校验不过，该指标**整条丢弃**。

### 5.2 状态标签：模型判定 + 词表校验

状态四态：`NORMAL` 正常（绿） / `HIGH` 偏高（红↑） / `LOW` 偏低（蓝↓） / `ABNORMAL` 异常（橙）。

> 需求 §5-3 只定义了前三种。第四种（橙）是本方案新增的，用于承载「阳性(+)」「可疑」「临界」
> 这类既不是偏高也不是偏低的结论，**需产品确认**（§12-1）。

`status` 由 LLM-A 输出，但判定权分两级：

| 情况 | 判定方 | 说明 |
|---|---|---|
| 报告原文有**方向性**标记 | **词表（Java 为准）** | ↑↓、偏高/偏低、增高/降低、升高/减低、正常、未见异常、异常、H/L |
| 报告只写了**非方向性**结论 | **LLM-A 判断** | 阳性(+)、**阴性(-)**、弱阳性、可疑、临界 |

> **词表是子串匹配，「轻度增高」含「增高」→ 直接命中 HIGH，不进模型判断路径**（§4.2 评审）。
> 只有整条结论里一个方向词都没有的才交给模型。

**Java 一致性校验（`ConclusionLabelWords` 常量表）：**

```
词表能明确命中 conclusionText  →  模型给的 status 必须与词表一致
                                  不一致：以词表为准，记 statusConflictCount 告警
词表命中不了                    →  采信模型的 status
                                  且要求 statusJudgedByModel = true，否则记计数
模型漏给 status                 →  按 Schema 必填走重试（§4.4）
```

> 护栏的目的不是不信任模型，而是堵住它在最简单的情况下抽风。「甘油三酯 ↑偏高」被判成正常，
> 这种错误比「阳性」判错方向严重得多，而它恰恰是词表百分之百能拦住的。

**为什么这一层要交给模型：** 「阳性」不等于异常，**「阴性」也不等于正常**。

```
过敏原-虾蟹类    阳性(+)  →  异常          过敏原-虾蟹类   阴性(-)  →  正常
乙肝表面抗体     阳性(+)  →  正常（有抗体） 乙肝表面抗体    阴性(-)  →  无抗体，报告通常提示接种
甲状腺球蛋白抗体 阳性(+)  →  异常          大便隐血        阴性(-)  →  正常
```

词表无论怎么写都分辨不了这六种，只有理解指标含义才能分。
**原设计把「阴性」无条件放进正常词表，与"阳性不一定异常"是同一个错误的两面**（§16 评审），
现已改为阴性阳性走同一条路径。这与 §8.4 把过敏打标交给模型是同一条理由。

**展示规则：颜色跟判定，文字跟原文。**

```
标签颜色 = status 对应色
标签文字 = conclusionText 报告原文（如「阳性(+)」），不写「异常」二字
```

即使模型判反了方向，用户看到的仍然是报告原文，不会被系统的措辞误导。

**记录 `statusJudgedByModel = true` 的条数**，上线后抽查判断质量；异常集中在某几个指标名时，
把该指标的确定性规则补进词表，走发版。

### 5.3 分组展示

按 §4.6 的 `groupKey`（`fileIndex + sectionIndex`）分组，**不用 `sectionName` 做键**
——多文件时两份报告都有「血脂检查」，用名字做键会把它们并进同一组。

分组标题用 `displayName`，分组顺序按 `fileIndex → sectionIndex`，组内按 `orderInSection`。
**全部平铺展示，不折叠。**

### 5.4 总览条（需求 §5-5）

```
总指标数 = 本模块展示的指标条数
正常项数 = status 为 NORMAL 的条数（绿）
异常项数 = status 为 HIGH + LOW + ABNORMAL 的条数
异常占比 = 异常项数 / 总指标数，四舍五入到整数百分比
```

> 这意味着「阳性」这类结论**是否计入异常项，由 LLM-A 的 `status` 决定**。
> 异常占比是用户看到的第一个数字，而它现在带有模型判断的成分——这是 §12-1 要一并确认的。
>
> **后端评审对「报告自带数字优先」提出反对（§12-9）：** 报告的「总项目数」通常包含大量
> 只有数值没有结论、因而不在本模块展示的指标，且 `reportOverview` 一般不提供正常项数。
> 结果是总览条显示 87 项而下方只有 52 张卡片，异常占比与展示内容无法核对。评审建议
> 总览条一律按本模块卡片计算，报告自带数字另设「报告原始汇总」一栏。
> **产品当前决策为不处理该不一致**，此处保留原设计，评审意见记录在案。

**报告自带汇总数字优先**：`reportOverview` 非空时，展示报告的数字，并在旁边标注「（报告原文）」。
两者不一致不做纠错、不告警，以报告为准。

`reportOverview` 为空时用上面的公式计算。

**不加任何差值说明文案。** 总指标数是本模块展示的条数，可能少于报告实际检查项目数
（只列数值不给结论的项按需求 §5-2 不展示，这类项在生化全套、血常规里常占 30~40%）。
产品已确认该不一致不作处理。

### 5.5 底部声明

> 以上指标数据均来自体检报告原文，仅供参考，如有疑问请咨询医生。

### 5.6 空态

无任何符合准入条件的指标时，展示「本次报告未提取到带明确结论的指标项」，
并**保留总览条位置展示 0**，不隐藏模块。

---

## 6. 模块二：健康问题（需求 §6）

### 6.1 三类来源与准入

**只有 `includeInHealthProblems = true` 的条目进入本模块**（§4.4-⑥）。
这条准入是必须的——`textualFindings` 和 `summaryConclusions` 里都有正常项：

```
肝胆B超：未见明显异常          ← textualFindings，status = NORMAL
心电图：窦性心律，正常心电图    ← textualFindings，status = NORMAL
各项检查未见明显异常           ← summaryConclusions，category = NORMAL_STATEMENT
建议继续保持适量运动           ← summaryConclusions，category = ROUTINE
建议每年参加健康体检           ← summaryConclusions，category = ROUTINE
```

无条件纳入的话，用户会在「健康问题」标题下看到「未见明显异常」，
直接违反需求 §6-1「仅汇总明确给出的异常结论和健康提示」。

| 来源类型 | 数据来源 | 准入条件 | 携带 `indicatorId` |
|---|---|---|---|
| `INDICATOR_NUMERIC` | `indicators` | `status != NORMAL` | **是** |
| `INDICATOR_TEXTUAL` | `textualFindings` | `includeInHealthProblems = true` | **否** |
| `SUMMARY` | `summaryConclusions` | `includeInHealthProblems = true` 且 `categories` 含 `HEALTH_PROBLEM` 或 `DIET_ADVICE` | **否** |

**`INDICATOR_TEXTUAL` 不带 `indicatorId`（§3.4 评审）：** 「脂肪肝」这类无数值结论
按 §5.1 的准入规则**根本不会生成健康指标卡片**，没有可跳转的目标。
原设计把它归入 `INDICATOR` 大类并统一要求关联跳转，是无法实现的。

前端据此渲染：只有 `INDICATOR_NUMERIC` 显示跳转按钮，另两类不显示
（符合需求 §6-3「若该问题来自总检结论且不直接对应某个指标，则不展示关联按钮」）。

### 6.2 条目字段

| 字段 | 生成方式 |
|---|---|
| `displayName` | `INDICATOR_NUMERIC`：优先取报告原文中的自然语言问题名；报告只有指标名和符号时，拼接「指标名 + 归一化结论词」（如「甘油三酯偏高」）<br>`INDICATOR_TEXTUAL`：直接用 `title`<br>`SUMMARY`：直接用回切后的原文 |
| `displayNameGenerated` | **布尔值**。`true` = 该名称由系统拼接而非报告原文（§4.4 评审、§12-10） |
| `sourceLabel` | 来源标注。单文件「血脂检查–甘油三酯」「总检结论第3条」；多文件加报告前缀「报告2-专家建议第2条」。**章节名取 §4.6 的 `displayName`，不写死「总检结论」** |
| `rawText` | 按 `segmentId` 取该 segment 的**整段原文**（§3.2.3）。segmentId 不存在则该条丢弃 |
| `indicatorId` | 仅 `INDICATOR_NUMERIC` 下发 |

`displayNameGenerated` 是给产品和测试用的：需求 §6-2 要求「直接引用报告原文表述，不做改写」，
而拼接严格说不是引用。有这个字段，验收时能数出有多少条是拼的，产品也能决定 UI 上要不要区别对待。

### 6.3 排序（需求 §6-4）

```
INDICATOR_NUMERIC + INDICATOR_TEXTUAL 在前，按 fileIndex → sectionIndex → orderInSection
SUMMARY 在后，按 fileIndex → sourceOrder
不做严重程度分级、不做风险排序
```

`textualFindings` 的 `orderInSection` 是本轮补上的字段（§4.2）——原契约没有它，
排序规则却在用，会导致同章节内的文字结论顺序不稳定。
`SUMMARY` 用 `sourceOrder` 而非 `itemNo`，因为报告的总检结论**不一定编号**。

### 6.4 空态与声明

三类来源全空时：

> 本次体检各项指标均在正常范围内，请继续保持良好的生活习惯。

底部声明：

> 以上内容均为体检报告原文结论的汇总，不构成二次诊断，如有疑问请咨询医生。

## 7. 模块三：饮食建议（需求 §7）

### 7.1 三条硬约束

1. **不从指标异常推导建议。** 甘油三酯偏高 ≠ 低脂饮食，只有报告明文写了才生成。
2. **不合并同向建议。** 「低脂饮食」与「控制体重」各自成卡片，各自引用各自原文。
3. **不在饮食建议中引用指标数据。** 不出现「因您的甘油三酯 2.8」这类表述。

> 守法方式是结构性的：本模块的输入只有 `enumKey` + `rawText`，**结构上看不到指标数据**，不靠提示词嘱咐。

**这条约束的直接后果（已确认接受）：**

```
一份报告：甘油三酯 3.5↑↑、总胆固醇 6.8↑、脂肪肝
         总检结论只写「血脂偏高，建议复查」

→ 健康指标：正常展示，一片红标签
→ 健康问题：正常展示，三条
→ 饮食建议：三个分区全空态
→ 菜品推荐：「本食堂菜品暂无个性化推荐。」
```

医生写总检结论时并不总会写饮食建议，很多就是「建议复查」「建议专科就诊」。
这类报告的用户会看到后两个模块全空。**这是需求 §7-5 的明确选择，不是缺陷**，
产品已确认按此实现（报告里空的就是空的），不做通用建议兜底。

### 7.2 枚举清单（待评审确认）

**食入性过敏原**（参与菜品匹配）

**枚举与词表同源于 `allergen_display_split.csv`。**
该文件是**参考基线，不是不可变真源**——当前 11 组 73 词覆盖不全，扩充见 §7.2.1。
但任何增删都会改变 Layer 1 的拦截行为，**属安全变更，须走医务评审后更新 CSV**，
不得在代码里绕过 CSV 直接加词。

| enumKey | 展示名 | avoid 词数 | hidden 词数 |
|---|---|---|---|
| `SHRIMP_CRAB` | 虾蟹类 | 10 | 9 |
| `FISH` | 鱼类 | 3 | 3 |
| `MILK` | 牛奶及乳制品 | 10 | 2 |
| `EGG` | 鸡蛋 | 5 | 3 |
| `PEANUT` | 花生 | 2 | 2 |
| `SOY` | 大豆 | 5 | 1 |
| `WHEAT` | 小麦麸质 | 3 | 2 |
| `NUTS` | 坚果 | 6 | 0 |
| `MANGO` | 芒果 | 1 | 0 |
| `BEEF` | 牛肉 | 3 | 0 |
| `MUTTON` | 羊肉 | 3 | 0 |

#### 7.2.1 待扩充的过敏原组

当前 11 组是按常见八大类整理的，与国内体检机构实际的食入性筛查面板对不齐。
**建议补充的组已在 `constants/内容常量草案.md` §1.1 起草成可直接并入 CSV 的词表**，
医务评审通过后合并。

| 优先级 | 组 | 缺失的后果 |
|---|---|---|
| **高** | `SHELLFISH` 贝类 | 蛤蜊、生蚝、扇贝、鲍鱼在筛查面板中常见；蚝油在中餐里无处不在，落 `OTHER` 只能字面匹配 |
| **高** | `SESAME` 芝麻 | 麻酱、香油在凉菜、火锅蘸料、烧饼里极常见，且「香油」这个名字看不出是芝麻 |
| 中 | `PINEAPPLE` 菠萝 | 面板常见项，咕咾肉等菜品含而名称不显 |
| 待评估 | `PORK` 猪肉 / `CHICKEN` 鸡肉 | 面板有此项。中餐里「肉丝」「肉末」默认指猪肉，鸡精是通用调味料，收录会导致大面积拒绝 |

> **扩充过敏原组会显著提高拒绝率。** 以 `SESAME` 为例，收录「香油」后，
> 对芝麻过敏用户，食堂凉菜类会被大面积排除——这是**正确**行为（那些菜确实含芝麻油），
> 但产品需要知道推荐列表会变得很短。这是 §0-5 安全不对称的直接代价。

**吸入性/接触性过敏原**（只展示，**不参与菜品匹配**）

`DUST_MITE` 尘螨 / `POLLEN` 花粉 / `ANIMAL_DANDER` 动物皮屑 / `MOLD` 霉菌 / `COCKROACH` 蟑螂

> 国内过敏原筛查普遍分吸入组和食入组，吸入组不是食物。

**`isFoodBorne` 是纯内部字段，不影响展示分组。** 全部过敏原**统一放在过敏提醒区，
按报告原文顺序混排**，不按食入性/非食物分组、不排序、不加分区标题。该字段只决定两件事：

| | 列食材清单 | 参与菜品匹配 |
|---|---|---|
| `isFoodBorne = true` | ✅ 需避免食材 + 易忽略的加工食品 | ✅ |
| `isFoodBorne = false` | ❌ 卡片上只有过敏原名称和来源标注 | ❌ |

**`OTHER` 必须按 `isFoodBorne` 拆成两条路径，不能混为一谈：**

| | 例子 | 过敏提醒区 | 菜品推荐 |
|---|---|---|---|
| `OTHER` + `isFoodBorne=true` | 芹菜、芥末、亚硫酸盐 | 展示原文 + 来源 | 走 §8.5 名称匹配 |
| `OTHER` + `isFoodBorne=false` | 艾蒿、豚草、真菌 | 展示原文 + 来源 | **完全不进菜品链路** |

> 不拆的后果是误杀：§8.5 会拿过敏原名称去跟菜名和食材做字符串匹配，
> 而不少植物性吸入过敏原的名字本身就是食材（艾蒿↔青团的艾草、桑↔桑葚、豚草↔某些野菜）。
> 报告写「艾蒿花粉 阳性」时会把含艾草的菜判成过敏不推荐——用户对花粉过敏，吃艾草并无问题。

**营养补充**

`IRON` 铁 / `CALCIUM` 钙 / `PROTEIN` 蛋白质 / `VITAMIN_D` 维生素D / `VITAMIN_B12` 维生素B12 /
`FOLATE` 叶酸 / `DIETARY_FIBER` 膳食纤维 / `ZINC` 锌 / `POTASSIUM` 钾

**饮食注意**

`LOW_FAT` 低脂 / `LOW_SODIUM` 低盐 / `LOW_ADDED_SUGAR` 限制添加糖 / `LOW_PURINE` 低嘌呤 /
`LOW_CHOLESTEROL` 低胆固醇 / `LOW_CALORIE` 控制体重 / `HIGH_FIBER` 高纤维 /
`LIMIT_ALCOHOL` 限酒 / `LIGHT_DIET` 清淡饮食

合计 **34 个正式枚举 + `OTHER`**（11 食入性过敏原 + 5 非食物过敏原 + 9 营养补充 + 9 饮食注意）。

### 7.3 高危表述强制转 OTHER（Java 黑名单）

模型归一化可能出现方向性错误——把「低蛋白饮食」映射到 `PROTEIN`（蛋白质补充）会直接导致肾病患者被推荐高蛋白菜品。
因此 Java 在收到 LLM-A 输出后，对 `rawText` 做一次黑名单扫描：

```
低蛋白 / 限蛋白 / 优质低蛋白 / 低钾 / 限钾 / 低磷 / 限磷
低碘 / 限碘 / 忌碘 / 高碘
妊娠 / 孕期 / 哺乳期 / 儿童
```

命中即**强制改为 `OTHER`，无论模型给了什么枚举**。这是全案唯一一处 Java 推翻模型输出的归一化规则。

### 7.4 OTHER 的处理

`enumKey = OTHER` 时：

- **照常展示**该条建议的报告原文与来源标注（需求 §7 要求展示报告里写的每一条）
- **不加任何说明文字**（产品决策，§12-3）
- **不生成食材清单、不参与菜品匹配、不进入打标维度**

> **这不满足需求 §7-3（§4.6 评审）。** 需求要求每个过敏原都列避免食材和易忽略食物、
> 每个营养素都列推荐食材/摄入量/搭配建议、每个饮食要求都列推荐食材/限制食材/烹饪建议。
> 「展示了原文」不等于「完成了该条饮食建议」。产品已确认接受该降级（§12-11），
> 需求文档需同步修改。
- 记录 `adviceOtherCount` 计数用于评估枚举表是否需要扩充

### 7.5 内容常量

每个正式枚举对应一份硬编码内容常量（`DietAdviceContent` 常量类），上线前由营养师/医务审核一遍，
改动随代码发版。字段按需求 §7-3 定义：

```java
// 过敏提醒（食入性）—— 直接从 allergen_display_split.csv 加载，不手写
class AllergenGroup {
    String key;                   // SHRIMP_CRAB
    String displayName;           // 虾蟹类
    List<Word> avoidIngredients;  // 该词本身即该过敏原或其直接制品：虾、蟹、虾仁、虾米
    List<Word> hiddenFoods;       // 该过敏原是其成分之一但品类名看不出来：虾丸、蟹棒、XO酱
}
// Word { matchWord（用于 Layer 1 匹配，宽松）, displayName（用于展示，已审核）}
// 两条永久不变量，由单测强制：
//   avoidIngredients ∩ hiddenFoods = ∅
//   两者 matchWord 的并集 == 该 key 在 CSV 中的全部 matchWord
// 前者防重复展示，后者防「展示了但不匹配」和「匹配了但不展示」

// 营养补充
class NutritionContent {
    String[] recommendIngredients; // 推荐食材 3~8 种：猪肝、鸭血、菠菜、黑木耳、红枣、瘦牛肉
    String[] intakeNotes;          // 摄入量说明：猪肝每周 1~2 次，每次约 50g
    String[] pairingTips;          // 搭配贴士：铁与维生素C同食可促进吸收
}

// 饮食注意
class DietRequirementContent {
    String[] recommendIngredients; // 推荐食材：鸡胸肉、鱼、豆腐、蔬菜
    String[] avoidIngredients;     // 需避免/限制：油炸食品、肥肉、奶油、动物内脏
    String[] cookingTips;          // 烹饪方式建议：建议蒸、煮、炖，避免煎、炸
}
```

### 7.6 展示

三个分区独立成卡片区，每条建议旁标注来源，引用报告原文：

```
来源：过敏原检查–虾蟹类 阳性(+)
来源：总检结论–建议补充铁剂
来源：总检结论–建议低脂低盐饮食
```

来源标注中的原文同样按 `segmentId` 取整段 `rawText`（§3.2.3）。
一条原文拆出多个枚举时（「建议低脂低盐饮食」→ `LOW_FAT` + `LOW_SODIUM`），
两张卡片各自独立展示，来源标注引用**同一段原文**，这不算合并（需求 §7-5 禁止的是合并建议本身）。

**三类来源之间不做交叉关联、不合并**（需求 §7-5）。

### 7.7 空态（需求 §7-4）

| 分区 | 无内容时的文案 |
|---|---|
| 过敏提醒 | 本次体检报告未涉及过敏原相关内容 |
| 营养补充 | 本次体检报告未涉及营养补充相关内容 |
| 饮食注意 | 本次体检报告未涉及饮食注意相关内容 |

**全模块不出现任何提示、说明或警示文字**，只有报告原文、来源标注、已收录枚举的食材内容，
以及上表的空态句和 §7.8 的底部声明（产品决策，§12-3）。

> **已知接受的风险：** 报告压根没做过敏原筛查时，页面与"做了筛查但全阴性"完全一样，
> 都显示「本次体检报告未涉及过敏原相关内容」。系统不会告知用户"推荐结果未考虑过敏因素"，
> 而本版又没有用户自填过敏原的入口——即用户没有任何途径让系统知道他的过敏情况，
> 也不会被提示这一点。此为产品明示决策，兜底仅靠 §7.8 与 §8.11 的模块声明。

### 7.8 底部声明

> 以上建议均基于体检报告原文，不构成医疗或营养处方，具体饮食方案请遵医嘱。

---

## 8. 模块四：食堂菜品推荐（需求 §8）

### 8.1 菜品数据

**菜品与食材数据已同步在本系统库表内，在线组装与离线打标都直接查库**，
不调外部接口，因此没有超时、降级、鉴权穿透这些问题。

```
ct_dish             dish_id（全系统唯一）、dish_name、on_shelf、biz_date
ct_dish_ingredient  dish_id、ingredient_name、weight_g
```

> 这两张是食堂系统的表，本方案只读、不写、不改结构。
> 实际表名与列名以食堂系统为准，接入时核对（§12-13）。

在线组装时按当前用户可见范围查当日在架菜品；主料标记、拼音、配料完整性全部运行时推导，
**菜品库零改造**。

**`dish_id` 全系统唯一已由菜品数据方确认**（2026-08-24），这是 §8.3 缓存 Key 不含租户/食堂维度的前提。

### 8.2 三个维度的判定方式

| 维度 | 枚举数 | 判定方 | 需求依据 |
|---|---|---|---|
| 食入性过敏原 | 11 | **LLM-B 离线打标 + Java 关键词兜底取并集** | §8-2 主料或配料 |
| 营养补充 | 9 | **纯 Java 确定性匹配**，不调模型 | §8-2 菜品主料 |
| 饮食注意 | 9 | **LLM-B 离线打标** | §8-2 符合/违反饮食要求 |
| 吸入性过敏原 | 5 | **不参与** | 非食物 |

**LLM-B 实际打标维度 = 11 + 9 = 20 个**（5 个吸入性不参与、9 个营养补充走 Java）。

### 8.3 离线打标（每日凌晨，xxl-job）

#### 8.3.1 缓存 Key 按维度组织，不按菜

```
tagPolicyVersion = hash(modelVersion + promptVersion + tagRuleVersion)

MySQL ct_dish_tag 唯一键   (dish_id, dish_hash, tag_policy_version, enum_key)      ← 真源
Redis Key               dish:tag:{enumKey}:{tagPolicyVersion}:{bizDate}   Hash
Redis Field             {dishId}:{dishHash}
Redis Value             {verdict, matchedIngredients}
Redis TTL               3 天
```

**Key 的形状必须匹配读取模式。** 在线要的是「这几个生效维度，全部菜分别什么标签」，
不是「这一道菜，20 个维度分别什么标签」：

```
生效维度 = 本次报告命中的食入性过敏原枚举 ∪ 命中的饮食注意枚举
          典型 3~6 个，不是 20 个
在线读取 = 每个生效维度一次 HMGET，一次带上全部 dishId:dishHash
          → 3~6 次 Redis 命令搞定
```

早期设计是一菜一 Key（`dish:tag:{dishId}:{dishHash}:{policyVersion}`），200 道菜就是
200 个 Key，且每次取回 20 个维度而只用其中 6 个。管道化能把 RTT 压下去，
但那是在用管道掩盖结构错配。

**`bizDate` 让 Key 每天重建，不留垃圾字段。** Hash 没有字段级 TTL，
不带日期的话每道菜改一次食材就在 20 个 Hash 里各留一个死字段，一年下来全是垃圾。

**两个版本维度仍然缺一不可：**

| 变的是什么 | 谁失效 |
|---|---|
| 某道菜改了食材 | `dishHash` 变 → Field 变 → **只有它自己**读不到标签 |
| 换模型 / 改提示词 / 改内容常量 | `tagPolicyVersion` 变 → Key 变 → 全部重打 |

**两者回答的是不同的问题：** `dishHash` 问"菜变了吗"，`tagPolicyVersion` 问"规则变了吗"。

**输出 Schema 的版本不参与。** 它只约束返回结构，不改变模型对「这道菜含不含虾」的判断；
加字段属于数据迁移问题，不是标签失效问题。只有真正影响打标结果的三项进 hash。

**bump 的代价很低：** 全量重打 = 200 道菜 × 20 维度 / 40 每批 ≈ 100 次调用，
一个凌晨窗口跑得完。所以这里可以放心保守——宁可多重打一次，
也不要让旧规则的标签留在线上。

只有 `dishHash` 而没有 `tagPolicyVersion` 的话，**改了提示词或内容常量之后菜和食材都没变，
diff 会认为标签已存在，永远不会重算**：

```
营养师往 LOW_FAT 的避免食材里加了「红油」「油炸」，bump tagRuleVersion
凌晨 diff：(水煮牛肉, dishHash=abc, LOW_FAT) 在 ct_dish_tag 里有没有？ 有 → 跳过
        → 这道菜永远不会用新规则重打
```

而且这个 bug 是**静默的**——不报错、不告警、监控指标全绿，只有人工比对才发现。

反过来，用「全部菜品集合」的整体 hash 也不行：任何一道菜改一克，整个版本号就变，
预热完成前推荐模块整个空掉。

#### 8.3.2 `dishHash` 必须规范化后再算

```
dishHash = sha256(
    normalize(dishName) + "|" +
    join(",", 食材列表按 normalize(name) 字典序排序后的 "name:weightG")
)
其中：weightG 统一换算为克并四舍五入到 1 位小数
      name 走 §3.2.2 的规范化
```

不排序、不统一单位、不规范名称的话，**外部查询返回顺序变一下就会触发全量无意义重打标**。

#### 8.3.3 打标任务

```
① 取当日在架菜品全量，逐菜计算 dishHash，取当前 tagPolicyVersion
② diff 出缺失的 (dishId, dishHash, tagPolicyVersion, enumKey) 组合
   —— diff 以 MySQL ct_dish_tag 为准，不看 Redis
③ 分批调用 LLM-B（40 道/批，按 enumKey 分组）
④ Java 校验通过的写入 MySQL ct_dish_tag（真源）
⑤ 按 enumKey 聚合，HSET 写入当日 Redis Key，设 TTL 3 天
⑥ 上报 tag_target_total / tag_written_total，不等即告警
```

**成本：** 200 道菜 × 20 维度 / 40 道每批 ≈ 100 次调用/天；稳态下只补新增和变更，远低于此。
`tagPolicyVersion` 变更会触发一次全量重打，需在发版计划里预留窗口。

#### 8.3.4 在线读取路径

```
① MySQL  当前用户可见的当日在架菜品 + 食材            ~200 菜 / ~1600 行
         （dishHash、主料推导、过敏 Java 兜底都要这份数据，绕不开）
② Java   逐菜算 dishHash                            200 × sha256，<5ms
③ Redis  每个生效维度一次 HMGET，Field 是全部 dishId:dishHash
                                                    3~6 次命令
④ MySQL  ③ 中返回 null 的 Field 回源查 ct_dish_tag       一次 IN 查询
         WHERE tag_policy_version=? AND enum_key IN (...)
           AND (dish_id, dish_hash) IN ((?,?), ...)
         MySQL 8.0.14+ 对行构造器 IN 支持索引区间扫描，走 idx_online；
         更早的版本会退化成全表扫，需确认实际部署版本
⑤ Java   仍然查不到 → TAG_MISSING（§8.9），不是 NEUTRAL
```

**第 ④ 步的回源不能省。** 没有它，预热任务失败或 Redis 被清空的那天，
所有维度全部 `TAG_MISSING`，按 §8.9 的完整性门槛，**推荐列表会整个空掉**。
Redis 在这里只是加速器，MySQL `ct_dish_tag` 才是真源。

#### 8.3.5 持久化

```sql
CREATE TABLE ct_dish_tag (
  dish_id             BIGINT       NOT NULL COMMENT '食堂菜品ID',
  dish_hash           CHAR(64)     NOT NULL COMMENT '菜名与食材的SHA-256内容哈希',
  tag_policy_version  CHAR(64)     NOT NULL COMMENT '打标策略版本哈希',
  enum_key            VARCHAR(32)  NOT NULL COMMENT '过敏原或饮食注意维度枚举键',
  verdict             VARCHAR(12)  NOT NULL COMMENT '打标结论：RECOMMEND/REJECT/NEUTRAL',
  matched_ingredients VARCHAR(512) NULL COMMENT '命中食材名称的JSON数组字符串',
  reason              VARCHAR(256) NULL COMMENT '模型返回的判定理由',
  model_version       VARCHAR(64)  NOT NULL COMMENT 'LLM-B模型版本',
  prompt_version      VARCHAR(32)  NOT NULL COMMENT 'LLM-B提示词版本',
  tag_rule_version     VARCHAR(32)  NOT NULL COMMENT '内容常量版本',
  create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由数据库维护',
  create_by           VARCHAR(50)  NULL COMMENT '创建人标识',
  update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护',
  update_by           VARCHAR(50)  NULL COMMENT '更新人标识',
  UNIQUE KEY uk (dish_id, dish_hash, tag_policy_version, enum_key),
  KEY idx_online (tag_policy_version, enum_key, dish_id, dish_hash),   -- ④ 的回源走这条
  KEY idx_cleanup (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品维度打标结果';
```

三个 `*_version` 列冗余存储（`tagPolicyVersion` 已是它们的 hash），用于复现某天的推荐结果。

**没有单独的 `tagged_at` 列**——打标时间就是行的 `create_time`，
而 `tag_policy_version` 进了唯一键，同一策略下的行不会被覆写重打。

### 8.4 LLM-B 打标契约

输入：一批菜品（菜名 + 食材列表 + 重量）+ 一个枚举的展示名和内容常量。
正式契约为 `schema/llm_b_output.schema.json`。

```jsonc
{
  "enumKey": "LOW_FAT",
  "neutralDishIds": [10001, 10003],     // 已核验、结论为中立的，只回 ID
  "hitList": [                           // 只有命中项携带证据
    { "dishId": 10002, "verdict": "REJECT",
      "evidenceType": "COOKING", "matchedIngredients": [], "reason": "油炸菜品" }
  ]
}
```

**紧凑格式不缩小覆盖范围**：`neutralDishIds ∪ hitList` 必须精确等于本批全部输入 `dishId`，
少一个、多一个、重复一个都判整批作废，不写库、不重试。
**遗漏的菜绝不静默补成 `NEUTRAL`。**

| 维度 | 允许的 verdict |
|---|---|
| 食入性过敏原 | `REJECT` / `NEUTRAL` |
| 饮食注意 | **只有 `REJECT` / `NEUTRAL`**，不产生推荐 |

过敏维度提示词要点：**判断这道菜的实际成分中是否可能含有该过敏原，包括调味料和加工食品里的
隐藏成分**（XO 酱含干贝虾米、沙拉酱含蛋、海鲜酱含虾）。宁可多标，不可漏标。

### 8.5 过敏的 Java 关键词兜底

模型漏标是随机的，而最直白的菜漏标后果最严重。过敏维度**额外**跑一层确定性匹配，
与模型结果**取并集**：

```java
// AllergenKeywords 直接由 AllergenGroup 的 avoidIngredients ∪ hiddenFoods 得出
// 与展示同源，共 11 组 73 词，不另建一张表
// 匹配范围：菜名 + 全部食材名（不只主料）；无重量阈值，微量即命中
任一来源判 REJECT → 该菜在该过敏维度 REJECT，模型不可推翻
```

**已知代价：过杀。**「鱼香肉丝」在鱼过敏时会被误标。按 §0-5 的不对称主动接受。
误杀集中在少数几个词时，加一个不超过 20 条的例外词典（常量数组）。

### 8.6 枚举外过敏原（`OTHER` 且 `isFoodBorne = true`）

**仅对食源性未收录过敏原生效。** `isFoodBorne = false` 的（艾蒿、豚草、真菌等）
不进入本节任何逻辑，也不参与任何菜名/食材匹配。

```
① 用过敏原名称原文对「菜名 + 全部食材名」做字符串匹配
② 命中的菜 → 进不推荐列表，标签展示「{原文名}过敏」
③ 未命中的菜 → 照常参与其他维度，正常出推荐列表
④ 不展示任何提示文字（产品决策，§12-3）
```

> **已知接受的风险：** 字面匹配拦得住「芹菜炒肉」，拦不住复合调味料里的隐藏成分。
> 这部分菜会正常出现在推荐列表中，且页面不作任何说明。兜底仅靠 §8.12 的模块声明。

### 8.7 营养补充：纯 Java 确定性匹配

**这个维度不需要模型。** 需求 §8-2 的规则本身就是确定性的：菜品主料包含该营养素的推荐食材。

```java
Set<String> matched = intersect(
    mainIngredients(dish),                               // §8.8
    NUTRITION_CONTENT.get(enumKey).recommendIngredients   // §7.5 内容常量
);
verdict = matched.isEmpty() ? NEUTRAL : RECOMMEND;
```

**原设计的漏洞：** 只校验了模型返回的 `matchedIngredients ⊆ 主料集`，
**没校验它属不属于该营养素的推荐食材**。模型如果认为「大米补铁」，而大米确实是主料，
Java 校验会原样放行——一道白米饭就成了补铁推荐菜。

改成 Java 交集后：结果完全确定、零模型成本、可单测穷举，且符合 §0-2
「能程序判定的不交给模型」。**LLM-B 少 9 个维度。**

食材名对不上时（「猪肝」vs「鲜猪肝」）用 `IngredientAliasWords` 常量别名表兜一层，
未命中即按不匹配处理（方向保守，只会少推荐）。

**该维度永远不会 `TAG_MISSING`**（§8.9）——Java 现算，不依赖预热。

### 8.8 主料推导

需求 §8-2 规定营养补充只匹配**主料**，过敏匹配**主料或配料**。菜品数据无主料标记，按重量推导：

```java
Set<String> mainIngredients(Dish d) {
    // 1. 排除调味料（SEASONING 常量：油、盐、糖、酱油、醋、葱、姜、蒜、料酒、淀粉...）
    // 2. 排除无重量数据的食材；全部无重量 → 返回空集（该菜营养维度全 NEUTRAL）
    // 3. total = 剩余食材重量之和
    // 规则一：重量占比 >= 25% 的，无论名次
    // 规则二：重量前 2 名，且占比 >= 15%
    // 两条取并集；都不满足则取最重的一个
}
```

| 菜品 | 数据 | 仅 ≥25% | 仅前2名 | 双规则 |
|---|---|---|---|---|
| 青椒肉丝（补蛋白） | 青椒200 肉丝80 → 28.6% | ✅ | ✅ | 主料 ✓ |
| 番茄炒蛋（补蛋白） | 番茄250 鸡蛋100 → 28.5% | ✅ | ✅ | 主料 ✓ |
| 青菜猪肝（补铁） | 青菜180 猪肝5 → 2.7% | ❌ 正确 | ⚠️ 误判 | 非主料 ✓ |

`MAIN_RATIO = 0.25` 与 `TOP_N_MIN_RATIO = 0.15` 是推导值，**上线前抽 50 道真实菜品校准**。
误判率偏高时应推动食堂系统补主料字段，而不是继续调阈值——业务数据永远比推导规则可靠。

### 8.9 标签缺失 ≠ 中立（§8 评审）

**这是本轮评审发现的最严重的一处错误。** 原设计写"漏标只导致少推荐，方向安全"，
在单维度下成立，**多维度下不成立**：

```
报告同时有「补充蛋白质」和「低脂饮食」两条建议
某道油炸肉菜：
   PROTEIN  维度 → Java 现算，肉是主料 → RECOMMEND
   LOW_FAT  维度 → 标签缺失（预热窗口后新上架）→ 原设计按 NEUTRAL 处理
   合并裁决 → 有 RECOMMEND 无 REJECT → 【推荐】
→ 一道油炸菜被推荐给需要低脂饮食的人
```

过敏维度同理，而且后果更严重。

**因此引入四态，`TAG_MISSING` 与 `NEUTRAL` 必须分开：**

```java
enum TagState { TAG_MISSING, NEUTRAL, RECOMMEND, REJECT }
```

**完整性门槛：** 对本次报告实际生效的每个**可产生 REJECT 的维度**
（全部食入性过敏原维度 + 全部饮食注意维度），该菜只要有任意一个维度是 `TAG_MISSING`，
**这道菜就不能进入推荐列表**。

```
可产生 REJECT 的维度 = 生效的食入性过敏原枚举 ∪ 生效的饮食注意枚举
营养补充维度不在此列（Java 现算，永不缺失）

该菜在上述任一维度 TAG_MISSING  →  不进推荐列表，也不进不推荐列表（不展示）
```

**为什么不放进不推荐列表：** 我们并不知道它违规，只是没核验过；
放进不推荐列表是对菜品的错误指控，也会误导用户以为这道菜有问题。不展示是唯一诚实的选择。

预热窗口之后新上架的菜品**当天不出现在推荐列表里**，这是有意的。

### 8.10 合并裁决

```java
// ① 逐条校验（不通过就降级，不整批丢弃）
//    - matchedIngredients ⊆ 该菜食材表，对不上的剔除；全对不上 → 降 NEUTRAL
//    - 本批未被模型返回的菜 → TAG_MISSING（★ 不是 NEUTRAL，见 §8.9）
//    - 营养维度不走此路径，由 §8.7 直接产出

// ② 裁决（需求 §8-3：冲突以不推荐优先）
if (任一过敏维度 REJECT)     return NOT_RECOMMENDED;   // 只带过敏标签
if (任一维度 REJECT)         return NOT_RECOMMENDED;   // 推荐标签作灰色附注
if (任一可 REJECT 维度 TAG_MISSING) return HIDDEN;     // ★ 不进任何列表（§8.9）
if (任一维度 RECOMMEND)      return RECOMMENDED;
return NEUTRAL;                                        // 不进任何列表
```

**过敏拒绝的菜不下发任何正面标签**，灰色附注也不行——「补铁 · 虾蟹过敏」并列展示会削弱过敏提示。
这与需求 §8-3「展示所有命中的标签」不一致，已列为产品决策（§12-12）。

### 8.11 标签与推荐理由

| tagType | 来源 | 示例 | 建议色 |
|---|---|---|---|
| `NUTRITION` | 营养补充 RECOMMEND | 补铁、高蛋白、补钙 | 绿 |
| ~~`DIET_OK`~~ | ~~饮食注意 RECOMMEND~~ | **当前不会产生**：饮食注意维度只判 `REJECT`。保留枚举值供将来使用，前端不需要实现 | 蓝 |
| `ALLERGY` | 过敏命中 | 虾蟹过敏、芹菜过敏 | 红 |
| `DIET_AVOID` | 饮食注意 REJECT | 高脂、高盐、高糖 | 橙 |

**推荐理由由 Java 拼接，不加工报告原文：**

```
{菜名}——含{matchedIngredients 逗号连接}；报告原文：「{segment 原文}」

例：菠菜猪肝汤——含猪肝、菠菜；报告原文：「建议补充铁剂」
```

原模板 `符合报告建议{rawText}` 会拼出「符合报告建议**建议**补充铁剂」——
原文本身常以「建议」开头。改成引号引用原文，既不重复也更符合需求 §8-4「引用对应饮食建议原文」。

一道菜命中多条时，按维度逐条列出全部理由。

### 8.12 排序、空态与降级

两个列表都按**菜名拼音首字母排序**（TinyPinyin，请求时实时计算，不落库），
非汉字开头的菜名统一排在汉字之后，**排序后各取前 3 道**。

```
recommendList 与 rejectList 最大长度均为 3
截断必须在全部维度合并裁决之后 —— 先取 3 再判过敏，会把该拦的菜留下、该推的菜丢掉
不足 3 道按实际返回，不用占位菜补满
```

| 情况 | 处理 |
|---|---|
| 饮食建议中无任何正式枚举内容 | 「本食堂菜品暂无个性化推荐。」 |
| 有饮食建议但两个列表都为空 | 「本次未匹配到符合建议的食堂菜品，菜品以食堂实际上架为准。」 |
| `suppressDishRecommend = true`（§4.1.1） | **整个模块不输出** |

### 8.13 底部声明

> 推荐菜品基于体检报告内容及食堂菜品数据自动匹配，菜品信息以食堂实际上架为准。

---

## 9. 存储与接口

### 9.1 持久化清单

| 存储 | 内容 | 生命周期 |
|---|---|---|
| MySQL `ct_health_report_task` | 任务状态真源、attempt、心跳、deadline、partial、deleted_at（DDL §2.3.2） | `expire_at` 到期物理删除 |
| MySQL `ct_health_report_file` | fileId、userId、S3 定位、taskId、fileIndex、文件元数据、status、expire_at | 按 §2.7 清理矩阵 |
| MySQL `ct_dish_tag` | 菜品标签真源 + 版本元数据（DDL §8.3.5） | 按 `create_time` 清理陈旧行 |
| MySQL `ct_dish` / `ct_dish_ingredient` | 食堂菜品与食材（外部同步，本方案只读） | 外部维护 |
| S3 私有 Bucket | 原始文件 | 按 §2.7 清理矩阵 |
| Redis `result:{taskId}` | 四模块结果 JSON | TTL 2h |
| Redis `dish:tag:{enumKey}:{tagPolicyVersion}:{bizDate}` | 打标读缓存，Field = `dishId:dishHash`（§8.3.1） | TTL 3d |
| Redis `q:analysis` | 任务队列 | — |

**没有 `task:{taskId}` 状态 Hash，也没有 Redis 墓碑，也没有 outbox 表。**
任务状态与删除标志都在 MySQL——状态 CAS 要与文件绑定同事务，Redis 做不到；
`deleted_at` 也不能随 TTL 消失（§2.6）。入队由 §2.3.3 的事务内 XADD 完成。

**姓名、报告原文、OCR 文本不进 MySQL**，只在 Redis 结果里存 2 小时。
`ct_health_report_task` 不含任何健康数据。

**`ct_health_report_file` 行必须整行删除，不是只删 S3 对象。**
原文件名、文件大小、`cloud_file_key`、内容 hash 都是可定位报告的信息，
需求 §3-10 的口径是「关闭后清除所有数据」，留着元数据不符合该口径。

#### 9.1.1 建表约定（项目统一规范）

本方案新建的三张表全部遵守，接入时不得例外。

**① 表名一律 `ct_` 前缀。**

```
ct_health_report_task    ct_health_report_file    ct_dish_tag
```

**② 四个审计列每张表都有，列名和类型固定。**

```sql
create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由数据库维护',
create_by    VARCHAR(50)  NULL COMMENT '创建人标识',
update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，由数据库维护',
update_by    VARCHAR(50)  NULL COMMENT '更新人标识'
```

**③ `create_time` 与 `update_time` 由数据库维护，代码永远不赋值。**

插入时不写这两列，更新时也不写——`DEFAULT CURRENT_TIMESTAMP` 负责前者，
`ON UPDATE CURRENT_TIMESTAMP` 负责后者。

```java
// 错误
task.setCreateTime(new Date());
task.setUpdateTime(new Date());

// 正确：这两个字段在 insert/update 语句里根本不出现
```

> 这条对本方案有一个具体后果：§2.3.4 的 CAS、§2.5 的状态更新、§2.6 的删除标志，
> 这些 `UPDATE` 语句都**不要**带 `update_time = now()`。
> MyBatis-Plus 若配置了 `@TableField(fill = FieldFill.INSERT_UPDATE)` 自动填充，
> 需对这两列关闭，否则会覆盖数据库默认值、绕开约定。

**④ `create_by` / `update_by` 由业务代码赋值。**

| 表 | 取值 |
|---|---|
| `ct_health_report_task` | 当前 `userId` |
| `ct_health_report_file` | 当前 `userId` |
| `ct_dish_tag` | 固定标识，如 `DISH_TAG_JOB`（离线任务写入，无用户上下文） |

> 这两列存的是操作者标识，不是健康数据，不违反 §9.1 的"任务表不含健康数据"。

**⑤ 字符集与排序规则（MySQL 8.0）。**

| 列类型 | 字符集 / 排序规则 | 理由 |
|---|---|---|
| 建表默认 | `utf8mb4` | 报告文本含部首区字符（§3.2.2）与生僻 CJK，`utf8mb3` 不够用 |
| 哈希、ID、枚举列<br>（`dish_hash` / `tag_policy_version` / `enum_key` / `task_id` / `file_id`） | `ascii_bin` 或 `utf8mb4_bin` | 这些列参与唯一键和等值查找，需要**精确二进制比较**。MySQL 8 的默认 `utf8mb4_0900_ai_ci` 大小写与重音不敏感，用在哈希列上是隐患 |

`ct_dish` / `ct_dish_ingredient` 是食堂系统的表，本方案只读，其结构以对方为准。

**⑥ 每个字段和每张表都必须有中文 `COMMENT`。**

- 字段使用 MySQL 字段级 `COMMENT '中文业务含义'`；
- 枚举/状态列在中文说明后列出允许值或指向定义；
- 表使用 `COMMENT='中文表用途'`；
- 行尾 `--` 备注、Java 注释、字段名自说明和另外的数据字典均不能代替字段 `COMMENT`；
- 本约束同时适用于新建表、`ALTER TABLE ADD COLUMN`、迁移脚本和文档中的 DDL 示例。

### 9.2 接口清单

| 接口 | 说明 |
|---|---|
| `POST /api/health-report/file` | 单文件上传，返回 fileId |
| `POST /api/health-report/analyze` | 提交有序 fileIds，创建并返回 taskId。**「重新解析」复用本接口**（§2.5） |
| `GET  /api/health-report/task/{taskId}` | 轮询状态与进度，失败时返回 `failCode` 与 `reanalyzable` |
| `GET  /api/health-report/result/{taskId}` | 取四模块结果，含 `partial` / `suppressDietAdvice` / `suppressDishRecommend` |
| `DELETE /api/health-report/task/{taskId}` | 用户关闭页面时清除（§2.6） |

**除上传接口外，全部接口必须校验 `taskId` 归属当前 `userId` 且 `deleted_at IS NULL`。**
taskId 难猜是混淆，不是鉴权。**上传接口虽无 taskId，但创建任务时必须校验文件归属**（§2.2）。

### 9.3 轮询

前端递增间隔轮询：`2s → 3s → 5s → 5s...`，上限 5 分钟。
Worker deadline 10 分钟，**客户端超时不写终态**，服务端独立判定（§2.3.4）。
该值以 §4.1.4 的并发执行为前提；批次若串行，30 页报告的耗时上界会超过它。

---

## 10. 实施优先级

### P0 — 安全

1. LLM-A 输出 Schema 强制必填 + **高风险内容交叉扫描与安全降级**（§4.4-①②）
2. **过敏原 `resultStatus` 准入过滤，阴性绝不进入**（§4.4-④）
3. **过敏原回切失败 → `ALLERGEN_SUSPECT_MISS` 降级，不得静默丢弃**（§4.4-⑤）
4. **批次 `UNREADABLE` → `BATCH_UNREADABLE` 降级，模块三四不输出**（§4.1.1）
5. **超 30 页 → `PAGE_TRUNCATED` 降级，模块三四不输出**（§3.3.2）
6. **`TAG_MISSING` ≠ `NEUTRAL`，可 REJECT 维度缺标签的菜不进推荐列表**（§8.9）
7. 过敏原按 `isFoodBorne` 拆两条路径 + 词表反向校验（§4.4-④、§7.2）
8. 高危表述强制转 `OTHER` 的 Java 黑名单（§7.3）
9. 过敏 Java 关键词兜底 + 与模型取并集（§8.5）
10. 枚举外过敏原名称匹配，仅对 `isFoodBorne = true` 生效（§8.6）
11. 过敏拒绝优先于一切推荐判定（§8.10）
12. 多文件同一性校验，不一致直接失败（§4.5）
13. 创建任务的文件所有权校验 + 原子绑定（§2.2）
14. **`deleted_at` 标志 + Worker 写回前带条件**（§2.6）
15. **`DietAdviceContent` 全量内容、过敏关键词族、高危黑名单、饮食注意规则的营养师/医务审核**
    ——**未审核通过，模块三与模块四不得上线**。审核需留档审核人与内容版本号。

### P0 — 正确性

16. **Segment 机制 + `rawText`/`normalizedText` 分离 + 按 `textSource` 分档的包含性校验**（§3.2）
17. `batchStatus` 三态，`NO_REPORT_FEATURE` 与 `UNREADABLE` 分开（§4.1.1）
18. 健康问题准入：`includeInHealthProblems` + Java 反向兜底（§4.4-⑥、§6.1）
19. `INDICATOR_TEXTUAL` 不带 `indicatorId`（§6.1）
20. 准入三分法（§4.3-2）
21. **阴性与阳性同走模型判断路径，不进正常词表**（§4.3-6）
22. **营养维度改 Java 确定性交集匹配**（§8.7）
23. 主料双规则推导（§8.8）
24. **`tagPolicyVersion` 进唯一键与缓存 Key；Key 按维度组织 + `bizDate` 每日重建**（§8.3.1）
25. **`dishHash` 排序 + 单位统一 + 名称规范化**（§8.3.2）
26. **去重只认 `segmentId + itemIndex`，非重叠页面不去重**（§4.1.3）
27. 多文件合并用 `groupKey`，健康指标按 `groupKey` 分组（§4.6、§5.3）
28. **一条原文可拆多个枚举条目，共享同一 segment**（§4.2、§4.3-4）
29. `summaryConclusions.categories` 数组 + `sourceOrder` 排序（§4.2、§6.3）
30. `textualFindings.orderInSection`（§4.2、§6.3）

### P0 — 工程

31. **状态机纯单向无回边，删除用 `deleted_at` 正交标志**（§2.3.1）
32. **`ct_health_report_task` DDL，状态 CAS 落 MySQL**（§2.3.2）
33. **XADD 在创建事务内、提交之前；失败即回滚**（§2.3.3）
34. **全案零重试**：无执行重试、无投递重投（§2.3.3、§2.5、§4.1、§4.4）
34a. **`FILE_ALREADY_BOUND` 返回已绑定的 taskId**，兜住响应丢包（§2.2）
34b. **LLM-A 批次并发执行**（串行跑不完 deadline）+ **只持有编码字节不持有 `BufferedImage`** + **心跳独立线程**（§4.1.4）
35. **「重新解析」= 同批 fileIds 重调 analyze，可从可重解析的失败任务解绑**（§2.2、§2.5）
36. **清理矩阵按状态逐类判定，原文件在 `SUCCEEDED` 后才删**（§2.7）
37. **`ct_dish_tag` MySQL 真源；Redis 未命中必须回源查库**，否则预热失败当天推荐列表全空（§8.3.4）
38. 逐格式判定 + 解压炸弹防御，**流式计数不信 `getSize()`**（§3.1、§3.1.1）
39. **Word 独立分块规则 + 内嵌图片 OCR**（§3.3.1）
40. **正式 JSON Schema 文件（LLM-A / LLM-B）+ 契约测试**（§4.2、§8.4）

### P0 — 需求符合性

41. 四个模块底部声明 + 全部空态文案
42. 总览条数字（§5.4）
43. 来源标注取报告原文章节名（§6.2）
44. 推荐理由 Java 拼接，引号引用原文（§8.11）
45. 拼音首字母排序（§8.12）
46. 三阶段进度条（§2.4）

### P1

47. 打标计数与召回率告警
48. 部首映射表持续补齐
49. 食材别名表扩充
50. OCR bounding box 落库（为原图高亮预留）

### P2

51. 过敏误杀例外词典（≤20 条）
52. 菜品拼音人工修正
53. OFD 兼容性扩展

### 明确不做

- 用户自填过敏原
- 由指标异常推导饮食建议（需求 §7-5 禁止）
- 「体检报告不完整」的失败判定（§2.5、§12-2）
- 严重程度分级 / 风险排序（需求 §6-4 禁止）
- 历史报告回看（结果 TTL 2 小时）
- 原图跳转与单页预览
- 内容管理后台 / 建议内容的人工编辑
- 报告未写饮食建议时的通用建议兜底（§7.1）
- 菜品标签的版本双缓冲与原子切换（单菜粒度后不需要，§8.3.1）

---

## 11. 上线前必须验证的假设

本文档所有阈值均为推导值，未经真实数据验证。

| # | 假设 | 验证方式 | 不成立的后果 |
|---|---|---|---|
| 1 | 文本层判据（50字符/页、30%非空白） | 20 份不同机构 PDF 抽样 | 电子版误走 OCR，或扫描件误当电子版 |
| 2 | 主料双规则阈值 0.25 / 0.15 | 50 道真实菜品人工标注对比 | 营养推荐名不副实，或该推的推不出来 |
| 3 | 34 个枚举的覆盖率 | 20 份真实报告统计 `OTHER` 占比 | 模块三大面积只展示原文 |
| 4 | 过敏原枚举覆盖真实筛查面板 | 收集 5 家机构的过敏原检查项目清单 | §8.6 的兜底分支频繁触发 |
| 5 | OFD 解析可行性 | 3~5 家真实样本跑通 | 该格式降级或砍掉 |
| 6 | LLM-B 打标稳定性 | 同一批菜连跑 5 次比对 verdict 一致率 | 打标结果每天跳变 |
| 7 | **Segment 切分的稳定性与粒度** | 20 份报告统计：一个 segment 平均含几个指标、表格单元格切分成功率 | 包含性校验失效（粒度太粗则形同虚设，太细则回切不到完整原文） |
| 8 | **Word 等效页折算系数（40 segment/页）** | 5 份医院导出 Word 报告，比对实际渲染页数 | 容量限制与分批策略失准 |
| 9 | **Word 内嵌图片形态占比** | 同上样本统计"扫描件贴进 Word"的比例 | 若占多数，Word 路径实质上是 OCR 路径，成本与耗时需重估 |
| 10 | 抽取召回率评测集 | 20~30 份真实报告人工标注过敏原、饮食医嘱、异常结论，统计 LLM-A 漏抽率 | 无法判断疑似漏抽告警的阈值，也无法证明模型没在静默漏抽 |
| 11 | 30 页以上报告的实际占比 | 样本统计 | 降级路径触发频率未知，可能远超预期 |
| 12 | **`ALLERGEN_SUSPECT_MISS` 的误报率** | 用评测集跑，统计有多少正常报告被误降级 | 误报过高则模块四大面积不输出。**去掉定向重试后该值会上升** |
| 13 | **LLM-A 调用的瞬时失败率**（超时 / 429 / 5xx） | 压测 + 灰度期观测 | 全案不做执行重试，瞬时抖动直接变成用户可见失败。失败率高于 2% 就要重新讨论是否放开批次级重试 |
| 14 | **创建任务时 XADD 的失败率** | 灰度期观测 `analyze` 接口的 `SERVER_ERROR` 占比 | 无投递重投，Redis 抖动会让用户白点一次「生成体检报告」。占比明显时需重新评估是否恢复 outbox |
| 15 | **模型服务的并发配额 `C`** | 向服务方确认，并压测验证 | §4.1.4 的 `W = floor(C/4)` 定不下来。全案零重试，一个 429 就是一次用户可见失败，`W` 设大了会直接反映成失败率 |
| 16 | **OCR 单页耗时与 LLM-A 单批耗时** | 用真实样本实测 | §4.1.4 的 deadline 测算基于 2~3s/页 与 60~180s/批，都是推演值。实际更慢的话 10 分钟 deadline 要重新定 |

### 11.1 敏感数据链路的技术核查

体检报告、过敏原和医生建议是敏感健康信息。§2.7 声称"原文件在任务成功后删除"，
但本方案只管得了自己这一侧。上线前需逐项核查并留档：

```
□ OCR 服务是否第三方？请求图片留存多久？
□ Dify 工作流日志是否默认保存输入输出？能否关闭？
□ 模型网关 / APM / 异常追踪（Sentry 等）是否记录请求体？
□ 传输与对象存储是否加密？
□ 临时文件、崩溃转储、队列重试消息是否含报告内容？
□ 第三方服务的数据留存周期与删除机制？
□ 是否存在跨境传输？
□ 运维人员能否直接读取 Redis 中的报告原文？
```

后端评审建议将此列为上线阻断项而非普通假设验证。

---

## 12. 待产品确认

| # | 事项 |
|---|---|
| 1 | **「异常」（橙）状态标签 + 状态由模型判定**——需求 §5-3 只定义三种。判定「阳性/阴性是否属于异常」需要理解指标的医学含义，超出需求 §5-1「不额外推断、不二次分析」的边界，且直接影响总览条异常占比（§5.2、§5.4） |
| 2 | **请从需求 §3-9 去掉「体检报告不完整（缺页等）」**——模型无法判断用户少传了什么。评审建议改为系统可判定的场景：PDF/OFD 声明页数与实际可读页数不一致、Word 结构损坏、图片无法解码。V1 均不实现（§2.5） |
| 3 | **全案不出任何提示、说明、警示文字**——四个模块只展示内容，兜底全靠需求规定的四条底部声明。已确认接受的连带后果：<br>① 报告未做过敏原筛查时用户不会被告知，且本版无自填入口（§7.7）<br>② `OTHER` 建议（含低蛋白、限碘等高危项）不会说明其未纳入菜品推荐（§7.4）<br>③ 枚举外过敏原的隐藏成分风险不作说明（§8.6）<br>④ 吸入性过敏原卡片只有名称和来源，不解释为何无食材清单（§7.2） |
| 4 | **累计 60MB 上限**——需求只规定单文件限制和最多 5 个文件，按需求理论上限是 100MB。60MB 指**上传体积**。需回写需求并给出固定错误提示（§2.2） |
| 5 | **三种降级的用户可见形态**——`PAGE_TRUNCATED` / `BATCH_UNREADABLE` 两类不出模块三四，`ALLERGEN_SUSPECT_MISS` 不出模块四。用户会看到"页面少了两块"，需确认是静默隐藏还是给文案；但 §12-3 已定"不出任何提示文字"，两者需一并裁决（§4.1.1） |
| 6 | **饮食要求来源 = 总检结论 + 医生建议章节**——比需求 §7-2「仅总检结论」略宽，排除各科小结、检查须知、科普段落、检查前准备。需回写需求（§4.3-5） |
| 7 | **弱阳性 / 可疑 / 临界过敏结果按阳性处理**，进入菜品拦截。按 §0-5 安全不对称从严。需医务确认是否过于保守（§4.4-④） |
| 8 | **一次只能分析一个人的报告，不支持代家人分析**——需求未规定，属新增限制，需回写需求。已知该校验是"发现冲突则拒绝"的弱校验，拦不住同名不同人和双方都识别不出姓名的情况（§4.5） |
| 9 | **总览条用报告自带数字还是本模块计算值**——后端评审建议一律用本模块计算值，理由是报告的总项目数含大量不展示的指标，会导致数字与卡片对不上。产品当前决策为不处理该不一致（§5.4） |
| 10 | **健康问题名称允许系统拼接**——「甘油三酯 2.8 ↑」拼成「甘油三酯偏高」。需求 §6-2 要求直接引用原文不做改写。已加 `displayNameGenerated` 字段供验收统计（§6.2） |
| 11 | **`OTHER` 建议只展示原文，不给食材内容**——不满足需求 §7-3 对每条建议的字段要求。需回写需求（§7.4） |
| 12 | **过敏命中时不下发任何正面标签**——与需求 §8-3「展示所有命中的标签」不一致。安全考虑，需作为正式产品决策回写需求（§8.10） |
| 14 | **预热窗口后新上架的菜当天不出现在推荐列表**——§8.9 完整性门槛的必然结果。若食堂当天临时加菜频繁，需评估影响面 |

> **所有"产品已确认"但与原需求不同的决策，都必须同步修改《体检报告分析需求.md》或形成
> 可追溯的评审记录。** 否则需求文档和设计方案会同时成为有效依据，开发、测试和验收无法确定最终口径。
