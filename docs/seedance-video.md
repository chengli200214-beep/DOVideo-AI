# Seedance Mini 视频适配（2026-10-02）

已新增方舟 `seedance` provider，并完成真实文生视频、图生视频、私有归档和多镜头成片闭环。沿用现有幂等、预算预留、查询恢复和审计链路。

项目按用户确认的本次范围已完成。用户明确取消供应商实际账单核对、正式人工评分、历史 SiliconFlow 三笔未知提交核实，三项均移出验收范围，不再作为完成条件。费用未知、未评分和历史未知提交记录保留原状态。

## 当前运行状态

用户已明确授权 Seedance 不限制调用次数和预算。本地应用现运行 `doubao-seedance-2-0-mini-260615` 真实生成模式，两个能力接口 `available=true`；此次实际调用 4 次，其中 1 次文生、3 次图生，全部完成。调用计数和费用预留仍持久化保存，未知提交仍不重投。

117 项后端回归及独立应用打包通过；54 项前端测试和生产构建为接入时通过的检查，本轮未改前端代码。实际付费启动预检发现独立入口漏注册 provider，已补入 `AigcApplication` 并新增实际 Spring 应用上下文测试；该测试覆盖三个 provider 的注入及付费门禁。此前 12 项协议／流程测试访问本地 HTTP 拦截器与 H2，真实生成另行记录。

三个图生镜头各为 720×1280、24fps、5.041667 秒的 H.264 视频，私有 MinIO 下载哈希一致；独立文生样例也完成。成片为 15.146333 秒、720×1280，带中文字幕和补齐的静音 AAC 音轨，完整解码通过。重启后重复原生成与合成意图均复用，台账仍为 4 次／20 CNY 预留；项目镜头为 3 次／15 CNY。预留不是实际账单。

原 SiliconFlow 三次未知任务及 4 次／10 CNY 预留未变化，未提交工单。本轮未重新执行中间件综合测试。证据：[真实验收](acceptance/seedance-real.json)、[成片](acceptance/seedance-film.mp4)、[工作台](acceptance/seedance-real-workbench.jpg)、[供应商脱敏回执](acceptance/seedance-provider-receipts.json)、[JSON 评测](acceptance/seedance-evaluation.json)、[CSV 明细](acceptance/seedance-evaluation.csv)。[初次未付费检查](acceptance/seedance.json) 保留为历史记录。

## 配置与启动

方舟密钥仅存放于 Git 忽略的 `.local/seedance-video.env`，不写入源码、文档或接口响应：

```dotenv
SEEDANCE_API_KEY=<本机填写>
SEEDANCE_BASE_URL=https://ark.cn-beijing.volces.com/api/v3
SEEDANCE_ARTIFACT_HOSTS=ark-acg-cn-beijing.tos-cn-beijing.volces.com
SEEDANCE_RECOVERY_ENABLED=false
```

```powershell
./scripts/start-aigc.ps1 -JdkHome '<JDK21+安装目录>' -Seedance -RecoverVideoTasks
```

启动器只导入凭据和下载配置，固定关闭新视频和分镜付费调用。`-RecoverVideoTasks` 开放 SiliconFlow 旧任务恢复。方舟与 SiliconFlow 的凭据、地址和下载白名单独立，切换不会把旧任务改为新供应商。`SEEDANCE_RECOVERY_ENABLED=true` 可独立开放已有方舟任务的查询与保存，不允许新提交。

本轮专用启动脚本为 Git 忽略的 `.local/start-seedance-authorized.ps1`，读取 `.local/seedance-video-authorization.json` 中已有 Seedance 授权 ID；重启复用 ID，不重置计数。不限预算的授权仍受 INT／DECIMAL 存储上限与每项目版本额度等技术边界约束。普通启动器保持默认不付费，发现已有付费运行实例会提示先停止。Seedance 授权不扩大 SiliconFlow 的历史额度。

## 首版调用约束

- 文生与图生均使用准确 Mini 模型 ID；固定 5 秒、720p；横屏、竖屏、方形分别为 `1280x720`、`720x1280`、`720x720`。
- `generate_audio=false`、`watermark=true`；不接受负向提示词，种子范围 0 至 2147483647。
- 图生从私有 MinIO 读取 PNG/JPEG，用 Base64 首帧提交；短边至少 300、最长边至多 4096 像素，沿用本地 5 MiB 限制。
- 提交前校验素材与参数，失败不跨入预算预留边界。固定默认值写入审计，图片字节被脱敏。
- POST `/contents/generations/tasks` 仅发送一次，返回 `id` 后 GET `/contents/generations/tasks/{id}`。`X-Client-Request-Id` 携带本地任务 ID 用于追踪，不假设供应商保证幂等。
- 查询校验返回任务 ID 和模型；仅明确 `succeeded` 且有视频 URL 才进入保存。
- POST 网络中断、408、5xx、无效响应或缺少 ID 保留 `SUBMISSION_UNKNOWN`，不重投、不释放预留。诊断不保存密钥、原始响应或临时签名 URL。

## 验收场景与使用范围

独立项目 `370ebf90-3577-4082-88df-bd0763662954`，确认修订 2，沿用已有 DeepSeek 分镜的人工修订与私有合成水杯图片，包含整体外观、拿取放入、桌面静置三个镜头。[最初准备方案](acceptance/seedance-prepared-project.json) 是授权前快照，当时的 3 次／15 元提案已被用户后续不限次数与预算授权取代。

真实生成和合成已完成。评测界面与导出保留“未评分”和费用未知状态，没有代填人工评分或伪造结算。抽帧观察见 [视觉检查记录](acceptance/seedance-visual-observations.md)；此观察与正式人工评价分开。TTS 与配乐未接入，口播文字仍只是分镜字段。

首次归档因白名单为空失败。核实真实返回主机 `ark-acg-cn-beijing.tos-cn-beijing.volces.com` 后更新精确白名单并重启，三个原任务恢复保存，没有重新生成。下载器仍拒绝通配符、重定向、非 443、私网地址和过大／非 MP4 产物；临时 URL 不作为永久资产。

本地预算为持久化调用次数和保守费用预留，不是供应商账单结算；实际费用保持未核对状态，按用户要求不继续对账。控制台 API 调试也会产生真实请求，本次未使用它。

官方协议：[创建任务](https://docs.volcengine.com/docs/ark/create-video-generation-task-api?lang=zh&redirect=1)、[查询任务](https://docs.volcengine.com/docs/ark/get-video-generation-task-api?lang=zh)、[任务列表与产物有效期](https://docs.volcengine.com/docs/ark/list-video-generation-tasks-api?lang=zh)。
