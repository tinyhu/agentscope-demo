---
kind: configuration_system
name: Spring Boot Profile + YAML 分层配置系统（AgentScope Demo）
category: configuration_system
scope:
    - '**'
source_files:
    - src/main/resources/application.yml
    - src/main/resources/application-a2a.yml
    - src/main/resources/application-agui.yml
    - src/main/resources/application-feishu.yml
    - src/main/resources/application-mysql.yml
    - src/main/resources/application-postgresql.yml
    - src/main/resources/application-redis.yml
    - src/main/java/com/skloda/agentscope/config/DistributedStateStoreConfig.java
    - src/main/java/com/skloda/agentscope/config/A2aServerConfig.java
    - src/main/java/com/skloda/agentscope/config/AguiConfig.java
    - src/main/java/com/skloda/agentscope/config/FeishuChannelConfig.java
    - src/main/java/com/skloda/agentscope/service/KnowledgeProperties.java
    - src/main/resources/config/agents.yml
    - src/main/java/com/skloda/agentscope/AgentScopeDemoApplication.java
---

## 1. 使用的系统与框架

本项目基于 Spring Boot 的 `application.yml` + 多 profile 机制组织全部运行时配置，并通过 `@ConfigurationProperties`、`@Value`、`@Profile` 三类方式注入到 Java 代码中。核心依赖是 Spring Boot 自带的配置绑定与条件装配；AgentScope 2.0 通过其 Spring Boot Starter（如 `agentscope-a2a-spring-boot-starter`、`agentscope-agui-spring-boot-starter`）暴露 auto-configuration，由本项目的 profile 按需启用。

## 2. 关键文件与位置

- 基础配置：`src/main/resources/application.yml`（默认 profile，排除 DataSource/A2A 自动装配，定义 `agentscope.model.*`、`agentscope.memory.*`、`agentscope.tools.*`、`agentscope.session.*`、`agentscope.knowledge.*`、`agentscope.mcp.*` 等根命名空间）
- 功能 profile 配置文件：
  - `application-a2a.yml`（S12 A2A 协议）
  - `application-agui.yml`（S15 AG-UI 协议）
  - `application-feishu.yml`（S14 飞书 Channel）
  - `application-mysql.yml` / `application-postgresql.yml` / `application-redis.yml`（S13 分布式状态存储）
- Java 配置类：`src/main/java/com/skloda/agentscope/config/` 下的 `DistributedStateStoreConfig.java`、`A2aServerConfig.java`、`AguiConfig.java`、`FeishuChannelConfig.java`、`CorrectSkillDiagnostic.java`、`JarEnvironmentDiagnostic.java`、`SkillFileSystemHelperDiagnosticRunner.java`、`A2aClientDemoRunner.java`
- 类型安全属性：`src/main/java/com/skloda/agentscope/service/KnowledgeProperties.java`（`@ConfigurationProperties(prefix = "agentscope.knowledge")`），在 `AgentScopeDemoApplication` 上通过 `@EnableConfigurationProperties` 显式注册
- Agent 行为配置：`src/main/resources/config/agents.yml`（集中声明所有 agentId、systemPrompt、modelName、tools、skills、harnessConfig、RAG、longTermMemory 等）
- 测试覆盖：`src/test/resources/application-test.yml` 提供测试 profile

## 3. 架构与设计约定

### 3.1 分层加载顺序
1. **默认 `application.yml`** 提供全局默认值（端口、模型 provider、内存模式、工具开关、日志级别等）。
2. **按 `--spring.profiles.active=...` 激活的 profile yml** 叠加覆盖（如 `mysql` 覆盖 `agentscope.session.type`，`redis` 覆盖为 Redis store，`a2a`/`agui`/`feishu` 开启对应协议）。
3. **环境变量** 通过 `${VAR:default}` 语法注入敏感值（`DASHSCOPE_API_KEY`、`MYSQL_URL`、`REDIS_URL`、`FEISHU_APP_ID`、`POSTGRES_*` 等），实现“配置即代码、密钥进环境”的分离。
4. **Java `@Configuration` + `@Profile`** 根据 profile 决定是否创建 Bean（如仅当 `redis|mysql|postgresql` 时创建 `AgentStateStore` Bean，否则回退到 InMemory/JsonFile）。
5. **`config/agents.yml`** 作为纯数据层，被 `AgentConfigService` 在运行时加载，驱动 Agent 实例化、工具/技能/RAG/Harness 行为，与 Spring 配置解耦。

### 3.2 模块化 Feature Profile
每个能力以独立 `application-*.yml` + `@Profile("xxx")` 配置类组合呈现：
- `a2a`：暴露 `ReActAgent.Builder` Bean，由 AgentScope A2A starter 自动装配为 JSON-RPC 服务。
- `agui`：注册 `@AguiAgentId("chat-basic")` 的 Agent Bean 到 AguiAgentRegistry。
- `feishu`：构建 `FeishuChannelProperties` / AccessTokenProvider / OutboundClient。
- `redis|mysql|postgresql`：通过 `DistributedStateStoreConfig` 暴露 `AgentStateStore` Bean，并附带乐观并发控制（OCC）版本化日志。
- `correct-skill-diagnostic`、`jar-environment` 等诊断 profile 用于开发期自检。

### 3.3 默认启动最小化
`application.yml` 显式 exclude `DataSourceAutoConfiguration`、`DataSourceTransactionManagerAutoConfiguration`、`AgentscopeA2aAutoConfiguration`，保证无 profile 时零外部依赖启动；数据库/A2A 仅在对应 profile 下启用。

### 3.4 配置来源映射
| 配置项 | 来源 | 说明 |
|---|---|---|
| `agentscope.model.*` | `application.yml` | 模型 provider、DashScope API Key、流式/思考模式 |
| `agentscope.session.type` | `application-{mysql,redis,postgresql}.yml` | 切换会话持久化后端 |
| `agentscope.distributed.redis.*` | `application-redis.yml` | Redis URL、key-prefix |
| `agentscope.channel.feishu.*` | `application-feishu.yml` | 飞书 App 凭据、回调路径 |
| `agentscope.agui.*` | `application-agui.yml` | AG-UI path-prefix、超时、事件开关 |
| `agentscope.a2a.*` | `application-a2a.yml` | A2A server card、transport |
| `agentscope.knowledge.*` | `KnowledgeProperties` + `application.yml` | RAG 知识库路径、embedding 模型、分块策略 |
| Agent 元数据 | `config/agents.yml` | 每个 agentId 的 systemPrompt、tools、skills、harnessConfig、samplePrompts |

## 4. 约定与约束

- **profile 命名规范**：每个可插拔能力使用独立的 `application-<feature>.yml` 文件名和同名 `@Profile("<feature>")` 配置类，注释统一标注 “Sxx: <描述>” 及激活命令（如 `--spring.profiles.active=a2a`）。
- **敏感信息不进仓库**：API Key、数据库密码、飞书 token 一律通过 `${ENV_VAR:default}` 形式读取，默认值为空或本地地址，禁止硬编码。
- **Bean 互斥**：分布式状态存储通过 `@Profile({"redis", "mysql", "postgresql"})` 单例暴露一个 `AgentStateStore` Bean；同时只能激活其中一个，避免冲突。
- **Agent 配置与 Spring 配置分离**：Agent 的行为（prompt、tools、skills、harnessConfig、RAG、approvalTools、structuredOutputClass 等）集中在 `config/agents.yml`，由 `AgentConfigService` 在运行时读取，不混入 Spring 配置。
- **默认回退策略**：未激活任何 profile 时，应用使用 InMemory 会话存储、禁用 A2A/AG-UI/飞书，确保开箱即用；激活特定 profile 才引入外部依赖。
- **日志分级隔离**：每个 profile 的 yml 单独设置 `logging.level.io.agentscope.*` 子包，便于按功能模块调试。
- **测试隔离**：`src/test/resources/application-test.yml` 提供测试专用 profile，不影响主配置。
- **版本化状态存储**：S13 的 Redis/MySQL/PostgreSQL 存储均通过 `supportsVersioning()` 输出 OCC 支持日志，表明 2.0.3+ 扩展原生支持乐观并发，无需额外开关。
