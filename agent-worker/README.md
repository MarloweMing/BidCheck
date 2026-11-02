# BidCheck 受控证据查证 Worker

这个进程使用 **OpenAI Agents SDK `0.24.0`** 的真实 `Agent`、`Runner`、`FunctionTool` 和 `OpenAIChatCompletionsModel`，调用项目已经配置的阿里云百炼兼容接口。SDK 是本地编排库；不使用 OpenAI 托管 Agent、模型、文件服务或云端 tracing。

业务职责只有：根据服务器提供的缺口选择查证工具、读取来源、提出候选事实、请求服务器重算。业务规则、来源校验、主体关联校验、证据持久化与最终结论仍由 Java 后端负责。

## 安装和验证

使用项目专用虚拟环境，不向全局 Python 安装依赖。已验证环境为 Python `3.12.14`；`requirements.lock` 固定实际安装的全部运行依赖。

```powershell
python -m venv .local/agent-venv
& .local/agent-venv/Scripts/python.exe -m pip install -r agent-worker/requirements.lock
& .local/agent-venv/Scripts/python.exe -m unittest discover -s agent-worker -p test_worker.py -v
```

`test_worker.py` 替换模型响应，不替换 SDK 的编排和工具调用过程。测试验证工具回环、来源文本与指令隔离、模型不能发布结论、重复操作去重、错误归类、异常超时和协议边界。它不是百炼实测，也不能代表 109 项实际通过率。

`probe.py` 是单独的真实供应商兼容性验证入口，只有明确执行时才调用已配置百炼服务。调用前由主程序设置上述 `BIDCHECK_AGENT_*` 环境变量；脚本不读取任何本地密钥存储或招投标文件。它使用一段虚构人员任职文字，通过真实 SDK 调用模拟检索、事实提交和重算工具，输出脱敏计数、状态和兼容性结果：

```powershell
& .local/agent-venv/Scripts/python.exe agent-worker/probe.py --output agent-worker/provider-probe.json
```

这个 probe 不代表人员审核正确率或真实文件耗时。自动离线测试只通过 `model_override` 验证其流程，不发送外部请求。

## 启动配置

Java 通过子进程环境显式传入：

| 环境变量 | 用途 |
|---|---|
| `BIDCHECK_AGENT_MODEL` | 当前配置的百炼模型，不设默认 OpenAI 模型 |
| `BIDCHECK_AGENT_BASE_URL` | 已授权百炼接口，例如 `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `BIDCHECK_AGENT_API_KEY` | 只通过子进程环境传入，禁止写入参数、协议、日志和报告 |
| `BIDCHECK_AGENT_REQUEST_TIMEOUT_SECONDS` | 单次供应商请求超时，默认 120 秒 |
| `BIDCHECK_AGENT_TOOL_TIMEOUT_SECONDS` | 等待 Java 工具回复超时，默认 120 秒 |
| `BIDCHECK_AGENT_RUN_TIMEOUT_SECONDS` | 单次查证的进程保护超时，默认 600 秒 |

调用入口：`python -u agent-worker/worker.py`。一进程接收一项查证任务，结束后退出。

Java 受控桥接会将工具回复超时与该次查证总超时对齐，避免 Worker 提前退出而 Java 工具还在运行。Java 总截止时间开始得更早，到期会取消工具、关闭调用许可并结束子进程。

超时和 10,000 轮失控循环保护均返回明确的 `FAILED` 系统状态，不会生成“缺材料”，不按费用预算跳过检查项。Java 应保存已接受事实和具体阻塞，必要时对有新输入的事项恢复查证。

生产环境只允许预置百炼域名及兼容接口路径；拒绝账号信息、查询参数、自定义端口、HTTP 和其他主机。测试可以通过 `BIDCHECK_AGENT_ALLOW_TEST_LOCALHOST=1` 使用本机 HTTP 模拟供应商，默认禁止。客户端禁止 HTTP 重定向和环境代理。

## JSONL 协议 `bidcheck-agent-1`

stdin/stdout 使用 UTF-8，每行一个 JSON 对象。Java 发送首行：

```json
{"type":"start","protocol":"bidcheck-agent-1","requestId":"investigation-123","context":{"domain":"PERSONNEL_OWNERSHIP","requirement":"原始要求及服务器已编译条件","gaps":["当前任职归属"],"knownFacts":[],"attemptedActions":[]},"tools":[{"name":"search_evidence","description":"在当前冻结文件范围内检索证据","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"],"additionalProperties":false}}]}
```

Worker 请求工具：

```json
{"type":"tool_call","protocol":"bidcheck-agent-1","requestId":"investigation-123","id":"tool-1","name":"search_evidence","arguments":{"query":"项目负责人任职表"}}
```

Java 必须回复匹配的工具调用 ID：

```json
{"type":"tool_result","requestId":"investigation-123","id":"tool-1","result":{"sources":[{"sourceId":"server-owned-source-id","page":22,"text":"原文"}]}}
```

`requestId` 在回复中可省略以兼容简版主机；若提供则必须匹配。结果必须是 JSON 对象。`cancel` 控制消息会保留未完成状态并终止本次查证。

每次模型 HTTP 请求前，Worker 会先发送带许可 ID 的事件并等待主机确认当前运行仍拥有有效执行权：

```json
{"type":"event","protocol":"bidcheck-agent-1","requestId":"investigation-123","event":"model_started","id":"permit-1","modelIndex":1,"elapsedMs":20}
```

Java 完成运行状态和执行租约检查后，才可回复：

```json
{"type":"event_ack","requestId":"investigation-123","id":"permit-1","allowed":true}
```

`allowed:false`、回复 ID 不匹配、缺少回复或通信中断都会停止模型请求并返回明确系统失败。未获得 `allowed:true` 不会调用模型 HTTP 客户端。等待许可采用 `BIDCHECK_AGENT_TOOL_TIMEOUT_SECONDS`；结果的 `modelCalls` 只统计获准后的请求尝试，`modelPermissionRequests` 另计许可请求数。Java 不应给普通事件发送 ack，避免占用工具回复通道。

Worker 仅接受下列工具名，而且 Java 可以按领域进一步减少工具：

- `search_evidence`
- `read_section`
- `read_table_record`
- `inspect_page_region`
- `query_external`
- `submit_candidate_fact`
- `recompute_check`

工具参数结构完全由可信 Java 代码提供；模型无法扩大工具集合。JSON schema 不允许远程引用，参数在转交 Java 前校验。Java 还必须对请求执行项目、文档版本、范围和来源绑定校验，不能因为 Worker 已做 schema 校验就信任模型给出的来源 ID。

特殊工具回执：

- `search_evidence`、`read_section`、`submit_candidate_fact` 的 `result.acceptedFactIds`：只有 Java 通过来源和业务事实校验并采用的事实 ID 才能写入，包括检索、读取时由原生解析器提取并采用的事实。模型自报以及其他工具返回的同名字段无效。新增 ID 会使旧读取缓存和旧业务完成回执失效，要求重新计算。
- `recompute_check.result.businessCheckCompleted`：只有 Java 完成业务规则重算才能返回 `true`。它可以表示已形成风险或不适用等确定结果，并非仅指“通过”。
- `recompute_check.result.targetCheckCompleted`：本次 `targetObligationIds` 已闭合，立即停止本次调查；整项其他条件可能仍未完成，不能据此将 `businessCheckCompleted` 改为 `true`。新增事实会同时使两个旧完成回执失效。
- `SOURCE_NOT_DISCLOSED`、`ROW_OFFSET_INVALID`、`FACT_OUTSIDE_TARGET` 等已知参数/导航/候选验证拒绝：记录 `validationRejectionCount`，允许按提示修正，不记作基础设施失败。
- 非验证类 `result.error`，或 `FAILED` / `ERROR` / `UNAVAILABLE`：按规范化操作记录未恢复技术故障。仅同一操作随后成功才清除，记录 `recoveredToolCount`；其他来源成功、部分新事实或仅登记 `PENDING` 均不能清除。未知错误即使附带 `recoverable=true` 仍按技术故障处理。`failedToolCount` 表示仍未恢复的操作数，Java 以自身回执计算，忽略 Worker 自报的故障计数。

最终 Worker 消息：

```json
{"type":"result","protocol":"bidcheck-agent-1","requestId":"investigation-123","status":"EVIDENCE_UPDATED","code":"","summary":"已提交并采用新的来源证据，仍需由服务器继续核验剩余条件。","acceptedFactIds":["fact-123"],"businessCheckCompleted":false,"modelCalls":4,"toolCalls":3,"duplicateCallsAvoided":0,"failedToolCount":0,"inputTokens":1200,"outputTokens":200,"elapsedMs":12500,"sdk":"openai-agents","sdkVersion":"0.24.0","tracingExportEnabled":false}
```

状态定义：

| 状态 | 含义 |
|---|---|
| `COMPLETED` | Java 的 `recompute_check` 确认本次目标已闭合；整项是否完成另看 `businessCheckCompleted`，不等于通过 |
| `EVIDENCE_UPDATED` | 采用了新增事实，但必要业务条件尚未全部核验完成 |
| `NO_PROGRESS` | 本次没有新增已接受事实，或重复操作没有新增证据；不等于缺材料 |
| `FAILED` | 配置、模型、工具、超时或协议异常；保留为系统处理问题 |

模型最终自然语言文本不会被采纳为结论、事实、缺材料声明或处理状态。最终说明由程序固定生成。

检索、读取或提交产生新的可信事实后，Worker 提示下一步先重算，再按剩余缺口查证。收到可信 `recompute_check.targetCheckCompleted=true` 或整项完成后，SDK 的自定义 `tool_use_behavior` 直接结束本轮，省去额外的模型总结请求。结束判定读取已验证的 Host 状态，不读取模型自报；同一批工具中随后采用的新事实仍会使旧完成回执失效，未完成时继续执行查证。

## 候选事实契约

`submit_candidate_fact` 的字段名称、必填项和类型以 Java 首行下发的 `parameters` 为准；Worker 原样校验和转交，不另设一套与后端冲突的事实结构。该契约必须满足以下约束：

1. 只能提交本次已返回的来源标识及原文引用，不能通过模型自造 ID 创建“来源”。
2. 人员、单位、岗位、日期、证书等都是待验证候选值；页面命中、相似度和模型自报置信度都不是接受条件。
3. 文件版本、文档哈希、项目/标段范围、事实 ID 和最终校验结果由 Java 绑定或生成，模型不得覆盖。
4. Java 应验证原文包含关系、字段与同一材料记录的对应关系、主体关系和范围，成功后才返回 `acceptedFactIds`。被拒绝的候选返回明确拒绝原因和空接受列表。
5. Worker 仅统计工具回执中的接受 ID。模型在自然语言中自称接受、在检索原文中出现接受 ID，均不生效。
6. 采用新事实后，之前的 `businessCheckCompleted=true` 回执自动失效；必须对新事实再次运行 `recompute_check`。

这些要求防止模型通过“提交一个像事实的 JSON”绕过现有证据校验。Worker 的工具 schema 校验保障参数形状，真实业务事实是否成立仍须服务端验证。

### 目标工作流 `TARGET_FACTS_V1`

Java 在 `context.workflowPolicy` 下发 `TARGET_FACTS_V1` 时启用目标绑定与滚动上下文；旧协议任务继续兼容。此模式要求非空 `targetObligationIds`。每次 `submit_candidate_fact` 必须携带属于该集合的顶层 `targetObligationId`，并满足 Java 下发的目标字段契约。空目标、错误目标和不可归属的拒绝回执属于系统协议失败，不能转成投标材料缺失。

候选拒绝回执必须含 `semanticWorkItems` 数组，每项包含：

- `workKey`、`targetObligationId`：Java 根据目标、标准谓词、可信实体记录生成的业务工作身份。字段别名、模型给出的事实类型、引用顺序不参与身份计算。
- `evidenceRevision`：仅该工作的相关来源、布局、主体绑定和规则版本。无关来源变化不能解锁该目标。
- `terminalForRevision`：该工作路径在当前版本不再重试；`targetExhaustedForRevision` 另行表示该目标已知记录集的取证策略耗尽。前者不会自动意味着后者。
- `failureFamily`、`remainingRepairActions`：具体失败来源及服务器允许继续的修复动作。更改模型措辞不产生新动作。

读取新相关证据时，Java 可返回 `targetEvidenceRevisions: {targetId: revision}`；Worker 仅解锁版本改变的目标，保留其他目标的阻塞。所有停止状态仍须由 Java 解释为本次查证是否完成，不能直接判定“缺材料”，也不代表业务已经通过。没有全局模型调用预算。

目标进度账本按 `targetId + serverPlanRevision/approvedPlanRevision + targetEvidenceRevisions[targetId]` 计量。A 切换到 B 再返回 A，A 的无收益次数仍保留；B 的新事实和任意新页面不清空 A 的记录。新位置只记 `navigationCount`，服务器接受事实或关闭业务目标才记证明进展。初次相关导航仍可继续读取，不把一次正常的检索—读取—提交过程当作重复失败。

同目标两次无收益纠错仅关闭该目标当前版本的路径，结果另给 `exhaustedTargetObligationIds`；其他未访问目标通过 `activeTargetObligationId/targetSchedule` 继续调度。`remainingTargetObligationIds` 是仍可执行的工作集合，不是完整的业务缺口集合；Java 必须保留未闭合业务条件并独立重算。无 target 的多事项读取使用独立 `DOCUMENT_DISCOVERY` 账本，停滞后要求明确业务目标，不能用持续返回新页绕过目标计量，也不能把导航结束当作业务目标完成。

结果中的 `targetProgressLedger` 可由可信 Java 主机保存并在下次 `context.targetProgressLedger` 回传；只有规则、目标及相关证据版本的 scope 完全一致才采用。首行应同时携带 `targetEvidenceRevisions`，避免使用全局 revision 恢复目标状态。账本保留 `firstFailureEventId`、最高无收益次数、导航/采纳事实/目标闭合计数及恢复触发条件；相关版本改变才重新启用该目标。Worker 的 `NO_MORE_EVIDENCE` 不是材料缺失判定。

模型每轮接收活动目标的完整字段契约、相关主体事实及必要祖先逻辑；其他目标保留 ID 与调度状态，切换后恢复其完整契约。服务器原始树、文档范围和来源验证权限保持不变。旧来源正文仍须通过读取工具重新取得，不能由压缩索引生成引文。相关事件记录活动目标、保留合同/事实数、输入 Token、目标进度和首次失败事件，不记录证件原号或正文。

通过 SDK 的 `call_model_input_filter`，每次模型请求只保留固定任务、最新可信契约和工作状态、来源索引及最近两个完整工具调用批次。历史审计不被删改；函数调用和对应结果必须成对保留。来源正文从模型窗口退出后，可以用原来源 ID 重新读取；成功读取结果按相关版本缓存，本地重放正文时不重放旧事实采用或业务完成回执。上下文缩短只改变提供给模型的数据，不改变已验证事实及业务规则。

新增本地事件 `context_compacted` 只记录 `inputChars`、`removedChars`、保留批次数和来源计数；最终结果记录 `contextCompactions`、`contextInputChars`、`contextRemovedChars`。这些字符数用于定位重复输入，不能代替供应商的真实 token 计数或实际耗时。

## 日志与数据流

可选配置 `BIDCHECK_AGENT_AUDIT_DIRECTORY` 保存 SDK 每次模型请求的输入和返回。默认关闭；排查时设置为服务进程可写、静态网站目录以外的本地目录。部署 Worker 时须一同部署 `model_audit.py`。审计由 `on_llm_start/on_llm_end` 获取实际 SDK 上下文，输入在宿主允许模型调用后记录。它不是 HTTP 抓包或供应商计费凭证。

每次调用生成独立 JSON 文件，普通事件只保留 `modelAudit.status/file/sha256/bytes`。文件保留引用原文，密钥、认证字段和配置中的密钥文本会被遮蔽；不会开启云追踪。文件写入失败或超过单文件上限时记录明确状态，不伪称已保存全文。文件采用请求 ID 哈希命名并禁止覆盖，后续可按 `requestId + modelIndex` 对应工具链日志；历史运行没有开启此功能时，不能补造其原始模型输入输出。

Worker 输出 `type=event` 的本地事件，包含模型/工具阶段、序号、耗时、接受事实数量及完成状态，不输出提示词、文档正文、参数原文、API key 或异常原始消息。工具调用消息本身包含业务参数，Java 必须作为进程协议处理，不得直接全文记入普通日志。

云追踪被三层禁用：导入 SDK 前的环境开关、SDK 全局禁用并清空处理器、每次 `RunConfig(tracing_disabled=True, trace_include_sensitive_data=False)`。只有百炼的显式模型客户端可接收任务上下文及工具返回的必要材料。

无变化的相同操作会使用缓存；缓存按目标、规范化参数及已采用事实/已披露来源区分。重算参数中的重复说明不能绕过去重；已知新来源可恢复此前被拒绝的读取。技术故障允许真实重试，同一故障重复发生后仍保留系统失败，不把失败缓存当作成功。

不同检索措辞返回完全相同来源正文时也记录语义无进展；连续再次重复会停止本轮，保留原结论。新来源或新事实允许继续，不设置业务事项或模型费用预算。相同来源及相同正文在 SDK 对话中只传一份，后续保留引用标识和范围元数据；同来源正文或范围变化时仍传新正文。即便模型忽略 `parallel_tool_calls=False` 返回多个工具调用，Python 仍使用锁保证与 Java 的请求/回复按顺序配对。

## 滚动上下文与重算回执

模型输入保留最近两组完整工具调用及返回；一组并行工具必须全部配对，普通助手说明不占用工具组名额。原始 Runner 历史与宿主审计不被删除。保留窗口外的原文通过 `read_section` 恢复，来源索引不能当作原文引用。

完整重算回执先由 Worker 校验并更新真实执行状态，再生成仅供模型阅读的视图：保留当前目标的决策详情、祖先的 ALL/ANY 等逻辑，以及兄弟目标的状态和缺口摘要。聚合节点重复收集的引文只保留哈希和计数，当前目标叶节点的具体证据仍保留。最新回执在 `currentHostState.latestComputationReceipt` 提供一次，历史工具结果引用同一 `fullReceiptRef`。完整最新回执仍保存在 Worker 和宿主，切换目标时从原始回执恢复，不靠模型摘要重建；视图不参与完成判定。

日志指标明确区分重建与压缩：`contextRebuilds` 计每次模型输入重建，`contextCompactions` 只计序列化字数实际减少的次数。`contextRetainedToolBatches` 记录最近一次实际保留的完整工具组数；`contextReceiptInputChars`、`contextReceiptOutputChars`、`contextReceiptRemovedChars` 记录回执视图的累计字符量。每次发出 `context_rebuilt`，实际减少时另发 `context_compacted`，同时给出工具组数、调用配对数量、回执前后字数和活动目标，便于区分反复重建与真正丢弃上下文。

`read_table_record` 属于受控宿主取证工具。只有宿主返回可信同版本自动重算回执时，Worker 才采用其事实及完成状态；表格结构识别、模型提交或界面显示本身不能自行宣布通过。

## 官方参考

- [Agents SDK 快速开始](https://developers.openai.com/api/docs/guides/agents/quickstart?lang=python)
- [模型与服务适配](https://developers.openai.com/api/docs/guides/agents/models)
- [追踪与可观测性](https://developers.openai.com/api/docs/guides/agents/integrations-observability)
