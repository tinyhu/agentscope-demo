## 1. 使用的构建系统与总体方案

本项目采用 **Maven** 作为构建工具，继承自 **Spring Boot `spring-boot-starter-parent` (3.5.14)**，通过 `spring-boot-maven-plugin` 完成编译、测试、可执行 Jar 包（`target/*.jar`）的生成。所有 AgentScope 相关依赖统一由 `${agentscope.version}` 属性集中管理（当前为 2.0.2），Java 版本锁定为 17。

项目没有 Dockerfile、Makefile、CI/CD 流水线（`.github/workflows/` 不存在）、也没有自定义 `build.sh`，因此本仓库只具备**本地 Maven 构建 + Spring Boot 内嵌容器运行**这一套方式，发布产物为单一的 fat JAR。

## 2. 关键构建文件

- `pom.xml`：唯一的构建描述文件，定义 parent、properties、dependencies、plugins；Lombok 标记为 `<optional>true</optional>` 并通过 `excludes` 从最终 jar 中剔除。
- `.java-version`：声明期望 Java 版本，配合 jenv / asdf 等使用。
- `src/main/resources/application.yml` 及多 profile 文件（`application-a2a.yml`、`application-agui.yml`、`application-feishu.yml`、`application-mysql.yml`、`application-postgresql.yml`、`application-redis.yml`）：通过 Spring Profile 控制运行时能力开关，配合代码中 `@Profile` 注解实现可选特性装配。
- `src/test/resources/application-test.yml`：测试 profile 配置。
- `agent-harness/setup.py`：子模块 Python CLI 工具的打包脚本（用于 `agent-harness/cli_anything` 这个独立的 pip 包），与主 Maven 构建解耦。

## 3. 构建架构与约定

### 3.1 依赖版本管理
- 所有 AgentScope 相关坐标统一走 properties 中的 `${agentscope.version}`，包括 starter、core、harness、各类 extensions（dashscope/rag/memory/a2a/channel/agui/redis/mysql/postgresql），确保这些包始终保持一致的小版本号。
- 非 AgentScope 第三方库（POI 5.5.1、PDFBox 3.0.7）直接写死版本。
- MySQL Connector/J 使用 `<scope>runtime</scope>`，仅在运行时需要。

### 3.2 Spring Profile 驱动的功能裁剪
虽然没有 Maven `profiles`，但项目通过 Spring Profile 达到“按需启用”的效果：
- 功能以独立 `application-*.yml` + `@Profile(...)` 类对存在，例如 A2A (`A2aServerConfig`、`A2aClientDemoRunner`)、飞书 (`FeishuChannelConfig`)、AG-UI (`AguiConfig`)、分布式状态存储 (`DistributedStateStoreConfig`)。
- 启动时通过 `--spring.profiles.active=a2a,mysql,redis` 等参数选择组合，从而在运行时加载对应 bean，而非在构建期裁剪。

### 3.3 打包与运行约定
- `mvn clean compile`：编译源码（不含测试资源）。`AGENTS.md` 将之列为日常开发命令。
- `mvn spring-boot:run`：带 devtools 的交互式运行方式。
- `mvn package`：生成 `target/agentscope-demo-1.0-SNAPSHOT.jar`，可用 `java -jar` 启动，或通过 `mvn exec:java`。
- 应用监听默认端口 8080。
- `DASHSCOPE_API_KEY` 环境变量注入模型密钥；也支持 `--agentscope.model.dashscope.api-key=...` 命令行参数覆盖。

### 3.4 子模块与外部构建
- `agent-harness/` 是一个独立的 Python 项目，维护自己的 `setup.py`，可单独 `pip install -e .` 安装其 CLI，不依赖 Maven。
- 前端静态资源（`static/scripts`、`static/styles`、`templates/chat.html`、`static/agui.html`）不进行任何构建步骤，直接由 Spring Boot Thymeleaf/WebMvc 作为静态资源分发到最终 jar。

### 3.5 版本与发布
- 版本号固定在 `1.0-SNAPSHOT`，未在 POM 中使用 maven-release-plugin 或 GitHub Actions Release，仓库内未发现 CI 自动化发布流程。
- 实际变更频率体现在 commit message 的 `chore(deps)` 标签上（如 agentscope 版本升级），但没有配套自动化脚本。

## 4. 约束与规则

| 规则 | 证据来源 |
|---|---|
| Java 编译器源/目标版本固定为 17 | `pom.xml` 中 `maven.compiler.source/target = 17` |
| 必须设置 `DASHSCOPE_API_KEY` 环境变量或 `agentscope.model.dashscope.api-key` 参数后才能跑通模型调用 | `AGENTS.md` 明确说明，缺少该变量会导致 LLM 初始化失败 |
| Lombok 不参与打包产物（仅编译期） | `pom.xml` 的 `lombok` 依赖标记 `optional=true` 且在 `spring-boot-maven-plugin.excludes` 中显式 exclude |
| 额外功能按 Spring Profile 启用，默认不包含 A2A/AG-UI/飞书/MySQL 等 | 代码类上的 `@Profile("a2a"|"agui"|"feishu"|"mysql"|"postgresql"|"redis")` 与对应 `application-*.yml` 成对出现 |
| 子 Python CLI 模块需通过 `setup.py` 独立安装，不随主 Maven 构建输出 | `agent-harness/setup.py` 存在且被独立引用 |
| 无 Docker/Maven 发布/CI 流水线 | 搜索根目录无 `Dockerfile*`、`.github/*`、`Makefile*`、`*.sh` 等构建脚本 |
