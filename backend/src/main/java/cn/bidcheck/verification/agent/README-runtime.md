# Java 受控证据查证

生产 Agent 使用 Java 8，在应用 JVM 内执行多轮 Chat Completions 工具调用。无需 Python、OpenAI Agents SDK 或独立 Worker 服务。模型端点沿用已配置的阿里云 DashScope 兼容接口，最终业务结论仍由原有 Java 规则和证据校验生成。

## 配置

```powershell
$env:BIDCHECK_AGENT_ENABLED = 'true'
$env:BIDCHECK_AGENT_BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
$env:BIDCHECK_AGENT_MODEL = '已授权使用的模型'
# BIDCHECK_AGENT_API_KEY 从运行环境或受控密钥存储注入。
```

未单独设置 Agent 模型配置时，继承现有抽取模型配置。默认关闭，默认并发数为 2，总超时为 600 秒。`Start-BidCheck.ps1` 仅启动 Java 应用；不检测、安装或自动启动语言解释器。

## 执行边界

模型只能请求服务器提供的工具。服务器继续验证文件冻结版本、来源、动态工具参数、执行租约与调用许可；每次模型请求都必须先取得当前运行许可。模型自然语言、候选事实或工具调用本身不能发布最终通过结论。

已有可信回执、目标版本、审查计划、进度与重复动作处理、上下文裁剪、来源重读、超时取消和并发保护继续参与查证。最终状态只接受 Java 工具实际返回的事实和重算回执，不能从模型的自述推导业务完成。

模型请求限定于允许的 DashScope 主机和兼容路径，禁止重定向和环境代理。密钥不进入提示、业务回执或导出的身份。可选 `BIDCHECK_AGENT_AUDIT_DIRECTORY` 记录本地脱敏模型与工具输入输出，默认关闭。

## 验证

离线测试使用本机模拟模型接口和受控工具，覆盖多轮回环、动态参数校验、可信回执、许可拒绝、超时、中断、重复动作和审计边界，不调用真实模型。历史 Python Agent 和测试保存在 `tests/legacy-python/agent-worker/`，仅用于行为对照；它们不会被 Maven 打入生产 JAR。
