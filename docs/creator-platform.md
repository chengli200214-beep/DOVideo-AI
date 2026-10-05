# AIGC 视频创作与任务编排平台

2026-10-05 的复审修复覆盖扩展异步下载、分镜与镜头并发、上传完成持久化、跨标签账号同步、Seedance 下载配置门禁及参考图异常清理，详见 [复审修复记录](review-2026-10-05.md)。

> 最新代码更新（2026-10-04）：已修复任务调度、超时恢复、跨修订镜头复用及草稿保护，并新增项目库与参考素材清理，详见 [修复与扩展记录](review-2026-10-04.md)。2026-10-02 的真实验收已完成 Seedance Mini 1 次文生、3 次图生、私有归档及 15.146 秒带字幕成片，证据见 [Seedance 接入与验收](seedance-video.md)、[成片](acceptance/seedance-film.mp4) 和 [脱敏验收记录](acceptance/seedance-real.json)。历史授权不随源码发布，普通启动仍关闭付费调用。按用户要求，实际账单核对、正式人工评分及历史 SiliconFlow 三笔未知提交核实均移出验收范围；费用未知、未评分和旧任务状态保留原记录。


基于 DOVideo-AI 二次开发，面向产品介绍类短视频，工作方向为模型服务接入、文生／图生视频任务编排及后端工程。原开源视频理解能力保留；独立创作应用无需加载其 LLM / ASR / RocketMQ / Qdrant 服务。

## 完整功能

创作需求 → 本地模板或单独授权的 DeepSeek 脚本与分镜 → 编辑和人工确认 → 镜头生成与局部重生成 → 选择已归档镜头版本 → FFmpeg 合成 → 私有成片预览及下载 → 人工质量评分与成本报告。

已提供 Seedance 异步视频适配器及 DeepSeek 官方分镜适配器；免费 Mock 用于演示和测试、模式能力配置、私有参考图、幂等提交、租约恢复、未知提交核对、任务事件及产物校验。普通启动器默认使用 Mock 和本地模板并关闭付费调用。模板及本次文字分镜适配器不理解图片，配音与音乐服务未接入。详见 [DeepSeek 分镜与调用费用](deepseek-storyboards.md)。

项目库支持搜索、游标分页、归档与恢复；素材库支持复用已上传的私有参考图，删除未引用图片并追踪对象清理状态。已有项目、历史分镜和任务引用的素材会保留。分镜草稿按账号和项目保存在当前浏览器；只改字幕、标题或口播时可以复用兼容的旧镜头视频，修改提示词等生成参数后只需重生成对应镜头。操作及 API 见 [项目库与素材库](project-library.md)，存储升级检查见 [MinIO 隐私说明](storage-privacy.md)。

## Windows 一键启动

需要已安装的 Docker Desktop、JDK 21+ 和 Node.js 22+。启动脚本会检查本机 Docker；完全退出时先备份已知残留 socket 目录，再启动引擎。已运行时复用现有引擎。下例使用 `C:\Projects\DOVideo-AI`，请按实际克隆位置调整。

若 Docker Desktop 已卡在 `dockerInference` 或 Secrets Engine `engine.sock` 错误窗口，先退出 Desktop，再运行项目启动脚本；脚本不会强行结束运行中的 Docker。完整行为见 [启动恢复说明](docker-recovery.md)。该恢复只备份指定临时目录，不重置项目数据库。

```powershell
cd C:\Projects\DOVideo-AI
# 首次没有 FFmpeg 时执行；从官方站点所列 Windows 分发方下载并校验 SHA-256。
.\scripts\install-ffmpeg.ps1
.\scripts\start-aigc.ps1 -JdkHome 'C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot'
```

打开 `http://127.0.0.1:9095/?storyboard`，注册自己的本地账号。启动脚本会构建前端，使用 Maven `aigc-app` 配置把静态文件打包进独立 Spring Boot 应用；浏览器与 API 同源，无需另开 Vite。

脚本使用独立 `dovideo-aigc-local` 容器项目，MySQL 3308、Redis 6380、MinIO 9002 / 控制台 9003，均仅监听回环地址。随机本地凭据保存于忽略版本控制的 `.local/aigc.env`，不覆盖原 `.env`；MySQL、Redis、MinIO 数据保存在命名卷中，重启后继续使用。日志及 PID 文件位于 `.local/`，脚本重复执行会识别已启动的本项目进程。

```powershell
# 只停止本项目 Java 进程；可选同时停容器，均不删除持久化数据。
.\scripts\stop-aigc.ps1 -StopInfrastructure
```

启动脚本强制 `provider=mock`、付费关闭、授权额度为 0，即使父进程有其他配置也不会开放付费模型。原视频理解应用仍可按原 README 启动，不在此脚本中运行。

付费模型仅保留 DeepSeek 官方与 Seedance。SiliconFlow 适配器和旧恢复参数已移除；历史产物仍可查看，旧任务不会继续执行。当前配置及原分析服务的独立密钥说明见 [模型配置](model-providers.md)。

## 演示步骤

1. 注册／登录，创建产品介绍需求，填写产品名称、卖点、风格、2–8 个镜头和期望总时长。
2. 可上传私有 PNG / JPEG 参考图。检查脚本、字幕、提示词和镜头顺序，保存并确认稿。
3. 配置项目版本额度，例如 12，Mock 预算上限为 0。提交镜头，等到状态为「已完成」；可查看归档视频，也可仅重生成某个已结束镜头。
4. 在「成片与效果评测」中为每个镜头选择一项与当前分镜生成参数兼容的已归档版本，保存镜头选择；兼容版本可以来自历史修订。勾选字幕后提交合成，字幕使用当前确认稿。
5. 完成后预览、下载 MP4，并查看镜头 SHA-256、生成版本和合成事件。合成输入不可变；合成失败只重试原合成任务。
6. 人工对完成的镜头按内容符合度、动作稳定性、主体一致性各打 1–5 分，标记可用并记录依据。导出 JSON 或 CSV；没有评分时显示未评价，不自动打分。

Mock 成片只验证流程、媒体处理和权限，不是实际 AI 生成效果。口播文字只保留在分镜和输入快照中，不自动转为语音。

## 合成接口与恢复

以下路径前缀为 `/generation/projects/{id}`，均需登录且属于当前用户：

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| GET | `/films` | 最近 50 个成片任务及最新镜头选择 |
| POST | `/selections` | `{revision,expectedSelectionVersion,versionIds}`，每个镜头恰好一个与当前分镜兼容的已归档版本 |
| POST | `/films` | `{selectionVersion,burnCaptions}` + Idempotency-Key，创建不可变合成任务 |
| GET | `/films/{job}` | 合成参数快照及事件 |
| POST | `/films/{job}/retry` | 仅恢复失败的原合成任务 |
| GET | `/films/{job}/artifact` | 私有成片的短时签名地址 |
| GET | `/films/{job}/download` | 带登录校验的 MP4 下载流 |
| GET | `/evaluation-report` | 全部镜头生成版本的质量及成本 JSON |
| GET | `/evaluation-report.csv` | CSV 导出，对用户文本防公式注入 |
| POST | `/reviews/{versionId}` | `{expectedReviewVersion,contentScore,motionScore,consistencyScore,usable,notes}` |

另外提供 `/generation/runtime` 检查 FFmpeg、ffprobe 和 CJK 字体，`/generation/evaluation-cases` 读取固定提示词样例。

V8 保存不可变镜头选择、合成任务、合成事件和人工评分历史。创建时持有项目行锁，防止与编辑、确认或选择更新竞争；相同合成请求 key 重放原任务。项目最多 50 个合成任务，失败任务可原地恢复。

worker 使用 25 分钟租约，渲染有 15 分钟总期限及每次进程 90 秒期限。失去租约的旧 worker 不能发布完成状态；输出使用每个租约独立的私有对象名，避免旧尝试覆盖新产物。最多自动尝试 3 次，输入校验失败直接终止。长期不可达对象或 DB 故障通过租约过期恢复。

输入从 MinIO 直接下载，限制单个视频 256 MiB，校验保存的字节数和 SHA-256；拒绝不可解析、尺寸超过 8192 或单镜头时长超过 60 秒的视频。FFmpeg 只读固定本地 MP4，不执行 shell，不接受用户过滤表达式或网络 URL。尺寸统一为 1280×720、720×1280 或 960×960，24 fps、H.264 / AAC，按分镜顺序拼接。原音轨保留，不足处补静音；字幕文本作为 UTF-8 文本文件，并关闭表达式展开。

Linux 运行需安装 `ffmpeg`、`ffprobe` 和 Noto Sans CJK 字体；默认字体 `/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc`。Windows 默认 Microsoft YaHei。可通过 `FFMPEG_DIR` 和 `COMPOSITION_FONT_PATH` 指定工具及字体，不改变系统 PATH。

合成滤镜及媒体探测依据 [FFmpeg 滤镜文档](https://ffmpeg.org/ffmpeg-filters.html) 和 [ffprobe 文档](https://ffmpeg.org/ffprobe.html)；Windows 分发方来自 [官方下载安装页](https://ffmpeg.org/download.html)。工具二进制和字体不包含在项目源码中，按各自许可使用。

## 评测口径

固定样例包含运动鞋、水杯和台灯，保存原始／结构化提示词及种子。页面「载入对比提示词」只修改前两个镜头的提示词、种子和参考图，必须人工检查后保存、确认和提交。

报告从数据库一致性读取取得统计截点，包含全部历史镜头生成版本，而非仅成功任务。成功率分母为全部版本，失败、待定、未知和未评价明确保留；完成耗时从生成事件计算，只对具备成功事件的任务统计中位数。评分来自人工输入，并保留修订历史。

对比自动匹配固定提示词及种子，再按 provider、模型、模式、尺寸、种子、负向提示和参考图校验值分组。只有两侧具备人工评分且为真实模型时才标记参数可比较；Mock 永远不标记为真实效果对比。参数匹配不是统计显著性证明，单个小样例也不支持效率或质量提升结论。

预留金额与真实费用分开。没有账单时付费实际金额为 null，跨过提交边界的任务标记 UNKNOWN；不把预留金额冒充实付，也不自动退款。只有费用均已知且存在可用视频时才计算可用视频成本。供应商未提交、未结算的任务也明确列出，Mock 的已知金额为 0。

## 验证与可据实描述的贡献

综合验收使用临时的 MySQL 8 / Redis / MinIO，仅加载 Mock provider，执行真实注册登录、任务提交、分镜修订、镜头并发生成与局部重生成、两次应用重启、独立合成、中文字幕、H.264 / AAC 产物验证、成片 SHA-256 和授权下载、评价及报告导出。执行入口：

```powershell
.\scripts\test-generation-infrastructure.ps1 -JdkHome 'C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot' -FfmpegDir '.tools\ffmpeg-9.0.2-essentials_build\bin'
cd client
npm test
npm run build
```

2026-10-01 基线通过 83 项后端测试、48 项前端测试、1 项真实中间件综合测试及前端生产构建，失败、错误和跳过均为 0。独立创作应用已实际打包启动；浏览器验收覆盖登录、模板创建、固定样例编辑与确认、项目额度、批量镜头提交、局部重生成、镜头选择、合成预览、评分、MP4 / JSON / CSV 下载以及服务重启后的会话和数据恢复。浏览器下载的 MP4 与数据库 SHA-256 一致，解码为 720×1280、3.021333 秒；导出报告保留全部 4 个生成版本和明确标注用途的测试评分。

2026-10-02 DeepSeek 补充开发通过 99 项后端测试（含 16 项新分镜模型测试）、54 项前端测试、1 项真实中间件综合测试（18 个检查）及生产构建，独立应用打包成功。新增 V9 迁移、默认关闭和跨项目全局调用额度竞争已在真实 MySQL 验证。模型协议测试仅访问本地 MockWebServer；这份自动化归档不包含真实模型请求。

补充验收归档见 [DeepSeek 接入验收](acceptance/deepseek.json) 和 [页面截图](acceptance/deepseek-workbench.png)。该历史验证覆盖模板创建、新分镜入口及禁用按钮；当时本地接口在关闭状态返回 403，前后均无模型任务，两个付费门禁关闭。

同日获得用户对 DeepSeek 的明确付费授权后，另完成一次 `deepseek-flash` 真实文字分镜联调：三个镜头、15 秒规划，独立保存为 `MODEL` 修订 2。用量为输入 420、输出 494 tokens，应用按配置价格估算 0.000719 USD，实际账单未核对。重复原应用意图复用原任务，调用计数保持 1；视频模型仍为 Mock，未执行真实视频生成。独立证据见 [真实分镜验收](acceptance/deepseek-real.json)、[分镜内容](acceptance/deepseek-real-storyboard.json) 和 [结果截图](acceptance/deepseek-real-workbench.png)。

永久记录见 [完整验收记录](acceptance/complete.json)，另附 [成片流程样例](acceptance/mock-film.mp4) 和 [工作台截图](acceptance/creator-workbench.jpg)。验证使用固定 Mock 视频，不启动付费模型；真实生成质量和供应商账单尚未验收。原项目分析服务未包含在创作平台验收中。

可据实描述新增贡献：基于开源项目完成视频模型异步适配、任务幂等与故障恢复、私有输入与产物管理、分镜及镜头版本编排、项目预算门禁、FFmpeg 独立合成和可追溯人工评测；补齐 DeepSeek 结构化分镜适配、本地契约测试和单例真实分镜联调。公司、实习时间、企业采用情况按实际填写，不把本地实现等同于企业上线，不写模型训练，也不以单例联调推断质量或效率提升。

## 使用范围

这是可运行的短视频创作最小平台，尚未声称生产规模验收：单实例顺序合成，最大 8 镜头，使用固定编码配置；转码过程不运行隔离容器，恶意媒体应在受限制的主机或容器中处理。用户配额、任务取消、对象保留期和孤立对象清理未做管理界面；过期／失败尝试的私有对象需按部署存储保留政策清理。已完成单例真实 DeepSeek 文字分镜、Seedance Mini 真实文生／图生与多镜头成片；TTS、配乐或自动质量评分尚未接入。真实模型运行范围依据用户授权，应用保留明确模型及持久化操作额度。

## 历史验收归档：SiliconFlow（已停用）

以下记录及恢复命令仅描述 2026-10-02 当时的实现，当前版本已移除该适配器与恢复参数，请使用上方当前启动方式。

收到专用密钥并按用户授权执行最多 4 次、总预算 10 CNY 的视频联调。`Wan-AI/Wan2.2-T2V-A14B` 一次提交成功：真实 H.264 视频 720×1280、5.0625 秒，311942 字节，私有 MinIO 归档与下载 SHA-256 一致。首次归档因精确域名白名单不匹配而失败；核实实际输出域名 `s3.6scloud.com` 后恢复原任务查询和保存，未重新生成。生成中应用重启、重复原意图均复用同一本地与供应商任务，额度不增加。

随后从该真实视频抽取合成水杯画面作为私有参考图，将真实 DeepSeek 分镜编辑为人工修订 3 并确认，提交三个 `Wan-AI/Wan2.2-I2V-A14B` 镜头。三个请求均为 SUBMISSION_UNKNOWN，未取得供应商 requestId；旧日志未保留 HTTP 错误分类，无法还原原因。不自动重发，不释放未知费用预留。全局台账为 4 次提交、10 CNY 预留；本轮已停止新增付费请求并恢复默认 Mock 运行，保留全部真实记录和产物。

供应商费用页在核对截点显示一条文生视频、2 CNY；这不是三次未知提交的最终结算，不能把缺少账单行当成未接受的证明。尚无真实图生视频产物及真实多镜头成片，不支持模型效果或质量提升结论。

已补齐脱敏 HTTP／传输／无效响应分类，保证未知任务重启和原意图复用不重投；17 项定向测试、101 项后端回归全部通过，随后前端生产构建和独立应用打包启动成功。本轮未改前端逻辑，54 项前端测试及真实中间件 18 检查为此前验收记录。测试不调用付费模型。

证据：[真实验收记录](acceptance/siliconflow-real.json)、[真实文生视频](acceptance/siliconflow-t2v.mp4)、[原任务追踪](acceptance/siliconflow-t2v-trace.json)、[确认分镜](acceptance/siliconflow-real-storyboard.json)、[未知提交核对清单](acceptance/siliconflow-reconciliation.json)。核实原供应商 requestId 后可用 reconcile 接口恢复原任务；任何额外付费提交需新的明确授权。

## 原任务恢复补充（2026-10-02）

已将原视频任务查询／归档恢复与新付费生成分开控制，默认关闭。`scripts/start-aigc.ps1 -RecoverVideoTasks` 仅加载本地 SiliconFlow 密钥、官方地址和精确产物域名；新创作仍为 Mock，DeepSeek 及真实视频新提交关闭。已有 RUNNING／SAVING 可恢复，QUEUED 真实任务不能跨入付费提交，未知任务须补录供应商核实的原 requestId。

104 项后端回归、54 项前端测试、生产构建及独立应用启动通过。恢复模式已在当前运行实例验证：三次未知任务和全局 4 次／10 CNY 预留保持不变，真实文生视频归档 SHA-256 再次验证一致。本次零新增付费生成。此前中间件综合测试是历史验收，本轮运行检查不将其算作重新执行。

证据见 [恢复模式验收](acceptance/recovery-mode.json)。[供应商核对材料](acceptance/siliconflow-support-request.md) 已准备，未发送工单。真实图生视频、真实多镜头成片、最终供应商账单和人工效果评价仍未完成；不能将本次恢复功能验收描述为整个真实模型链路已经完成。

## Seedance Mini 接入补充（2026-10-02）

已新增独立 Seedance 适配器并配置本机方舟密钥，控制台只读核实 Mini 已开通。本地应用现运行 Seedance 配置，付费生成关闭；116 项后端回归、54 项前端测试、生产构建和独立启动通过。原 SiliconFlow 未知任务、4 次／10 CNY 预留和真实文生视频归档保持不变。本轮没有发起 Seedance 模型请求，真实图生与多镜头成片仍待新的明确授权。

接入参数、恢复方式与限制见 [Seedance Mini 接入说明](seedance-video.md)，脱敏运行证据见 [Seedance 工程验收](acceptance/seedance.json)。以上更新替代文中历史运行配置，不把工程测试算作真实模型效果验收。
