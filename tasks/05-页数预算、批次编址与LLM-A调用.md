# 开发任务 05：页数预算、批次编址与 LLM-A 调用

> 判据优先级：`AGENTS.md` > 本任务文件 > `AI体检报告分析-开发方案V1.md` > `AI体检报告分析-精简设计方案V1.md` > `体检报告分析需求.md`
> 与更高优先级的安全、持久化、公开接口或医疗规则冲突时停止为 BLOCKED，不得自行选规则。

> 覆盖开发方案 §5.4 / §5.5 / §6.1 / §6.2.0 / §6.2.1.1 / §6.2.2 / §6.2.4。

## 1. 目标

落地三档页数规则与零 segment 裁决、批内块号编址、LLM-A 直连模型客户端、分批并发与批次裁决。
**§6.2.1.1 这段是全案最敏感的一次出网**：请求体含报告图像与 OCR 文本，响应体含健康结论。

## 2. 范围

- 要修改的模块：`parse`、`llm/a`、`infra`
- 允许修改的接口：新增 `PageBudgetService`、`ParseOrchestrator`、`BatchAddressing`、
  `LlmAModelClient` 及实现（**不是占位符**）、`BatchPlanner`、`LlmABatchExecutor`
- 允许修改的数据库表/字段：`partial` / `partial_reason`
- 允许修改的 Prompt/Schema/常量：新增 `PromptVersions` 常量类

## 3. 功能要求

### A. 页数预算、零 segment 裁决与批次编址（原 14）

1. **三档页数**（§5.4，只适用 PDF/OFD/图片，Word 走任务 04 的「超限即拒绝」）：
   ≤30 全处理；**31~60 只处理前 30 等效页** + `PAGE_TRUNCATED`（模块三四不输出）；>60 创建时已拒。
2. 截断按 `fileIndex` 升序、文件内按页序累计到第 30 等效页，**不是按文件整份丢弃**
   ——第 3 个文件的前半截该处理就处理。输入用 `precheck_pages`，**工作线程不重算**。
3. `processedPages` / `totalPages` 必须落进结果并下发。
4. **零 segment 裁决在分批之前**（`ParseOrchestrator`，§6.2.0）：
   全部为空 → `FAILED / UNREADABLE`、**LLM-A 调用 0 次**（不是 `NOT_HEALTH_REPORT`）；
   **部分文件为空 → `BATCH_UNREADABLE` 降级，不静默忽略**（读不出的那份恰好可能含过敏原和医嘱）。
5. `BatchAddressing`（§5.5）：每页一个页眉（**真实页码**）+ 每块 `[n]` 批内块号（0 起连续，按 `seq` 升序）；
   **渲染顺序是契约的一部分**；Java 持有 `blockRef → segmentId` 映射表。
   每行含 `(textSource, bbox=x,y,w,h)`——**bbox 必须逐块给**，只给页面图模型判不出同一行。

### B. LLM-A 直连模型客户端（原 15）

6. 按 §6.2.1.1 **照抄实现**：`OpenAiCompatibleLlmAModelClient`、`StatusOnlyErrorHandler`、
   `CappedByteArrayOutputStream`、`BoundedResponseExtractor`、`RequestTooLargeException`、
   `ResponseTooLargeException`、`LlmAProperties`、`LlmCallException`。
7. **请求体保持缓冲**（`setBufferRequestBody(true)`，显式写出防误改），带 `Content-Length`，**不用 chunked**。
8. **`StatusOnlyErrorHandler` 必须替换默认实现**：`DefaultResponseErrorHandler` 会把 4xx/5xx
   的**完整 body 读进内存**再塞进异常，绕过响应上限。本实现**不调 `response.getBody()`**。
9. **base64 之前先判**：`estimateBodyBytes` 返回**未截断**的值，超限直接抛
   ——一张 1MB 的图 base64 后是 2.8MB 堆，等写进流里再判就晚了。
10. `CappedByteArrayOutputStream` **不继承 `ByteArrayOutputStream`**（它的扩容翻倍且私有不可控），
    自管数组，扩容目标 `min(max(需要, 当前×2), maxBytes)`，**底层数组永不超上限**。
11. **批次信息必须真的发出去**：user content 第一项写 `fileIndex` / `batchIndex` / `batchCount` /
    `promptVersion`——模型要原样回填，不发就等于让它照抄提示词占位符。
12. **图片按页交替内联**：文本 → 该页图 → 下一页文本 → 下一页图；文本与图同属一个 `BatchPage`。
    发送前断言：页列表非空、页码**严格递增**、`imageRequired && jpegBytes == null` 即抛。
13. **绝不实现重试**——全案零重试，超时/429/5xx 直接抛。
14. **脱敏**：`RestClientResponseException` **不得直接进日志**（持有响应正文）；
    `extractContent` 的 Jackson 解析异常**就地脱敏**，只记异常类型名与响应长度，**不传 `e`**。
15. `PromptVersions.EXTRACTION = "extraction-2.3.1"` / `LLM_B = "dishtag-2.2.1"`，**两个独立常量**；
    `modelVersion` 走配置 `llm.model-version-a`，不做常量类。
16. 启动自检：`baseUrl` / `model` / `apiKey` 任一为空 → **直接启动失败**。
17. 提示词打包（§6.2.2）：`prompt/extraction.md` 需进 JAR，二选一（Maven copy-resources 或移进
    `src/main/resources`），**并加启动自检读一次，读不到直接启动失败**。

### C. 分批、并发与批次裁决（原 16）

18. 分批：**一个批次的全部页必须来自同一个文件**；每任务调用次数 = `Σ ceil(各文件等效页数/8)`，上限 8 批；
    单任务内批次并发度 4，**跑在 `llmBatchExecutor` 上**（不是任务池）。
19. **任一批调用失败 → 整任务立即 `FAILED / SERVER_ERROR`**；**不做批次级重试**。
20. **任一批失败时不取消其余批次**，让它们跑完再丢弃——取消传播换来的只是几秒模型调用成本。
21. 内存：渲染完立即编码并释放 `BufferedImage`；**分析与 Web 层共用一个堆**，一次 OOM 连 Tomcat 一起带走。
22. `batchStatus` **三态**：`OK` / `NO_REPORT_FEATURE`（确定没有）/ `UNREADABLE`（不知道有没有）。
    **两者不可互换**。
23. 文件级与任务级裁决（§6.2.0）+ 降级矩阵三行，抑制标志写进 `DegradeAccumulator`。

## 4. 验收条件

- [ ] 正常场景：30 页以内全部处理；22 页报告分 3 批，批次不跨文件；
      WireMock 收到符合 OpenAI 兼容格式的请求，能取回 content
- [ ] 边界场景：**R43b9** —— 恰好 30 等效页不截断，`partial=false`
- [ ] 边界场景：**R43a** —— `totalPages=45` 时只处理前 30，`processedPages=30`、`PAGE_TRUNCATED`、模块三四不输出
- [ ] 边界场景：**R43c** —— 截断落在第 3 个文件中间时，该文件前半截照常处理
- [ ] 边界场景：部分文件零 segment → `partial=true`、`BATCH_UNREADABLE`、其余文件照常
- [ ] 边界场景：**R58** —— 请求体**不含** `taskId`/`userId`/`origin_name`/`segmentId`
- [ ] 边界场景：**R60/R61** —— 消息严格「文本→图→文本→图」；乱序/缺图/重复页**在组装前失败**
- [ ] 边界场景：**R65** —— 静态检查禁 `HttpEntity<String>` / `writeValueAsString` / 整请求体 `StringBuilder`
- [ ] 边界场景：**R65a** —— 超 `maxRequestBodyBytes` 时**发送前**抛，WireMock 收不到请求
- [ ] 边界场景：**R44** —— 一批 `UNREADABLE`、其余正常 → 该批丢弃、`BATCH_UNREADABLE`、模块三四不输出
- [ ] 边界场景：**R45** —— 全部 `NO_REPORT_FEATURE` → `NOT_HEALTH_REPORT`（**不是 `UNREADABLE`**）
- [ ] 失败场景：**R43b10** —— 图片型 Word OCR 后零文字块 → `FAILED/UNREADABLE`、LLM-A 调用 0 次
- [ ] 失败场景：**R43b11** —— 纯图片/扫描 PDF OCR **成功但结果为空** → 同上；OCR **调用失败**则是 `SERVER_ERROR`，两者不得混淆
- [ ] 失败场景：**R62** —— 429/500/读超时各自**调用次数恰好为 1**
- [ ] 失败场景：**R65b** —— 超大响应体 200 与 500 **各一次**，都不完整读进内存；500 走 `StatusOnlyErrorHandler`
- [ ] 失败场景：**R65c** —— 200 + 畸形 JSON（含敏感串）→ 捕获全部日志断言**不含该串**
- [ ] 失败场景：任一批失败 → 整任务 `SERVER_ERROR`，**其余批次跑完但结果被丢弃**
- [ ] 幂等/并发场景：4 批并发乱序返回，合并结果与顺序无关
- [ ] 安全与日志：**R63/R64** —— 日志无 body；wire logging 与 debug 关闭；无打印 body 的拦截器；
      只记 `batchIndex`/耗时/`batchStatus`；渲染文本不进日志
- [ ] 安全与日志：**R57** —— ArchUnit 断言 LLM-A 链路**不依赖 `DifyClient`**
- [ ] 安全与日志：**R55a/R55b** —— 版本号与 `prompt/versions.tsv` 四条断言
- [ ] Java 8 全量构建通过：是

## 5. 不做什么

- 本任务不包含：Schema 校验、来源校验、安全扫描（任务 06）；真实端到端联调（需 ⛔ 三项）；
  R65p（性能测试，§11.5）
- 不允许新增：工作线程重算 `precheck_pages` 的逻辑；任何重试（含批次级）；取消传播；
  任何把 body 写进日志的路径
- 不允许改变的既有行为：两个线程池分离；零重试口径

## 6. 外部依赖与已确认决定

- 外部接口/凭证/测试数据：**全部用 WireMock**（真实 HTTP over localhost，`test` scope）。
  **不得用 `MockRestServiceServer`** —— 它绕过 `ClientHttpRequestFactory`，而 R64/R65 要验的就是那一层。
- **⛔ 只阻塞端到端联调与上线**：`base-url` / `model` / `apiKey`（§6.2.4）。
  **本任务不因此 BLOCKED**，用配置占位 + WireMock 完成全部验收。
- 产品已确认决定：`MAX_SEGMENTS_PER_PAGE=400`、Word 40 分块/页 均为**待校准值**（§11-7b、§11-8）；
  8 批上限、4 并发、页/批 是一组参数，不得单独调
- 医疗审核结论：不适用

## 7. 交付物

- 代码：`PageBudgetService`、`ParseOrchestrator`、`BatchAddressing`、
  §6.2.1.1 的八个类 + `PromptVersions` + 启动自检 + 提示词打包、`BatchPlanner`、`LlmABatchExecutor`
- 测试：R43a、R43b9~R43b11、R43c、R44、R45、R55a、R55b、R57、R58、R60~R65、R65a~R65c
  + 部分文件零 segment + 并发乱序 + 失败不取消
- DDL/Prompt/Schema/文档同步：`prompt/versions.tsv`
