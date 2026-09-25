---
kind: dependency_management
name: Maven + setuptools 双语言依赖管理
category: dependency_management
scope:
    - '**'
source_files:
    - pom.xml
    - agent-harness/setup.py
---

## 1. 使用的系统/方法

本项目是 Java（Spring Boot）+ Python CLI 的混合仓库，依赖管理分为两条线：

- **Java 侧**：使用 Maven（`pom.xml`），继承 `spring-boot-starter-parent:3.5.14`，通过 `<properties>` 集中声明版本，并通过 Spring Boot BOM 管理 Spring 生态依赖。
- **Python 侧**：`agent-harness/` 子模块使用 `setuptools`（`setup.py`）发布为独立包 `cli-anything-agentscope`。

仓库根目录没有 `go.mod`、`package.json`、`requirements.txt`、`poetry.lock`、`Pipfile` 等文件；前端静态资源直接放在 `src/main/resources/static/` 下，未使用 npm/yarn/pnpm 管理。

## 2. 关键文件

- `pom.xml` — 主项目依赖与构建配置。
- `agent-harness/setup.py` — Python CLI 包的依赖声明。
- `.gitignore` — 忽略 `target/`（Maven 输出）。

## 3. 架构与约定

### 3.1 Java 依赖（Maven）

- **父 POM**：继承 `org.springframework.boot:spring-boot-starter-parent:3.5.14`，由父 POM 统一管理 Spring、Reactor、JUnit、Lombok 等版本。
- **版本集中化**：AgentScope 相关依赖统一通过 `<agentscope.version>2.0.3</agentscope.version>` 属性引用，所有 `io.agentscope:*` 依赖均使用该变量，保证 AgentScope 各模块版本一致。
- **模块化依赖分组**：依赖按功能域注释分组（DashScope 模型、Harness、A2A、Channel/IM、AG-UI、分布式 StateStore、JDBC 驱动、测试），便于阅读与维护。
- **可选依赖**：`lombok` 声明为 `<optional>true</optional>`，并在 `spring-boot-maven-plugin` 的 `<excludes>` 中排除，避免打入最终 JAR。
- **运行时作用域**：数据库驱动（如 `mysql-connector-j`）使用 `<scope>runtime</scope>`，仅在运行期加载。
- **测试依赖**：`reactor-test`、`spring-boot-starter-test` 使用 `<scope>test</scope>`。
- **自定义中央仓库**：在 `<repositories>` 中显式声明 `central-official`（`https://repo.maven.apache.org/maven2`），并禁用 snapshot。注释说明该仓库 id 不与全局 mirror（aliyun）冲突，用于绕过阿里云镜像对刚发布的 agentscope 版本的滞后问题。
- **编译器插件**：`maven-compiler-plugin:3.13.0` 显式指定 Lombok annotation processor path（`lombok:1.18.46`），与 Spring Boot BOM 管理的 lombok 版本解耦。
- **覆盖率**：集成 `jacoco-maven-plugin:0.8.14`，要求 BUNDLE 级别指令覆盖率 ≥ 60%，并排除 `config/**`、`model/**`、`*Config*.class`、`*Constants*.class`。

### 3.2 Python 依赖（setuptools）

- `agent-harness/setup.py` 定义包名 `cli-anything-agentscope`，`python_requires=">=3.10"`。
- 运行时依赖：`click>=8.0`、`requests>=2.28`、`prompt_toolkit>=3.0`（仅最低版本约束，无锁文件）。
- 开发依赖通过 `extras_require.dev` 提供 `pytest>=7.0`。
- 通过 `console_scripts` 暴露 `cli-anything-agentscope` 命令行入口。

### 3.3 前端资源

- 前端 JS/CSS 直接以静态资源形式置于 `src/main/resources/static/scripts/`、`src/main/resources/static/styles/`，未使用任何包管理器或构建工具。

## 4. 约定与约束

- **AgentScope 版本一致性**：所有 `io.agentscope.*` 依赖通过 `${agentscope.version}` 引用，强制保持同一版本（当前为 `2.0.3`）。
- **非锁定依赖**：Python 端 `setup.py` 使用宽松的版本下限（`>=x.y`），不存在 `requirements.txt` 或 `poetry.lock` 等锁文件；Java 端由 Spring Boot BOM 管理传递依赖版本，但 `pom.xml` 未包含 `dependencyManagement` 块来覆盖第三方库版本。
- **私有仓库**：未发现 `~/.m2/settings.xml` 中的私有仓库配置、`GOPRIVATE`、NPM registry 等私有源设置；唯一自定义仓库是 Maven Central 官方 URL（`central-official`）。
- **镜像规避策略**：通过独立的 repository id `central-official` 绕过可能存在的 `mirrorOf=central` 的全局镜像（注释明确说明是为了避开阿里云镜像对新发布 agentscope 版本的滞后）。
- **Lombok 不参与打包**：通过 `spring-boot-maven-plugin` 的 `<excludes>` 将 Lombok 从最终 JAR 中剔除。
- **Java 版本**：`java.version`、`maven.compiler.source`、`maven.compiler.target` 均固定为 `17`。
- **覆盖率门槛**：JaCoCo 规则要求整体指令覆盖率不低于 60%（`minimum>0.60`），否则构建失败。
