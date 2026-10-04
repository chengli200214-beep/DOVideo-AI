# 模型配置：DeepSeek 与 Seedance

当前创作平台只接入两个付费模型服务：DeepSeek 官方 API 生成文字脚本与分镜，火山方舟 Seedance 生成视频。Mock 与本地分镜模板保留为免费的演示和自动测试方式。

## 调用与启动

- 分镜：`STORYBOARD_BASE_URL=https://api.deepseek.com`，专用 `STORYBOARD_API_KEY`；当前配置模型 `deepseek-flash`。不接收图片内容。
- 视频：`GENERATION_PROVIDER=seedance`，专用 `SEEDANCE_API_KEY`；当前接入 `doubao-seedance-2-0-mini-260615`，文生与图生使用同一模型。
- Seedance 能力：5 秒、720p、横屏／竖屏／方形、首帧参考图、无生成音频、有水印；`GENERATION_*_SIZES=1280x720,720x1280,720x720`，`GENERATION_*_NEGATIVE_PROMPT=false`。Mock 的通用默认能力保留 `960x960` 和负向提示词用于流程测试。
- 普通 `scripts/start-aigc.ps1` 默认 Mock；`-Seedance` 仅载入本机方舟凭据和能力，仍关闭视频、分镜付费调用及任务恢复，不导入授权额度。
- 真实调用需独立配置付费开关、准确模型、授权 ID、调用次数和预算预留；配置密钥或打开页面不代表允许调用。现有用户授权与调用台账只保存在忽略版本控制的本机配置，源码不会携带它们。
- `SEEDANCE_RECOVERY_ENABLED=true` 只开放已有方舟任务的查询／保存；不让真实 QUEUED 任务跨入新付费提交。

模型提交、查询、归档、局部重生成、字幕合成和人工评测流程不变。下载需要精确的 `SEEDANCE_ARTIFACT_HOSTS`，不接受通配符。费用预留仍不是供应商实际账单。

## 已移除的接入

删除 SiliconFlow 视频适配器、协议测试、分镜兼容网关、启动器恢复参数 `-RecoverVideoTasks`，以及 `GENERATION_API_KEY`、`GENERATION_BASE_URL`、`GENERATION_ARTIFACT_HOSTS`、`GENERATION_RECOVERY_ENABLED` 配置。旧参数调用会明确报错；旧环境变量不再绑定应用配置。

历史任务、额度、分镜、图片和已归档视频没有删除或迁移到 Seedance。所属用户仍可查询追踪记录和下载已归档产物；恢复接口返回服务已停用，页面不会显示可用恢复操作。后台延后旧服务任务，避免阻塞当前任务或重新向旧服务发送请求。旧未知提交仍保留原状态和费用未知事实。

`docs/acceptance/` 与文档历史验收段落保留原服务记录，表示当时发生的事实，不代表当前还支持该接口；历史命令不再是当前运行方式。此次精简无需数据库迁移。

## 原视频理解模块

原分析 LLM 改为 DeepSeek 官方：`DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`LLM_MODEL`。它与分镜的 `STORYBOARD_*` 授权配置独立。

原 ASR 与 Embedding 不再继承 DeepSeek 密钥或服务地址，也不再默认访问第三方网关：

| 服务 | 默认状态 | 可选独立配置 |
| --- | --- | --- |
| ASR | 关闭 | `ASR_ENABLED`、`ASR_API_KEY`、`ASR_URL`、`ASR_MODEL` |
| Embedding | 关闭 | `EMBEDDING_ENABLED`、`EMBEDDING_API_KEY`、`EMBEDDING_BASE_URL`、`EMBEDDING_MODEL` |

不启用 ASR 时没有语音转写；不启用 Embedding 时沿用关键词检索降级。这两个服务用于保留的原视频理解应用，独立 AIGC 创作入口不加载它们。DeepSeek 和 Seedance 的创作闭环不需要额外开通这两种服务。

测试使用内置 MP4、本地 HTTP 拦截器和隔离中间件，关闭所有真实调用开关，不发送付费生成请求。真实模型效果证据沿用已有 [DeepSeek 分镜](acceptance/deepseek-real.json) 与 [Seedance 视频及成片](acceptance/seedance-real.json)。

本轮完整回归通过：153 项后端、77 项前端、真实中间件 1 项／21 个检查，以及 32 项 Windows 启动检查（PowerShell 7 与 5.1）。干净构建的独立 JAR 已确认不包含退役适配器；工作台实际重启后，原项目、素材、成片及额度台账快照一致，历史视频下载 SHA-256 一致。新增付费请求为 0，见 [精简验收记录](acceptance/model-provider-simplification.json)。
