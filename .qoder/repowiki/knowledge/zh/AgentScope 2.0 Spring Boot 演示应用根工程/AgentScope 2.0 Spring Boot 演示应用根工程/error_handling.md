## 1. 整体策略：基于 Flux + SSE 的错误下沉，不抛 HTTP 异常

仓库没有任何自定义业务异常类（`grep *Exception* -l` 结果为空），也**没有** `@ControllerAdvice` / `@ExceptionHandler` 等 Spring 全局异常处理器。错误处理的核心理念是：**HTTP 边界永远正常返回 200，但 SSE 流中携带语义化的 `ChatEvent.type = "error"` 事件**。所有上游工具/中间件/运行时抛出的异常都通过 Project Reactor 的 `Flux.onErrorResume` 拦截并转换为 `errorAndDone(...)`。

核心入口 `ChatController.sendMessage` 在 `agentService.createStreamFlux(...).onErrorResume(e -> errorAndDone(errMsg))` 统一兜底；`handleApproval` 也对每条 resume 流做同样的 `onErrorResume` 覆盖。因此工具层的异常不会向上传播为 HTTP 5xx，而是以 `type: "error"` 的 SSE 事件回给前端。

## 2. 统一的 SSE 错误事件载体：`ChatEvent`

`src/main/java/com/skloda/agentscope/model/ChatEvent.java` 是唯一的 SSE payload 模型，仅三个字段：`type`、`message`、`content`。其 `done()` 和 `error(message)` 静态工厂供全局复用。控制器内部维护四组 helper：
- `sseEvent(payload)` — JSON 序列化失败时降级为 `ChatEvent.error("Serialization error")`
- `toJsonString(obj)` — 同样捕获 Jackson 异常并退回裸 JSON
- `sseError(message)` → `sseEvent(ChatEvent.error(message))`
- `errorAndDone(message)` → 先发 `error` 再发 `done`，保证前端能稳定识别结束

这是仓库内唯一的「结构化错误响应」模型，其他任何地方不定义独立的 Error DTO。

## 3. 各层的错误传播路径

### 工具层（`tool/*`）
所有工具类 (`DocxParserTool`、`PdfParserTool`、`XlsxParserTool`、`WebSearchTool`、`BankInvoiceTool` 等) 直接使用 try-catch 包裹底层 I/O 或第三方库调用，将异常转为文本结果而非向上抛出（例如 Xlsx 解析失败时记录日志后返回失败描述）。这保证了 AgentScope 工具执行器收到的是普通工具结果，AgentRuntime 层面继续按流程转发；异常不会中断 SSE 流。

### 运行时层（`runtime/*`）
`AgentRuntime.stream()` 本身不主动抛错——它把异常留给下游 `AgentEventMapper` 及 Agent 框架处理，并在 `doOnError` 里做日志记录 + `close()` 清理：
```java
.doOnError(e -> {
    log.error("Stream error for agent: {}", agent.getName(), e);
    this.close();
});
```
`MultiAgentStreamSupport`、`StructuredOutputValidator`、`SupervisorRuntimeFactory` 等复合运行时同样用 `log.warn` / `log.error` 吞掉校验失败和配置异常，让流继续或静默跳过步骤。

### 中间件层（`middleware/*`）
`MiddlewareRegistry.registerBuiltInMiddlewares()` 启动阶段逐个注册审计、限流、上下文增强、详细审计、指标收集、OpenTelemetry tracing 等中间件。每个中间件实现 `io.agagentscope.core.middleware.MiddlewareBase`，采用 **try-catch + log + 链式传递** 的模式：捕获自身执行异常后记日志但不阻断链。`MetricsCollectorMiddleware`、`DetailedAuditMiddleware` 均遵循此约定。

### Hook / 事件源层（`hook/` + `runtime/EventSink`）
`ObservabilityHook` 包装 `EventSink`，对 pipeline/routing/handoff/loop/graph 等手动事件使用 EventSink 的 `emitXxx` 方法。EventSink.asFlux() 被合并进主 SSE 流；流完成时显式调用 `eventSink.complete()`（见 `AgentRuntime` 注释：之前循环依赖导致 sink 永远不完成，前端会卡死，已修复）。这里没有显式异常传播，依赖下层工具异常经 Event 流转后被 Controller 的 `onErrorResume` 捕捉。

### Agent 装配与配置加载
`AgentConfigService`、`ToolRegistry`、`HarnessAgentFactory`、`AgentFactory` 在构造期扫描 YAML / SKILL.md / classpath，遇到无法解析的文件时统一采用 `log.warn` + 跳过该条目，而不是启动期 Fail-Fast。例如 ToolRegistry 对缺失 skill 的前置文件打印 `"Failed to parse skill file:"` 并继续。

## 4. 已知约束与模式总结

- **无前向声明的业务异常**：仓库中不存在 `com.skloda.agentscope.exception.*` 包，也没有继承 `RuntimeException` 的领域异常类型。判断依据：grep `extends RuntimeException`、`new xxxException` 在整个 src/main 下未命中任何自定义定义。需要新建领域异常时应参照现有约定 —— 由调用方捕获并转 SSE event。
- **SSE 永远正常结束**：所有失败的请求也必须发送一条 `type: "done"` 作为收尾，前端据此重置 UI 状态。`ChatController.errorAndDone` 是实现模板。
- **Jackson 序列化必须容错**：`ChatController` 明确区分了两种序列化失败场景（构建 SSE vs 构建纯 JSON），后者直接 fallback 到硬编码的 `{"type":"error","message":"Serialization error"}`，确保流不被阻塞。
- **Reactor 错误必须在边缘恢复**：只允许在 Controller 或 Flux 链路最外层做 `onErrorResume`，内部 runtime/middleware 应 logging-only，避免多层吞错造成“丢失错误根因”。`AgentRuntime` 的 `doOnError` 只做 cleanup，实际用户可见的错误由 Controller 转换。
- **AgentScope 框架错误经由 `AgentEventMapper` 转化**：`AgentRuntime.mapAgentEvent` 委托给 `AgentEventMapper`，该 mapper 覆盖 30+ AgentEventType，并把异常事件映射为 `"type": "error"` 的 map，再由 Controller 统一封装。
- **测试用例覆盖了流中止场景**：`runtime/AgentRuntimeTest`、`controller/ChatControllerStreamTest`、`blackboard/NestedAgentStateOverwriteRegressionTest` 等验证了 pending tool call 清理、stream hang 修复、approval 路径下的异常传播行为。

## 5. 关键文件清单

- `ChatEvent.java` — 唯一 SSE 错误/成功事件载体
- `ChatController.java` — 唯一集中式 onErrorResume 与 SSE helper
- `AgentRuntime.java` — Agent 级 stream 错误日志 + 清理 + approval 恢复路径
- `MultiAgentStreamSupport.java`、`StructuredOutputValidator.java`、`SupervisorRuntimeFactory.java`、`StateGraphRuntime.java` — 复合运行时错误收敛点
- `ObservabilityHook.java` — 手工事件流的 emit/complete 生命周期
- `middleware/MiddlewareRegistry.java` — 中间件注册，单点定位审计/追踪扩展
- `tool/ToolRegistry.java` — 工具/技能装配失败时 warn+跳过，非 Fail-Fast
