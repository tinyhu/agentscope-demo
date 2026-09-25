---
kind: build_system
name: Maven + Spring Boot 构建与测试体系
category: build_system
scope:
    - '**'
source_files:
    - pom.xml
    - agent-harness/setup.py
    - src/main/resources/application.yml
    - src/main/resources/application-a2a.yml
    - src/main/resources/application-agui.yml
    - src/main/resources/application-feishu.yml
    - src/main/resources/application-mysql.yml
    - src/main/resources/application-postgresql.yml
    - src/main/resources/application-redis.yml
    - src/test/resources/application-test.yml
---

## 1. 构建系统与工具链

项目采用 **Maven**（`pom.xml`）作为唯一构建系统，基于 `spring-boot-starter-parent:3.5.14` 父 POM 管理依赖与插件。Java 版本锁定为 **17**（`java.version`、`maven.compiler.source/target` 均为 17），源码编码为 UTF-8。

核心依赖通过 `<properties>` 中的 `${agentscope.version}`（当前 `2.0.3`）集中声明，所有 AgentScope 相关模块（core、harness、dashscope、a2a-server/client、agui、channel-feishu、redis/mysql/postgresql、rag-simple、memory-bailian）统一使用该变量，保证版本一致。

## 2. 关键构建文件

- `pom.xml`：唯一的构建描述符，定义依赖、仓库、插件与覆盖率规则。
- `agent-harness/setup.py`：Python 侧 harness CLI 的独立打包入口（与 Maven 主工程解耦）。
- `src/main/resources/application*.yml`：多环境 profile 配置（`application.yml`、`application-a2a.yml`、`application-agui.yml`、`application-feishu.yml`、`application-mysql.yml`、`application-postgresql.yml`、`application-redis.yml`），通过 Spring Profile 切换后端能力。

## 3. 插件与生命周期

### 3.1 Spring Boot Maven Plugin
- 使用 `spring-boot-maven-plugin` 生成可执行 JAR。
- 通过 `<excludes>` 排除 Lombok，避免将 Lombok 打入最终产物。

### 3.2 Maven Compiler Plugin
- 显式声明 `maven-compiler-plugin:3.13.0`，source/target=17。
- 通过 `<annotationProcessorPaths>` 引入 `lombok:1.18.46` 注解处理器。

### 3.3 JaCoCo 代码覆盖率
- 版本 `0.8.14`，绑定到 `test` 阶段：`prepare-agent` 在测试前注入 agent，`report` 在测试后生成报告。
- 排除规则：`**/config/**`、`**/model/**`、`**/*Config*.class`、`**/*Constants*.class`。
- **强制规则**：BUNDLE 级别 INSTRUCTION COVEREDRATIO ≥ 0.60（60%），低于此阈值构建失败。

## 4. 依赖管理与仓库

- 官方 Maven Central 镜像被覆盖（注释说明阿里云 mirrorOf=central 会滞后于刚发布的 agentscope 版本），因此额外声明了 `central-official` 仓库（`repo.maven.apache.org/maven2`），并禁用 snapshots。
- 数据库驱动（如 `mysql-connector-j`）使用 `scope=runtime`，仅在运行时加载。
- 可选依赖（如 Lombok）标记为 `<optional>true</optional>`。

## 5. 测试体系

- 测试框架：`spring-boot-starter-test` + `reactor-test`。
- 测试源码位于 `src/test/java/com/skloda/agentscope/`，按包结构组织（agent、blackboard、controller、harness、middleware、runtime、tool 等）。
- 测试资源：`src/test/resources/application-test.yml`。
- 测试运行由 Maven `test` 阶段触发，同时触发 JaCoCo 覆盖率收集。

## 6. 环境与 Profile 约定

应用通过 Spring Profile 组合不同能力：
- `a2a`：启用 A2A Server/Client 协议。
- `agui`：启用 AG-UI 前端交互。
- `feishu`：启用飞书 Channel。
- `mysql` / `postgresql` / `redis`：启用分布式 AgentStateStore 后端。

这些 profile 对应独立的 `application-*.yml` 配置文件，由 `@Profile` 或命令行参数激活。

## 7. 发布产物

- 标准 Maven 构建输出：`target/` 目录下的 JAR（由 Spring Boot Maven Plugin 生成）。
- Python 侧 harness 通过 `agent-harness/setup.py` 单独分发。
- 未发现 Dockerfile、Makefile、CI 流水线文件或 release 脚本；构建与发布流程未在仓库中体现。

## 8. 约束与规则

- Java 编译目标必须为 17（由 `maven.compiler.source/target` 和 parent POM 共同约束）。
- 覆盖率门槛 ≥ 60%（JaCoCo rules 强制执行，不达标则 `mvn test` 失败）。
- Lombok 不得进入最终 JAR（`spring-boot-maven-plugin` excludes 强制）。
- AgentScope 各扩展模块版本号必须与 `agentscope.version` 保持一致（通过单一 property 管理）。
- 新增数据库驱动需使用 `scope=runtime`，避免污染 compile classpath。