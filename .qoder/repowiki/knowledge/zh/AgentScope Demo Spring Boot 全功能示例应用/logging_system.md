## 1. 使用的系统/框架

本仓库通过 Spring Boot 3.5（`spring-boot-starter-web`）间接引入 **Logback** 作为 SLF4J 的默认实现，并通过 `application.yml` 的 `logging.level` 和 `logging.pattern.console` 进行配置。所有业务组件通过 `org.slf4j.Logger` / `LoggerFactory.getLogger(...)` 直接记录日志，未使用 Lombok `@Slf4j`、Log4j2 或自定义桥接器。仓库内未发现独立的 logback.xml，全部以 application.yml 中的 key/value 形式控制日志行为。

此外，AgentScope 2.0 提供的 `MiddlewareBase` 钩子被用于集中式的 **审计与指标日志**：`AuditLoggingMiddleware`、`DetailedAuditMiddleware`、`MetricsCollectorMiddleware` 分别注册在 `MiddlewareRegistry` 中，对 agent/reasoning/acting/modelCall 等阶段注入日志输出，这是本次日志体系区别于普通业务代码的核心特征。

## 2. 关键文件

- `src/main/resources/application.yml`：根 logger level（`INFO`）、`io.agentscope`、`com.msxf.agentscope` 与自定义 `METRICS` logger 级别；控制台输出 pattern；文档声明 `logging.level.io.agentscope: DEBUG` 可调低。
- `pom.xml`：仅依赖 `spring-boot-starter-web`，未显式引入 spring-boot-starter-logging，因此走 Boot 默认的 Logback 实现。
- `src/main/java/com/skloda/agentscope/middleware/AuditLoggingMiddleware.java`：基础审计中间件，记录 agent 起止、reasoning/tool 调用预览、耗时。
- `src/main/java/com/skloda/agentscope/middleware/DetailedAuditMiddleware.java`：增强审计，生成 `traceId`（基于 UUID 前 8 位），利用 `ThreadLocal<TraceContext>` 串联一次请求的 reasoning rounds、tool calls、thinking/text 内容，完成端到端可追踪的 `[DETAILED-AUDIT] [...] audit` 日志块。
- `src/main/java/com/skloda/agentscope/middleware/MetricsCollectorMiddleware.java`：收集工具调用耗时、token 用量、全局错误计数，并以独立 `METRICS` logger（`LoggerFactory.getLogger("METRICS")`）聚合输出成本估算。
- `src/main/java/com/skloda/agentscope/middleware/MiddlewareRegistry.java`：负责将所有中间件实例集中装配到 AgentScope 执行链。
- 各 tool / runtime / controller（如 `XlsxParserTool`、`WebSearchTool`、`ChatController`、`KnowledgeController`、`HarnessAgentFactory`、`ObservabilityHook`、`AgentRuntimeFactory` 等）均以 `private static final Logger log = LoggerFactory.getLogger(Xxx.class)` 方式获取 logger 并记录 info/error。

## 3. 架构与约定

### 3.1 日志门面与后端
- 统一使用 **SLF4J 门面**，无具体实现导入。
- 通过 Spring Boot 自动管理的 Logback Console Appender 输出，pattern 为 `%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n`，即带日期、线程、级别、36字符截短的 logger 名与消息。

### 3.2 级别策略
- `root`、`io.agentscope`、`com.msxf.agentscope`、`METRICS` 均默认 `INFO`。
- 项目启动时不输出调试日志；调试 AgentScope 内部行为需要在启动参数或环境变量中将 `logging.level.io.agentscope` 改为 `DEBUG`（见 AGENTS.md 的 “Run” 说明）。

### 3.3 中间件化审计（核心设计）
所有 AgentScope 执行路径都经过 `MiddlewareBase` 拦截链，审计逻辑集中于三个中间件而非散落在业务代码中：
- `onAgent`：记录 agent name、输入消息数、整体耗时。
- `onReasoning`：计数 reasoning rounds，可选打印 thinking/text 增量。
- `onActing`：逐个记录 `ToolUseBlock` 的名称与入参（过长时截断至 80/150 字符），并记录 tool 级耗时。
- `onModelCall`（仅 Metrics）：消费 `MODEL_CALL_END` 事件累加 token。

`DetailedAuditMiddleware` 进一步维护 `TRACE_CONTEXT`（包含 traceId、agentName、计数器等 ThreadLocal），将一次 agent 调用的开始→事件流→结束（含成功/失败分支）串成一组结构化的 `[DETAILED-AUDIT]` + `[audit]` 块，方便按 traceId 检索完整链路。

### 3.4 结构化字段约定
虽然未使用 JSON 日志库，但日志行内使用固定的 key 约定：
- 审计前缀：`[AUDIT-LOGGING]`、`[DETAILED-AUDIT]`、`[audit]`，便于 grep。
- 追踪键：`Trace ID`、`Agent`、`Success`、`Total duration`、`Tool calls`、`Thinking blocks`、`Text blocks`、`Reasoning rounds`。
- 指标通道：通过命名 logger `METRICS` 单独输出性能统计，避免污染业务日志。
- 工具参数采用“首 80/150 字符 + `...`”的截断格式，防止超长 tool input 撑大日志。

### 3.5 普通业务日志位置
除了中间件外，以下场景也在各自类中直接打日志：
- Controller 层：HTTP 请求入口（`ChatController`、`FeishuChannelController`、`KnowledgeController`）记录接口进入与异常。
- Tool 层：文档解析/网络搜索/发票生成工具（`DocxParserTool`、`PdfParserTool`、`XlsxParserTool`、`WebSearchTool`、`BankInvoiceTool`、`ContractReviewReportTool`）记录文件处理过程与错误。
- Runtime/Harness：`AgentRuntimeFactory`、`StreamingAgentRuntime`、`ParallelRuntime`、`SequentialRuntime`、`LoopRuntime`、`MsgHubRuntime`、`SubAgentSeq/ParRuntime`、`DebateRuntime`、`StructuredOutputAgentRuntime`、`HarnessAgentFactory`、`HarnessAgentService`、`WorkspaceInitializer`、`ObservabilityHook` 等记录 agent 构建、流式事件、工作区初始化等运行时信息。
- Service/Config：`AgentService`、`SessionManagerService`、`ApprovalService`、`KnowledgeService`、`CompositeAgentFactory`、`AgentConfigService`、`AguiConfig`、`A2aServerConfig`、`DistributedStateStoreConfig`、各类 diagnostic runner 等。

## 4. 约定与约束

- **禁止** 使用 `System.out.println` / `System.err` 记录业务日志——除 `AuditLoggingMiddleware`、`DetailedAuditMiddleware`、`MetricsCollectorMiddleware` 这三个演示性中间件仍保留少量 `System.out` 之外，其余生产代码均只使用 SLF4J Logger。
- 所有新 middleware 必须实现 `io.agentscope.core.middleware.MiddlewareBase` 并在 `MiddlewareRegistry` 中注册，才能接入统一的 agent 执行链日志。
- 新增业务组件应遵循 `private static final Logger log = LoggerFactory.getLogger(Xxx.class);` 的单例 logger 模式，不要每个方法新建 Logger。
- 长字段输出必须截断后再 log（见 tool call 参数的 80/150 字符限制），避免破坏下游日志采集系统的行解析。
- 审计相关输出统一使用前缀字符串（`[audit]`、`[DETAILED-AUDIT]`）以便通过 grep/日志系统快速筛选。
- 如需降低 AgentScope 自身日志噪音，应调整 `application.yml` 或启动时的 `logging.level.io.agentscope`，不要在代码里动态改 level。
- 性能与成本相关的统计通过独立的 `METRICS` logger 输出，不应混入常规 `INFO` 业务日志。