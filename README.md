<div align="center">
  <h2>DoVideoAI</h2>
  
  <p>
    <a href="https://github.com/Xiaoc7r/DOVideo-AI/stargazers"><img src="https://img.shields.io/github/stars/Xiaoc7r/DOVideo-AI?style=flat-square" alt="GitHub Stars"></a>
    <img src="https://img.shields.io/badge/Java-21-E76F00?style=flat-square" alt="Java 21">
    <img src="https://img.shields.io/badge/Spring%20Boot-3.5.9-6DB33F?style=flat-square" alt="Spring Boot 3.5.9">
    <img src="https://img.shields.io/badge/Vue-3-42B883?style=flat-square" alt="Vue 3">
    <img src="https://img.shields.io/badge/MySQL-8-4479A1?style=flat-square" alt="MySQL 8">
    <img src="https://img.shields.io/badge/Redis-7-DC382D?style=flat-square" alt="Redis 7">
    <img src="https://img.shields.io/badge/RocketMQ-5.3.4-D77310?style=flat-square" alt="RocketMQ 5.3.4">
    <img src="https://img.shields.io/badge/LangChain4j-Agent-20232A?style=flat-square" alt="LangChain4j">
    <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="MIT License"></a>
  </p>
</div>

<div align="center">

面向长视频内容理解的 <strong>Video Agent</strong>。

本仓库基于 [Xiaoc7r/DOVideo-AI](https://github.com/Xiaoc7r/DOVideo-AI) 二次开发，保留原项目与 MIT 许可证，新增 <strong>AIGC 视频创作与任务编排平台</strong>：DeepSeek 脚本与分镜、文生／图生视频、镜头生成版本、局部重生成、FFmpeg 成片及质量／成本评测。完整功能、独立启动和验收见 [创作平台说明](docs/creator-platform.md)。

致力于将长视频转化为可检索、可追溯、可继续追问的结构化知识。

<div align="center">

[![Star History Chart](https://star-history.dera.page/svg?repos=Xiaoc7r/DOVideo-AI&type=Date)](https://star-history.dera.page/#Xiaoc7r/DOVideo-AI&Date)

</div>


</div>

## AIGC 创作平台与真实验收

新增链路：创作需求 → DeepSeek 官方脚本与分镜 → 人工确认 → Seedance 文生／图生视频 → 私有产物归档 → 镜头版本选择 → 带字幕成片。付费模型仅保留 DeepSeek 与 Seedance，免费 Mock 用于演示和测试；默认启动关闭付费调用，密钥和本地数据库不随仓库发布。

Seedance Mini 已验证 1 次真实文生视频、3 次真实图生视频，以及 15.146 秒带字幕成片。后续代码审查补齐任务调度、超时恢复、跨分镜版本复用、本地草稿保护、项目搜索与归档、参考素材清理。模型精简与历史兼容见 [DeepSeek 与 Seedance 配置](docs/model-providers.md)，修复验收见 [修复与扩展记录](docs/review-2026-10-04.md)。历史任务与验收记录保留，已停用的服务不再提交或恢复。

![AIGC 视频创作工作台](docs/acceptance/seedance-real-workbench.jpg)

[启动与演示说明](docs/creator-platform.md) · [项目库与素材清理](docs/project-library.md) · [Seedance 接入说明](docs/seedance-video.md) · [真实成片 MP4](docs/acceptance/seedance-film.mp4) · [脱敏验收记录](docs/acceptance/seedance-real.json)

## 原项目预览

**登录与注册**

![DoVideoAI 登录与注册](docs/images/login-register.png)

**视频工作台**

![DoVideoAI 视频工作台](docs/images/video-library.png)

**Agent 目标输入**

![DoVideoAI Agent 目标输入](docs/images/agent-compose.png)

**Agent 分析结果**

<img width="2886" height="1656" alt="b89cf519f7189cf823507d5c17b0d88d" src="https://github.com/user-attachments/assets/8bfeed0e-28df-4527-86bf-e549f5516dcc" />

<img width="1776" height="1708" alt="a52abccc6447591c6f9a66ad948c5709" src="https://github.com/user-attachments/assets/9e04ecca-a2a5-4d59-89d7-8f4329858070" />



用户完成登录后，可以上传视频并在工作台管理解析任务；选择视频并输入分析目标后，可以手动选择分析模式，也可以交给 Agent 自动判断。工作台会展示结构化结论、时间戳证据、执行计划、阶段轨迹与质量评估，并支持基于同一视频继续追问；追问历史会通过 Redis 保存，模型可结合多轮对话上下文作答。

## 核心功能

长视频处理天然是**长耗时、高资源消耗、外部调用成本敏感**的场景。DoVideoAI 的设计都围绕这一背景展开，可以概括为四层能力。

### 🎬 可靠的视频任务链路

> 把大文件上传与耗时的视频解析从请求主链路中剥离，提交即返回，不阻塞、不重复烧钱。

- **分片上传 + 断点续传** — 前端按 5 MB 分片，Redis 记录已完成分片，MinIO 保存合并后的视频，弱网中断后可从断点续传。
- **异步削峰** — RocketMQ 将视频解析移出请求线程，提交后立即返回任务 ID；Redisson 按「内容指纹 + 分析目标」加锁，拦截并发与重复消费。
- **链接异步入库** — 粘贴视频链接后立即返回任务 ID，yt-dlp 在独立线程池中拉取（每用户最多 2 个并发），前端在后台轮询并在完成后通知；下载期间不占用请求线程，也不阻塞其他上传。
- **成本护栏** — 用户级与全局令牌桶限制 AI 请求速率；ASR 与模型调用采用有限次数的指数退避重试，兜底第三方网络抖动。

### 🧩 时序多模态 VideoContext

> 把语音、画面文字与时间戳融合成一份可检索、可校验的统一上下文。

- **双分支抽取** — FFmpeg 将音频按 60 秒切片，同时通过场景变化检测抽取关键帧，并以 30 秒保底采样避免遗漏静态板书。
- **并行与容错** — ASR 与 OCR 使用独立有界线程池并行执行；相邻画面通过感知哈希去重，单路失败时仍保留另一条有效信息。
- **统一结构** — 语音区间、OCR 文本、关键帧与时间戳被合并为统一的 `VideoSegment`，后续检索与校验不再依赖底层模型格式。

```text
[02:00 - 03:00]
ASR      接下来讲解二叉树的前序遍历
OCR      前序遍历：根节点、左子树、右子树
Evidence frame_000125.jpg
```

### 🔁 有证据约束的 AgentLoop

> 每条结论都必须绑定可在原始视频中核验的时间戳证据，拒绝模型自由发挥。

- **角色分工** — Planner 将用户目标拆成可执行任务，Executor 生成固定结构的结论、证据与建议。
- **闭环校验** — Critic 检查目标覆盖、结构完整性与时间戳证据；不通过时依据缺失内容和时间范围定向重新检索。
- **自动模式路由** — 根据用户目标自动选择通用、学习、审查或创作模式；路由不可用时回退通用模式，不阻断分析任务。
- **四类结构化产物** — 通用模式生成结论与建议，学习模式生成大纲、自测题与易错点，审查模式定位逻辑漏洞与存疑结论，创作模式提取爆点、标题与口播脚本。
- **成本可控** — AgentLoop 最多执行两轮，既允许定向修正，也通过轮次上限约束延迟与 Token 成本。

### 🔍 长视频检索与断点恢复

> 面向数小时长视频的分段检索，以及分阶段可恢复的任务状态机。

- **混合检索** — 每 5 分钟生成片段摘要、关键词与 Embedding，通过关键词匹配与 Qdrant 语义召回选出 TopK 原始证据。
- **优雅降级** — Qdrant 或 Embedding 服务不可用时，退化到本地关键词与已有向量排序，不阻断主分析链路。
- **断点恢复** — Checkpoint 以 MySQL 为恢复真源、Redis 为热缓存，持久化 `VideoContext`、分块、计划、Critic 状态与最终结果。
- **状态可观测** — 前端通过 SSE 接收任务阶段；失败消息写入独立失败主题与失败任务表，可由管理接口重新投递。

## 系统流程

```mermaid
sequenceDiagram
    autonumber
    actor User as 用户
    participant Web as Vue 工作台
    participant API as Spring Boot API
    participant MQ as RocketMQ
    participant Worker as 分析消费者
    participant Context as VideoContext
    participant Search as Qdrant 检索
    participant Agent as AgentLoop
    participant State as MySQL + Redis

    User->>Web: 上传视频并填写分析目标
    Web->>API: 分片上传与合并
    API->>MQ: 投递视频分析任务
    API-->>Web: 返回 202 Accepted
    MQ->>Worker: 异步消费
    Worker->>State: 查询幂等结果与 Checkpoint

    alt 已存在可恢复结果
        State-->>Worker: 返回最近成功阶段
    else 首次解析
        par 语音分支
            Worker->>Context: FFmpeg 分段 + ASR
        and 视觉分支
            Worker->>Context: 关键帧抽取 + OCR
        end
        Context->>State: 保存时序多模态上下文
    end

    Worker->>Search: 摘要、关键词与 Embedding 混合检索
    Search-->>Agent: 返回相关原始证据
    loop Critic 未通过且未达到两轮
        Agent->>Agent: Planner -> Executor -> Critic
        Agent->>Search: 按反馈定向补充证据
    end
    Agent->>State: 保存结构化结果与 Checkpoint
    Worker-->>Web: SSE 推送阶段与最终结果
    Web-->>User: 展示结论、证据与后续追问
```

## 技术栈

| 层次 | 技术 | 用途 |
| :--- | :--- | :--- |
| Web | Vue 3、Vite、SSE、Marked | 上传、Agent 工作台、实时进度与安全 Markdown 展示 |
| API | Java 21、Spring Boot 3.5.9、Undertow、MyBatis-Plus | 鉴权、媒体管理、任务编排与 REST API |
| 异步与缓存 | RocketMQ 5.3.4、Redis 7.4、Redisson | 异步削峰、状态缓存、限流、锁与消费幂等 |
| 数据与存储 | MySQL 8、MinIO、Qdrant | 业务数据、视频对象、Checkpoint 与向量检索 |
| 视频与 AI | FFmpeg、Tesseract、LangChain4j、DeepSeek、Seedance | 分镜、视频生成、媒体处理；原分析模块 ASR / Embedding 需单独配置，默认关闭 |
| 部署 | Docker Compose | 本地中间件编排 |

## 本地运行

新增文生视频／图生视频任务接口与 Vue 生成入口，普通启动器默认使用本地 mock。已支持模型能力配置、私有参考图片、任务追溯、幂等恢复和 MinIO 归档；接口见 [视频生成接入说明](docs/video-generation.md)，分镜、镜头版本与成片功能见 [创作平台说明](docs/creator-platform.md)。[Seedance Mini](docs/seedance-video.md) 已完成 1 次真实文生、3 次真实图生及带字幕多镜头成片验收；117 项后端回归通过。实际费用与人工评分保留待核对状态。

已新增「脚本与分镜」入口（`/?storyboard`）：产品需求创建本地模板草稿，支持镜头编辑、历史修订和人工确认，确认不会启动视频生成。接口、限制和验收见 [脚本与分镜说明](docs/storyboards.md)。

当前付费模型只保留 DeepSeek 官方与 Seedance；精简后 153 项后端、77 项前端、真实中间件 21 个检查和 Windows 启动测试通过，历史视频及台账保留。配置与范围见 [模型配置](docs/model-providers.md)，本轮 [脱敏验收记录](docs/acceptance/model-provider-simplification.json) 不包含新增付费调用。

确认分镜后可在「镜头生成」区域配置项目额度、批量提交、查看归档视频或局部重生成；生成版本绑定不可变分镜，查询与保存恢复沿用原模型任务。默认 Mock、零付费调用，详见 [镜头生成与预算台账](docs/shot-generation.md)。

已接入已完成镜头的版本选择、独立 FFmpeg 合成、中文字幕、原声／静音归一、私有成片预览与下载、固定提示词对比样例、人工评分和 JSON / CSV 评测导出。独立应用启动：`scripts/start-aigc.ps1 -JdkHome <JDK21+目录>`，默认地址 `http://127.0.0.1:9095/?storyboard`，付费调用保持关闭。

Windows 可运行 [真实中间件验收脚本](scripts/test-generation-infrastructure.ps1)，自动创建临时 MySQL／Redis／MinIO 并验证生成闭环及应用重启。MinIO 镜像由 `infrastructure/minio/Dockerfile` 从官方固定版本源码构建，首次启动会进行编译。

### 环境要求

| 组件 | 要求 | 说明 |
| :--- | :--- | :--- |
| JDK | 21 | 后端运行环境 |
| Node.js | 22 | Vue 与 Vite 构建环境 |
| Docker | Compose v2 | 启动 MySQL、Redis、MinIO、Qdrant 与 RocketMQ |
| FFmpeg | 可在终端调用 | 音频切分与关键帧抽取 |
| Tesseract | 安装 `chi_sim` 与 `eng` | 中英文关键帧 OCR |
| yt-dlp | 可选 | 仅解析在线视频链接时需要 |

建议先确认命令均可用：

```bash
java -version
node --version
docker compose version
ffmpeg -version
tesseract --version
```

### 1. 准备配置

```bash
cp .env.example .env
```

编辑 `.env`，至少替换数据库、Redis、MinIO、Qdrant 的示例密码；运行原视频理解应用时设置 `DEEPSEEK_API_KEY`。全新数据库中 `DB_USERNAME` 与 `MYSQL_APP_USER` 应保持一致；`MYSQL_ROOT_PASSWORD` 仅供数据库初始化使用。密钥只保存在本地 `.env`，不要提交到仓库。

原分析 LLM 改用 DeepSeek 官方地址和配置模型 `deepseek-flash`。ASR 与 Embedding 默认关闭，各自使用独立服务地址和密钥，不继承 DeepSeek 凭据；不配置 Embedding 时保留关键词检索降级，未配置 ASR 时不能提供语音转写。独立创作平台不加载这些分析服务。`LLM_TIMEOUT_SECONDS` 默认是 `300`；模型或超时配置变更后需要重启后端。

### 2. 启动中间件

```bash
./scripts/dev-up.sh
```

脚本会检查本机命令与版本、校验 Compose 配置，并等待 MySQL、Redis、MinIO、Qdrant 和 RocketMQ 启动。中间件与后端默认只监听 `127.0.0.1`，不会直接暴露到局域网；远程部署时再显式修改 `SERVER_ADDRESS` 并配置反向代理。

部署在 Nginx 等可信反向代理之后时，请设置 `SERVER_FORWARD_HEADERS_STRATEGY=native`，并确保代理会设置 `X-Forwarded-For`；否则所有用户共用代理 IP，10 分钟内累计 30 次登录失败就会让所有人被限流（可用 `AUTH_MAX_LOGIN_FAILURES_PER_IP` 调整阈值）。

### 3. 启动后端

```bash
set -a
source .env
set +a

cd server
./mvnw spring-boot:run
```

后端默认地址为 `http://localhost:9090`，启动时会初始化项目所需数据表。另开终端确认服务可用：

```bash
curl http://localhost:9090/health
```

成功时返回 `{"code":0,"message":"success","data":"UP"}`。

### 4. 启动前端

```bash
set -a
source .env
set +a

cd client
npm ci
npm run dev
```

浏览器访问 `http://localhost:5173`。开发环境默认通过 Vite 代理访问后端；后端地址不同时修改 `VITE_DEV_PROXY_TARGET`，前后端分开部署时再设置 `VITE_API_BASE_URL`。

只查看前端 Agent 工作台时，可以打开 `http://localhost:5173/?demo`。Demo 模式使用内置示例数据，不依赖后端服务。

### 常见问题

| 现象 | 处理方式 |
| :--- | :--- |
| 后端无法连接 MySQL 或 Redis | 运行 `docker compose --env-file .env ps`，确认服务健康且 `.env` 密码一致 |
| 页面提示无法连接后端 | 先访问 `/health`；再检查 `VITE_DEV_PROXY_TARGET` 或 `VITE_API_BASE_URL` |
| 视频解析提示命令不存在 | 确认 `ffmpeg`、`tesseract` 可在终端执行，必要时配置 `FFMPEG_DIR`、`OCR_COMMAND` |
| AI 接口返回 401 或模型不可用 | 分镜检查 `STORYBOARD_API_KEY`，视频检查 `SEEDANCE_API_KEY`；原分析检查 `DEEPSEEK_API_KEY`，修改后重启后端 |
| Maven 提示 `maven-default-http-blocker` | 在 `server` 目录执行 `./mvnw -s .mvn/central-settings.xml spring-boot:run`，临时绕过失效的用户级镜像 |

停止本地中间件：

```bash
docker compose --env-file .env down
```

该命令不会删除 `mysql/data`、`redis/data`、`minio/data`、`qdrant/data` 或 RocketMQ 命名卷。需要完全重置时请先备份，再使用 `docker compose --env-file .env down --volumes` 并手动清理这些数据目录。

## 目录结构

```text
DoVideoAI
├── client/              # Vue 3 工作台
├── server/              # Spring Boot API 与 Video Agent
├── rocketmq/            # Broker 配置
├── docker-compose.yml   # 中间件编排
└── .env.example         # 本地配置模板
```

## License

本项目基于 [MIT License](LICENSE) 开源。
