---
kind: error_handling
name: Spring Boot 演示应用中的错误处理模式
category: error_handling
scope:
    - '**'
source_files:
    - src/main/java/com/skloda/agentscope/controller/ChatController.java
    - src/main/java/com/skloda/agentscope/runtime/AgentEventMapper.java
    - src/main/java/com/skloda/agentscope/agent/AgentConfigService.java
    - src/main/java/com/skloda/agentscope/composite/CompositeAgentFactory.java
    - src/main/java/com/skloda/agentscope/blackboard/SupervisorRuntimeFactory.java
    - src/main/java/com/skloda/agentscope/harness/HarnessAgentService.java
    - src/main/java/com/skloda/agentscope/mcp/McpClientService.java
    - src/main/java/com/skloda/agentscope/blackboard/SupervisorRuntime.java
---

## 1. 总体方案

该仓库是一个基于 AgentScope 2.0 的 Spring Boot 演示平台，**没有定义任何自定义异常类**（未找到 `extends RuntimeException` / `extends Exception` 的用户类型），也没有全局异常处理器（未找到 `@RestControllerAdvice`、`@ExceptionHandler`、`ErrorController`）。错误处理是**分散式**的：各层自行捕获异常并返回 HTTP 响应或 SSE 事件。

## 2. 关键文件与位置

- **控制器层**: `src/main/java/com/skloda/agentscope/controller/ChatController.java` —— 唯一使用 `ResponseEntity` 构建错误响应的入口。
- **运行时层**: `src/main/java/com/skloda/agentscope/runtime/AgentEventMapper.java` —— 注释明确约定“映射出错时返回 `error` payload（从不抛出）”。
- **中间件层**: `src/main/java/com/skloda/agentscope/middleware/` 下的多个中间件（如 `AuditLoggingMiddleware`、`RateLimitMiddleware`）—— 在拦截器链中直接 catch 异常。
- **黑板/Supervisor 层**: `src/main/java/com/skloda/agentscope/blackboard/SupervisorRuntime.java` —— 大量 `catch (Exception e)` 包裹业务逻辑。
- **配置加载层**: `src/main/java/com/skloda/agentscope/agent/AgentConfigService.java` —— 配置文件解析失败时包装为 `IllegalStateException`。

## 3. 架构与约定

### 3.1 参数校验 → 抛出标准异常
工厂/服务层对非法入参统一抛 `IllegalArgumentException`，例如：
- `CompositeAgentFactory` 中对 `STATE_GRAPH`、`ROUTING`、`DEBATE`、`LOOP`、`MSG_HUB`、`SUBAGENT_SEQ/PAR` 等子智能体数量校验；
- `McpClientService` 中对 STDIO/SSE/HTTP transport 必填字段校验；
- `SupervisorRuntimeFactory` 中对 agentId 不存在抛 `IllegalArgumentException`；
- `HarnessAgentService` 中对缺失 `harnessConfig` 抛 `IllegalArgumentException`。

这些异常由调用方（通常是 Controller）捕获后转为 HTTP 4xx/5xx。

### 3.2 资源/状态异常 → `IllegalStateException`
用于不可恢复的配置或运行时状态错误，例如：
- `AgentConfigService` 加载 YAML 失败时包装为 `IllegalStateException("Failed to load agent config from ...")`；
- `SupervisorRuntimeFactory` 找不到 agent 时抛 `IllegalStateException`；
- `HarnessAgentService` 未配置 DashScope API Key 时抛 `IllegalStateException`。

### 3.3 I/O 异常 → 向上抛出 `IOException`
`WorkspaceInitializer` 的文件复制方法声明 `throws IOException`，由上层捕获后转为错误响应。

### 3.4 控制器层错误响应格式
`ChatController` 中所有显式错误响应遵循统一 JSON 结构：
```json
{"error": "..."}
```
HTTP 状态码选择：
- `404 Not Found`：agent 不存在、sample prompt 不存在、skill/tool 不存在；
- `400 Bad Request`：空文件、路径遍历检测失败、缺少 fileId；
- `500 Internal Server Error`：session 创建失败、文件下载异常。

### 3.5 SSE 流式错误处理
SSE 端点不使用 HTTP 错误码，而是通过 `ChatEvent.error(message)` + `ChatEvent.done()` 双事件表示错误：
- `sseError()` / `errorAndDone()` 辅助方法封装；
- `sendMessage` 使用 Reactor 的 `onErrorResume(e -> errorAndDone(errMsg))` 将下游异常转换为 SSE 错误事件；
- `handleApproval` 同样用 `onErrorResume` 把审批流程中的异常转成 SSE 错误事件。

### 3.6 序列化容错
`ChatController.sseEvent()` 和 `toJsonString()` 内部 `try/catch (Exception e)`，序列化失败时记录日志并返回一个兜底 `ChatEvent.error("Serialization error")`，保证 SSE 连接不因 JSON 序列化失败而中断。

### 3.7 通用 catch(Exception)
多处使用宽泛的 `catch (Exception e)` 包裹第三方库调用（如 Jackson、AgentScope SDK、文件 IO），典型位置：
- `AgentConfigService` 的 YAML 解析；
- `SupervisorRuntime` 的类型转换与属性读取；
- `CorrectSkillDiagnostic`、`JarEnvironmentDiagnostic` 的诊断扫描；
- `A2aClientDemoRunner` 的启动流程。

这些 catch 块通常仅 `log.error(...)` 后返回默认值或空结果，不向上传播。

## 4. 约定与约束

- **无全局异常处理器**：未发现 `@RestControllerAdvice` 或 `@ExceptionHandler`，错误响应由各 Controller 方法自行构造。
- **无自定义异常类型**：代码全部使用 JDK 内置异常（`IllegalArgumentException`、`IllegalStateException`、`IOException`、`RuntimeException`），未定义领域异常类。
- **SSE 错误不依赖 HTTP 状态码**：流式接口通过 `ChatEvent.error` + `done` 事件表达错误，HTTP 状态码始终为 200。
- **非流式接口使用统一的 `{"error": "..."}` JSON 结构**：这是 `ChatController` 中所有 `ResponseEntity.status(4xx).body(Map.of("error", ...))` 调用的共同约定。
- **AgentEventMapper 契约**：其 Javadoc 明确约定“On mapping error, returns an `error` payload (never throws)”，是该模块的错误传播边界。
- **文件上传/下载的安全校验内联于 Controller**：路径遍历检查（`fileId.contains("..")`、`!filePath.startsWith(normalizedUploadDir)`）直接返回 400，不在异常分支中处理。
- **测试中未见对自定义异常的断言**：测试主要验证返回值与行为，而非异常类型（如 `SupervisorRoutingContextTest` 中出现的 `"system error code"` 字符串仅为测试数据，不是异常类型）。