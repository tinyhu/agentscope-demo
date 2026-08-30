## 1. 系统/工具
- **构建与依赖管理器**：Maven（`pom.xml`），继承 `spring-boot-starter-parent:3.5.14`，使用其默认的 BOM 统一管理 Spring 生态依赖版本。
- **语言栈**：主体为 Java + Spring Boot（`java.version=17`）；子模块 `agent-harness/cli_anything/` 是独立的 Python Click CLI 工具包，用 `setup.py` 声明依赖。
- **无前端包管**：静态资源（JS/CSS/fonts/vendor 插件）以源码形式放在 `src/main/resources/static/`，不存在 `package.json`、`yarn.lock` 或 `go.mod`。
- **无私有仓库**：未发现 `settings.xml`、GitHub Packages/Nexus 等配置，依赖均从 Central 拉取。

## 2. 关键文件
- `pom.xml`：工程根级 POM，集中声明所有依赖及版本属性。
- `agent-harness/setup.py`：Python CLI 包的 `install_requires`/`extras_require` 声明。
- `.java-version`：声明 JDK 17。
- `docs/superpowers/plans/...`：变更记录中可看到 agentscope RC → GA 升级的依赖迁移过程（如 2026-06-09、2026-07-10）。.

## 3. 架构与约定
- **全局统一版本号**：通过 `<properties><agentscope.version>2.0.2</agentscope.version></properties>` 集中管理整个 AgentScope 套件版本（core、harness、model-dashscope、rag-simple、memory-bailian、a2a-server/client、channel-common/feishu、agui、redis/mysql/postgresql 扩展等全部引用同一 `${agentscope.version}`），保证框架内部一致性。
- **按能力拆分 starter 模块**：AgentScope 2.0 GA 采用模块化 starter（`agentscope-extensions-*`、`agentscope-*-spring-boot-starter`、`agentscope-agui-spring-boot-starter`），在 `pom.xml` 中以显式 `<dependency>` 逐个引入，而非单一 monolithic jar。
- **可选功能按 profile + runtime scope 隔离**：
  - JDBC/数据库驱动 (`mysql-connector-j`) 仅声明 `<scope>runtime</scope>`，结合 Spring Boot `application-{profile}.yml`（`application-mysql.yml`、`application-postgresql.yml`、`application-redis.yml`）按需激活。
  - A2A、飞书 Channel、AG-UI、分布式 State Store 等能力的 agent 级启用通过 Spring `@Profile` 条件装配，依赖虽全量声明但运行时仅加载所需。
- **构建期排除**：Spring Boot Maven Plugin 配置 `<excludes>` 把 `lombok` 从最终 fat jar 中剔除。
- **Python 子模块**：`agent-harness/cli_anything/` 作为独立 setuptools 包发布，声明 `python_requires>=3.10` 及 `click>=8.0, requests>=2.28, prompt_toolkit>=3.0`，以 `console_scripts` 入口 `cli-anything-agentscope` 暴露命令行。

## 4. 约定与约束
- **单一属性来源**：所有 AgentScope 相关 artifact 必须且只能通过 `${agentscope.version}` 引入，新增能力时应复制既有 starter 模式并复用该属性，避免散落的硬编码版本。
- **Starter 命名空间**：第三方库依赖位于 `io.agentscope.*` 下的扩展一律视为“按需引入”，对应 Spring profile 已存在的应保留当前粒度，新协议/传输也应以同风格 `-extensions-*` starter 新增条目到 `pom.xml`。
- **JDK 与 compiler 设置**：由 `spring-boot-starter-parent` + `<maven.compiler.source/target>17</maven.compiler.target>` 双源保障，不应再在子模块覆盖。
- **Python CLI 保持独立**：CLI 工具的依赖仅在 `agent-harness/setup.py` 中维护，不得复制到主项目 `pom.xml`；若需要与 Java 端协同，应以 HTTP 方式调用而非共享类路径。
- **前端依赖直接入库**：`src/main/resources/static/vendor/js/` 下的高亮/Markdown 渲染脚本（`highlight.min.js`、`marked.min.js`）以及字体以静态资源形式提交版本快照，不通过 npm/yarn/pnpm 包管理——因此也不存在 lockfile 概念。
- **版本升级依据**：依赖演进遵循 docs 中的 plans/specs 记录（如 `2026-06-12-phase1-foundation-upgrade.md`、`2026-07-10-agentscope-rc3-to-ga-migration-design.md`），升级后需同步清理过时 API（见 AGENTS.md “过时 API 全面清理” 要求）。
