---
kind: logging_system
name: 基于 SLF4J + Logback 的审计中间件式日志体系
category: logging_system
scope:
    - '**'
source_files:
    - src/main/resources/application.yml
    - src/main/java/com/skloda/agentscope/middleware/AuditLoggingMiddleware.java
    - src/main/java/com/skloda/agentscope/middleware/DetailedAuditMiddleware.java
    - src/main/java/com/skloda/agentscope/middleware/MiddlewareRegistry.java
    - src/main/java/com/skloda/agentscope/hook/ObservabilityHook.java
    - src/main/java/com/skloda/agentscope/controller/MetricsController.java
    - src/main/java/com/skloda/agentscope/agent/AgentConfigService.java
    - src/main/java/com/skloda/agentscope/agent/AgentFactory.java
---

## 1. 使用的系统与框架

- **日志门面**：统一使用 `org.slf4j.Logger` / `LoggerFactory`，所有业务类通过 `private static final Logger log = LoggerFactory.getLogger(Xxx.class)` 方式获取 logger。
- **日志实现**：项目未引入自定义 `logback.xml`/`log4j2.xml`，依赖 Spring Boot 默认集成的 Logback；日志输出格式由 `application.yml` 中 `logging.pattern.console` 控制。
- **结构化字段**：没有 JSON 结构化日志框架（如 Logstash、GELF），而是通过固定前缀标签 `[audit]`、`[DETAILED-AUDIT]`、`[getSkillInfo]` 等作为“伪结构化”字段，配合占位符参数化输出关键上下文（agentName、traceId、toolName、durationMs、token 计数等）。
- **额外输出**：`AuditLoggingMiddleware` 中还混用 `System.out.println("[AUDIT-LOGGING] ...")` 直接打印到 stdout，用于快速调试。

## 2. 核心文件与位置

| 职责 | 文件路径 |
|---|---|
| 全局日志级别与控制台格式 | `src/main/resources/application.yml` |
| 基础审计中间件（Agent/Reasoning/Acting 生命周期） | `src/main/java/com/skloda/agentscope/middleware/AuditLoggingMiddleware.java` |
| 详细审计中间件（Trace ID、Thinking/Text/Tool 流式事件、Token 统计） | `src/main/java/com/skloda/agentscope/middleware/DetailedAuditMiddleware.java` |
| 中间件注册表（启用 audit-logging、detailed-audit） | `src/main/java/com/skloda/agentscope/middleware/MiddlewareRegistry.java` |
| 可观测性 Hook（将 AgentScope 事件桥接到 EventSink） | `src/main/java/com/skloda/agentscope/hook/ObservabilityHook.java` |
| 指标/审计日志查询入口 | `src/main/java/com/skloda/agentscope/controller/MetricsController.java` |
| 典型业务日志示例（配置加载、Agent 创建） | `src/main/java/com/skloda/agentscope/agent/AgentConfigService.java`、`src/main/java/com/skloda/agentscope/agent/AgentFactory.java` |

## 3. 架构与约定

### 3.1 日志级别策略
- 根级别 `root: INFO`，并显式把 `io.agentscope`、`com.msxf.agentscope`、`METRICS` 设为 `INFO`。
- 业务代码中：正常流程走 `log.info(...)`，配置异常/兼容告警走 `log.warn(...)`，错误走 `log.error(..., e)`。未见 `DEBUG`/`TRACE` 级别的常规使用。

### 3.2 控制台输出格式
`application.yml` 定义：
```
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```
即标准单行文本格式，包含时间、线程、级别、logger 名、消息体。

### 3.3 审计中间件模式
日志以 **AgentScope Middleware** 形式横切注入，而非散落在业务方法内部：
- `AuditLoggingMiddleware`：记录 agent 启动/完成耗时、reasoning 阶段消息数、tool 调用名称及参数预览（截断至 80 字符）、tool 执行耗时。
- `DetailedAuditMiddleware`：为每次 agent 执行生成 8 位 `traceId`，通过 `ThreadLocal<TraceContext>` 在 Reactor `Flux` 链中传递上下文；捕获 `THINKING_BLOCK_DELTA`、`TEXT_BLOCK_DELTA`、`TOOL_RESULT_TEXT_DELTA` 等流式事件，聚合 thinking/text/toolResult 内容并在阶段结束时输出；统计 reasoning rounds、tool calls、thinking blocks、text blocks、total tokens。
- 两者均通过 `MiddlewareRegistry` 以 `"audit-logging"`、`"detailed-audit"` 键注册，便于按需启用。

### 3.4 可观测性事件桥接
`ObservabilityHook` 封装 `EventSink`，暴露 `emitPipelineStart/End`、`emitRoutingDecision`、`emitHandoffStart/Complete`、`emitLoop*`、`emitGraph*`、`emitRoundtable*`、`emitTask*` 等方法，将多智能体协作阶段的语义事件以 `Map<String,Object>` 形式推送到下游消费者（如 MetricsController 或外部系统）。该 hook 自身也通过 SLF4J 记录日志。

### 3.5 业务层日志约定
- 配置加载：`AgentConfigService` 对每个 agent 输出 `Loaded agent config: {name} ({id})`，对重复 id 输出 `warn` 告警。
- Agent 构建：`AgentFactory` 输出 `Creating agent: {name} ({id}) [permissionMode=...]`，并对 RAG、PlanNotebook、ApprovalMiddleware、long-term memory 等能力启用情况分别打 `info` 标记。

## 4. 约定与约束

- **统一门面**：全仓使用 SLF4J，未发现直接使用 `java.util.logging`、`log4j` 原始 API 的地方。
- **无 JSON 结构化日志**：所有日志均为单行文本，结构化信息通过固定前缀标签（`[audit]`、`[DETAILED-AUDIT]`、`[getSkillInfo]`）和参数化占位符表达。
- **Trace 上下文**：仅 `DetailedAuditMiddleware` 实现了跨事件的 traceId 关联，通过 `ThreadLocal` 维护；其他中间件未复用此上下文。
- **流式事件处理**：审计日志全部基于 Reactor `Flux` 的 `doOnNext/doOnComplete/doOnError` 钩子追加，避免阻塞主链路。
- **长度保护**：对可能较大的 payload（工具参数、thinking 内容、tool result）进行截断（80/100/150/200 字符不等），防止日志膨胀。
- **测试覆盖**：`AuditLoggingMiddlewareTest`、`DetailedAuditMiddleware` 相关用例位于 `src/test/java/com/skloda/agentscope/middleware/`，验证中间件注册与基本行为。
- **未发现的约束**：仓库中没有统一的日志 level 切换开关、没有按模块拆分 logger 的策略文档、没有异步/文件 sink 配置，因此日志主要面向本地开发调试与演示用途。
