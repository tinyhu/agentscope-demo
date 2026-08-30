## 1. 总体方案

仓库前端为纯静态页面（Spring Boot 通过 Thymeleaf 模板 `templates/chat.html` 提供），没有使用任何 CSS 框架、构建工具或预处理器。样式体系采用 **原生 CSS**，以 **CSS Custom Properties（CSS 变量/设计令牌）** 为核心，实现“赛博朋克终端”风格的暗色主题。

- 主题入口：`static/styles/base.css` 集中声明全部设计令牌（颜色、发光、间距、字体）。
- 组合入口：`static/styles/chat.css` 作为唯一对外暴露的样式表，通过 `@import` 聚合所有组件模块与基础样式，并通过版本号 `?v=2.x` 做浏览器缓存破缓存。
- 第三方字体与代码高亮通过 `static/vendor/fonts/fonts.css` 和 `vendor/css/highlight.css`、`vendor/js/marked.min.js`、`vendor/js/highlight.min.js` 引入，不经过构建管线。

## 2. 关键文件

- `src/main/resources/templates/chat.html`：Thymeleaf 页面壳，加载 `fonts.css`、`highlight.css`、`styles/chat.css?v=2.9` 以及主入口脚本 `scripts/chat.js?v=2.12`。
- `src/main/resources/static/styles/base.css`：**设计令牌中心**，定义 `--bg-*`、`--neon-*`、`--glow-*`、`--text-*`、`--status-*`、`--border-*`、`--space-*`、`--font-heading`、`--font-body` 等语义化 CSS 变量。
- `src/main/resources/static/styles/chat.css`：应用布局（`.app`、`.app-header`、`.chat-area`、`.chat-input-area`）与各区域样式，`@import` 聚合全部模块。
- `src/main/resources/static/styles/modules/*.css`：按组件拆分的小模块——
  - `header.css`：顶部导航栏
  - `sidebar.css`：左侧 Agent 选择面板
  - `chat.css`：聊天消息区、气泡、thinking、tool-call 块
  - `debug.css`：右侧调试追踪面板
  - `upload.css`：文件上传交互
  - `modal.css`：弹出对话框
  - `utils.css`：通用工具类
- `src/main/resources/static/vendor/fonts/fonts.css`：内嵌 Jetbrains Mono / Orbitron 字体的 `@font-face` 定义（本地 TTF，无需 CDN）。
- `src/main/resources/static/vendor/css/highlight.css`：highlight.js 的代码着色主题。
- `src/main/resources/static/agui.html`：AG-UI 协议的独立页面入口，复用相同的风格体系。

## 3. 架构与约定

### 设计令牌（Design Tokens）
所有视觉语言集中在 `base.css` 的 `:root` 块中，语义化命名：
- **色彩层**：背景色系 (`--bg-void` / `--bg-card` / `--bg-input`)、霓虹强调 (`--neon-cyan` / `--neon-magenta` / `--neon-green` / `--neon-blue` / `--neon-purple` / `--neon-yellow` / `--neon-orange`)、文本层 (`--text-primary` / `--text-secondary` / `--text-muted`)
- **发光效果**：每色一组 `--glow-*` box-shadow/text-shadow 变量，区分常规与强发光级别，避免散落的硬编码阴影值。
- **状态色**：`--status-thinking` / `--status-tool` / `--status-error` / `--status-user` 让消息气泡类型可通过单一变量切换。
- **排版**：`--font-heading = 'Orbitron', sans-serif` 用于标题；`--font-body = 'JetBrains Mono', monospace` 用于正文与代码，统一终端字体感。
- **间距系统**：`--space-xs`..`--space-xl` 四级间距，统一 4/8/12/16/24px 节奏。

### 目录组织与模块拆分
- 顶层 `base.css` 只声明令牌 + 全局重置（box-sizing、scrollbar、markdown `.md-render` 渲染样式、网格背景 `.grid-bg`）。
- 各组件样式放在 `styles/modules/`，由 `styles/chat.css` 通过 `@import url('modules/*.css?v=2.x')` 按需聚合。
- 第三方资源隔离在 `static/vendor/`（字体、CSS 主题、JS 库），避免与业务样式混排。
- HTML 模板仅加载单个入口 `chat.css`，隐藏依赖粒度，便于版本管理（查询串 `?v=2.x`）。

### 主题策略
- **暗色赛博朋克终端**：深空背景（`#12121f`）+ 高饱和霓虹前景，大量 `text-shadow` / `box-shadow` 模拟霓虹辉光。
- **Markdown 输出统一外观**：在 `base.css` 中对 `.md-render h1-h6`、`code`、`pre`、`blockquote`、`table` 进行主题适配，使 LLM 产出的 Markdown 直接渲染为终端风格。
- **代码高亮**：通过 highlight.js（`highlight.min.js` + `highlight.css`）覆盖 `<pre><code>` 的视觉，同时屏蔽内联样式以免冲突。

### 响应式/移动端
页面未使用媒体查询，通过固定宽度侧栏 + Flexbox 弹性主区域自适应窗口大小，未检测到针对移动端的专门断点处理。

### 动画与微交互
通过 CSS `@keyframes` 实现轻量动效：
- `neonFlicker`（霓虹闪烁）
- `logoGlow`（Logo 辉光渐变）
- 滚动条渐变高亮
- hover 时的边框变色、阴影放大、`transform: scale()` 等反馈。

## 4. 惯例与约束

| 规则 | 证据位置 | 说明 |
|---|---|---|
| 所有颜色必须引用 CSS 变量 | `base.css` 定义 + 各处 `var(--neon-*)` | 禁止散落硬编码十六进制色（仅注释中的主题色值除外） |
| 新 UI 元素应放入 `styles/modules/` 并加入入口 `chat.css` 的 `@import` | `chat.css` 头部 import 列表 | 保持单入口样式表，避免重复加载 |
| 新组件样式优先复用 `--space-*` / `--border-*` / `--status-*` | 全 CSS 一致用法 | 间距与状态色遵循 Token |
| 字体引用 `--font-heading` / `--font-body`，不使用裸字体族名 | `chat.css`、`base.css` | 保证全局字体一致 |
| 静态资源版本化需递增查询串参数 | `chat.css?v=2.9`、`chat.js?v=2.12`、`api.js?v=2.5` 等 | 强制通过 `?v=` 控制浏览器缓存 |
| Markdown 内容通过 `.md-render` 容器包裹并使用内置主题 | `base.css` 大量 `.md-render` 规则 | 确保 LLM 输出统一渲染风格 |
| 第三方 JS/CSS 一律放在 `static/vendor/` | `vendor/js/`、`vendor/css/`、`vendor/fonts/` | 业务代码不得耦合外部包路径 |
| SCSS/Tailwind/PostCSS 等非本仓库规范 | 未发现 `.scss`、`tailwind.config.*` | 本项目坚持原生 CSS，无预处理链路 |

总体而言，这是一个**零依赖、手工维护的纯 CSS 主题系统**，靠设计令牌 + 组件化模块拆分来维持视觉一致性，风格定位为「暗色赛博朋克终端」。新增功能应继承既有的 token 体系、把样式下沉到 `styles/modules/`，并通过 `chat.css` 的统一入口发布。