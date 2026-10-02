# DeepSeek 脚本与分镜接入

创作工作台现在支持两种起草方式：免费本地模板，以及单独授权的 DeepSeek 文字模型。创建项目仍只生成模板；在已有项目的「DeepSeek 脚本与分镜」中明确提交，才会创建模型任务。自动刷新、重新载入页面和人工确认均不提交模型。

## 付费调用是什么意思

后端通过 API 向服务商发出真实生成请求，服务商可能从 API 账户余额或赠送额度扣费。DeepSeek 按输入／输出 token 计费；视频模型依供应商、模型、分辨率及时长等规则计费。开发接口、本地 Mock、运行自动化测试、保存已有视频与本机 FFmpeg 合成不会产生模型 API 费用。ChatGPT／Codex 的订阅与供应商 API 账户独立，订阅不自动支付这些请求。

初始接入、自动测试与中间件验收不调用真实模型，相关历史记录的付费调用为 **0**。2026-10-02 获得用户对 DeepSeek 的明确付费授权后，另完成 **1 次真实分镜调用**，见下方独立记录。`scripts/start-aigc.ps1` 仍强制关闭视频和分镜的付费开关，清空对应密钥与授权次数；父进程配置也不能改变该演示脚本的行为。

## 工作方式

1. 创建模板项目，保存编辑内容。
2. 检查 DeepSeek 面板的模型、币种、单次预留、总预算和最大调用数。
3. 服务端完成调用授权配置后，勾选本次费用确认，再点击「DeepSeek 生成分镜」。只依据已保存的原始创作需求生成；当前手工编辑的脚本不发送给模型。
4. 自动查询任务。成功时原分镜保留，新增 `MODEL` 修订并清除原确认；明确点击「载入生成分镜」，检查、修改、保存并重新确认。
5. 视频生成仍是独立操作，拥有自己的授权额度，不因分镜完成而自动启动。

参考图仅在服务端保留素材引用；此次适配器发送文字，不发送私有图片、对象存储地址或素材 ID，也不声称模型看过图片。镜头 ID、顺序、画幅、参考素材和生成参数由服务端赋值。模型返回的脚本及镜头字段受类型、数量、长度和总时长校验；无法通过校验时保留旧稿及错误记录。

## 幂等、费用与异常恢复

- `(user_id, Idempotency-Key)` 唯一。创建任务前锁定项目，原子保存冻结的模型请求并占用全局授权次数／预留预算；重复提交原意图返回原任务。不同意图不能借用同一个 key。
- 同一项目只允许一个进行中的分镜模型任务。修改授权的模型、价格、币种、额度或提示模板版本后，旧授权 ID 不能继续消费；新的授权配置需重新获得人工许可。
- HTTP 客户端关闭连接失败重试和重定向，单次调用有时间及响应大小上限；每个任务只发送一次 POST。没有模型自修复重试。
- HTTP 4xx 明确拒绝为 `REJECTED`；输出无效为 `FAILED`；网络超时、断连、5xx、已返回结果但数据库保存失败为 `UNKNOWN`。重启后的过期 `SUBMITTING` 同样进入 `UNKNOWN`，不会重新请求。
- 失败和不确定任务均保留已占用额度，不自动释放。用户明确选择「再次生成（新调用）」才使用新的 key 和额度；尤其 `UNKNOWN` 应先到供应商后台核对原调用及账单。
- 调用期间人工保存了新版时，模型结果标为 `CONFLICT` 并保留输出，不覆盖手工版本。过期或失去租约的工作进程不能保存修订。
- `reservedCost` 是授权预算预留；`estimatedCost` 是返回 token 用量按配置价格计算的估算，可能与缓存、阶梯或实际账单不同。未核对账单时不能称为实际成本。预留门禁限制应用侧调用范围，最终扣费依据供应商账单及账户规则。

## 配置与启动

默认不启用；独立配置前缀为 `storyboard.model`，环境变量为 `.env.example` 的 `STORYBOARD_*`。不继承原视频理解模块的 `SILICONFLOW_API_KEY`，也不继承视频任务的 `GENERATION_API_KEY`。

支持 `https://api.deepseek.com`（也接受 `/v1`）及 `https://api.siliconflow.cn/v1`。前者使用 DeepSeek 官方模型 ID 与官方密钥；后者使用该平台实际开放的 DeepSeek 模型 ID 与该平台密钥，二者不能混用。当前官方默认模型名 `deepseek-flash`，上线前应再次核对账户可用模型。

启用真实请求需同时配置：`STORYBOARD_PAID_ENABLED=true`、API 密钥、准确的模型及完全相同的 `STORYBOARD_APPROVED_MODEL`、人工许可对应的授权 ID、币种、最大调用数、总预算、单次预留、已核实的输入／输出价格（每百万 token）。价格和额度为 0 或配置不完整时不能提交。输入 UTF-8 字节数与最大输出 token 用于保守估算；预留不足也拒绝提交。

密钥只能保存在忽略版本控制的本地配置，不写入源码、接口返回、请求快照或验收报告。不要把密钥粘贴到聊天。`start-aigc.ps1` 始终运行免费演示；要执行真实验收，应在得到模型、金额与次数的明确授权后，使用独立运行环境配置启动打包的 `aigc-app`，不能修改演示脚本偷偷开放调用。

## 接口

均需登录：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/generation/storyboard-model/runtime` | 无密钥的就绪状态及预算占用 |
| POST | `/generation/projects/{project}/planning` | `{expectedRevision}` + Idempotency-Key，返回 202 和模型任务 |
| GET | `/generation/projects/{project}/planning` | 当前用户项目的最近 50 个模型任务 |
| GET | `/generation/projects/{project}/planning/{task}` | 冻结输入、输出、校验、token 用量、状态及时间 |

任务状态：`QUEUED → SUBMITTING → SUCCEEDED / FAILED / REJECTED / UNKNOWN / CONFLICT / BLOCKED`。成功任务记录 `savedRevision`；结果需要人工确认才能提交后续镜头。

## 官方依据与验收范围

请求使用 Chat Completions、JSON Output、非流式输出和有界 `max_tokens`。提示词明确包含 json 和示例；空内容、截断、重复 JSON 字段、额外素材字段等均拒绝。DeepSeek 官方使用 `thinking.type=disabled`，SiliconFlow 使用 `enable_thinking=false`。

依据：[DeepSeek JSON Output](https://api-docs.deepseek.com/guides/json_mode/)、[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[DeepSeek 模型与计费](https://api-docs.deepseek.com/quick_start/pricing/)、[SiliconFlow Chat Completions](https://docs.siliconflow.cn/docs/api/chat-completions-post)。

自动测试的模型 HTTP 请求全部由测试专用拦截器送到回环地址的 MockWebServer，不会送往真实供应商。覆盖成功保存、权限、默认关闭、并发幂等、跨项目额度竞争、配置变更、无效 JSON、空输出、截断、非法字段、HTTP 错误、断连、超时、版本冲突、过期租约、存储失败及不重复调用。单例真实联调与自动测试分开记录，不把一次生成视作全面质量评测。

## 2026-10-02 真实分镜验收

用户明确授权 DeepSeek 付费调用，不设用户预算上限。本轮操作范围为一个文字分镜样例；应用仍使用一次调用的操作额度及 0.02 USD 预留，避免联调刷新或重复意图产生额外请求。这是本轮运行配置，不是用户授权的预算上限。视频服务保持 Mock、视频付费门禁关闭。

先通过官方 `/models` 验证凭据及可用模型，再由现有应用提交 `deepseek-flash` 分镜任务。便携水杯案例从模板修订 1 生成独立的 `MODEL` 修订 2，包含整体外观、拿取放入、桌面静置三个镜头，每个规划 5 秒，共 15 秒；脚本、字幕、口播与视频提示词均保存，仍待人工检查及确认。

返回输入 420 tokens、输出 494 tokens，共 914 tokens；任务执行约 2.918 秒。按已核对的官方高峰、缓存未命中价格配置，应用估算 0.000719 USD；该估算未考虑实际时段优惠，不是已结算扣费。供应商账单未核对。

完成后重放同一应用幂等意图仍返回原任务，全局调用计数保持 1，项目只有一个分镜模型任务及一份新增模型修订，验收账号视频任务为 0。页面已显示真实脚本、三个镜头、新分镜已保存和估算费用；本轮一次操作额度用尽后，生成按钮禁用。未执行真实文生视频或图生视频。

脱敏证据：[真实调用记录](acceptance/deepseek-real.json)、[已保存的分镜](acceptance/deepseek-real-storyboard.json)、[工作台截图](acceptance/deepseek-real-workbench.png)。历史 [零付费接入验收](acceptance/deepseek.json) 保持原样；模型密钥仅在忽略版本控制的本地文件中保存，不进入这些记录。

## 已保存分镜的后续闭环验证

在独立演示项目中导入上述真实分镜，保留来源项目及修订记录，确认后生成三个 Mock 镜头并归档；再保存镜头选择，以真实 FFmpeg 合成带中文字幕的私有 H.264 / AAC MP4。下载文件为 16791 字节，SHA-256 与归档元数据一致，视频流实际 3 秒、容器 3.021333 秒、720×1280。Mock 每个片段固定为一秒，因此没有把分镜的 15 秒规划伪装成实际视频时长；画面是蓝色流程样本，音频补静音，口播未转为语音。

此次没有新增 DeepSeek 或真实视频调用。评测记录为三个成功的 Mock 版本、三个未评价版本，没有代填人工质量分数。证据见 [闭环记录](acceptance/deepseek-chain.json) 与 [Mock 成片](acceptance/deepseek-chain-mock-film.mp4)。原真实分镜项目仍保留，尚未配置视频项目预算，可供后续真实视频联调。

初次准备时，用户另行授权 SiliconFlow 两种 Wan2.2 模型合计最多四次视频提交、总预算 10 CNY，但尚缺专用密钥，未发送真实视频请求。该历史范围见 [视频联调计划及授权](acceptance/siliconflow-plan.json)。收到专用凭据后完成账户模型检查：一次文生视频已成功归档，三次图生视频提交结果未知，本轮额度已全部占用并关闭付费门禁。详情见 [真实视频验收](acceptance/siliconflow-real.json)。真实分镜项目新增人工修订 3，将真实文生视频的合成画面截图作为私有参考图，不能描述为用户实物产品照片；尚未完成真实多镜头成片。
