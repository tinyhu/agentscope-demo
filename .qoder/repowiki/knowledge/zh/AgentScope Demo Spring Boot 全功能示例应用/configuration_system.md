## 概述

该项目采用 **Spring Boot 标准配置框架**（application.yml + `@ConfigurationProperties`）结合 **自定义 Yaml 文件 + SnakeYAML 加载器** 的方式，构建了一套面向 AgentScope 2.0 的多层配置系统。核心思路是：**运行时环境参数通过 Spring profile 与 `application-{profile}.yml` 切换，Agent/Harness 等业务实体定义通过 `config/*.yml` 静态文件声明并由应用启动时装载**。

## 一、配置来源与优先级

| 层级 | 来源 | 说明 |
|---|---|---|
| 基础应用配置 | `src/main/resources/application.yml` | 端口、multipart 大小、默认排除自动配置、AgentScope 全局配置（model、memory、session、knowledge、mcp）、日志级别 |
| 特性开关 profile | `application-a2a.yml` / `application-agui.yml` / `application-mysql.yml` / `application-postgresql.yml` / `application-redis.yml` / `application-feishu.yml` | 每个文档对应一个可激活的 Spring profile，按需引入数据库源、A2A server、AG-UI 端点、飞书 channel 等 |
| Agent 业务定义 | `config/agents.yml` + `config/harness-agents.yml` | 用 kebab-case 的 agentId 描述所有单智能体 / Harness 智能体的 systemPrompt、modelName、streaming、enableThinking、skills、userTools、systemTools 等；由 `AgentConfigService` 在 `@PostConstruct` 时从 classpath 读取并反序列化为 `AgentConfig` |
| MCP 服务器列表 | `config/mcp-servers.yml` | 供 `McpClientService` 动态加载外部 MCP 服务端点 |
| 环境变量 | `${DASHSCOPE_API_KEY}`、`MYSQL_URL`、`MYSQL_USER`、`MYSQL_PASSWORD` 等 | 通过 Spring `@Value` 注入或 `${key:default}` 语法注入到 YAML 中，敏感信息不入库 |
| profile 级覆盖 | `application-{profile}.yml` | 以 Spring profile 为单位覆盖同名 key，例如 redis/mysql/postgresql profile 各自提供独立的 DataSource |

## 二、核心组件及职责

- **`application.yml`**：唯一的全局入口。排除 `DataSourceAutoConfiguration` 和 `AgentscopeA2aAutoConfiguration` 避免默认启动时强依赖 DB/A2A；集中声明 `agentscope.model.dashscope.api-key`、`agentscope.knowledge.*`、`agentscope.mcp.*` 等。
- **`AgentConfigService`**：实现类使用 `org.yaml.snakeyaml.Yaml(new Constructor(AgentsWrapper.class, new LoaderOptions()))` 从 `classpath:config/agents.yml` 和可选的 `classpath:config/harness-agents.yml` 两个资源文件加载全部 agent 定义；对重复 agentId 报警后弃用后者，并对缺失 agentId 的条目跳过加载。
- **`KnowledgeProperties`**：使用 Spring `@ConfigurationProperties(prefix = "agentscope.knowledge")` 绑定 knowledge 子树配置，类型安全。
- **`DistributedStateStoreConfig`**：基于 `@Profile({"redis","mysql","postgresql"})` 条件装配，分别暴露 `RedisAgentStateStore`、`MysqlAgentStateStore`、`PostgresAgentStateStore` 三种 `AgentStateStore` Bean；无 profile 时不创建任何 Bean，保持零开销。
- **`A2aServerConfig`**：仅在 `@Profile("a2a")` 下暴露 `ReActAgent.Builder` Bean，被 `agentscope-a2a-spring-boot-starter` 自动装配为 A2A Server；暴露 `/.well-known/agent-card.json` 与 `/a2a/jsonrpc`。
- **`AguiConfig`**：仅在 `@Profile("agui")` 下注册 `@AguiAgentId("chat-basic")` 的 `Agent` Bean，通过 `agentscope-agui-spring-boot-starter` 将 POST `/ag-ui/run` 暴露为 AG-UI SSE 端点；路径前缀通过 `agentscope.agui.path-prefix` 控制。
- **`FeishuChannelConfig`**：仅当 `feishu` profile 启用时装配飞书 IM webhook 所需 bean。

## 三、架构约定

1. **配置分层分离**：平台能力（DB、协议 server）通过 Spring profile + `application-{profile}.yml` 控制；具体 agent 行为（prompt、tools、skills）通过业务 YAML（`config/agents.yml`）管理，二者互不耦合。
2. **Bean 按 feature 开关**：分布式状态存储、A2A、AG-UI、飞书 channel、MCP 等均以 `@Profile` 粒度控制是否创建相关 Bean，保证默认启动最小依赖。
3. **敏感值一律走环境变量**：DashScope API Key 以 `${DASHSCOPE_API_KEY:}` 形式写入 YAML，MySQL 连接串同理通过 `MYSQL_*` 环境变量注入，不在仓库中硬编码。
4. **agentId 唯一性约束**：`AgentConfigService` 显式去重，同一 agentId 在多文件中出现时使用第一个定义，并发出警告日志。
5. **RAG knowledge 默认关闭索引**：`auto-index-on-startup=false` 配合 `path` 指定目录，可按需开启。
6. **多协议 endpoint 隔离**：A2A (`a2a` profile)、AG-UI (`agui` profile)、飞书 webhook (`feishu` profile) 各自通过独立 profile 激活，互不影响。

## 四、配置扩展方式

- **新增 agent/Harness 实例**：在 `config/agents.yml` 或 `harness-agents.yml` 追加一段 agent 块（kebab-case agentId），无需改 Java 代码；如需新工具则注册进 `ToolRegistry`。
- **切换持久化后端**：以 `--spring.profiles.active=mysql|postgresql|redis` 启动，各 profile 提供的 `application-*.yml` 注入对应 `DataSource` + `AgentStateStore` Bean。
- **启用 A2A / AG-UI 前端协议**：分别通过 `--spring.profiles.active=a2a|agui` 激活对应配置文件中的段，以及 `A2aServerConfig` / `AguiConfig`。
- **调整知识库参数**：直接修改 `application.yml` 中 `agentscope.knowledge.*` 或通过 `application-test.yml` 在测试 profile 覆盖。

## 五、注意事项

- 由于全局排除了 `DataSourceAutoConfiguration`，在未启用 mysql/postgresql/redis profile 的情况下不存在 `DataSource` Bean，任何尝试注入 `DataSource` 的组件将无法工作。
- `AgentConfigService` 使用的 `LoaderOptions` 未设置白名单类，若 `agents.yml` 包含自定义类字段，SnakeYAML 加载可能触发安全限制。
- profile 组合遵循 Spring Boot 规则：同时激活多个 `application-*.yml` 时同 key 以后加载的 profile 覆盖前者；推荐单一核心 profile + 少量可选 profile 组合使用。