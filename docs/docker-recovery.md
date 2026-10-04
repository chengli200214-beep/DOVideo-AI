# Docker Desktop 启动时的残留 socket 恢复

本机这次启动故障的日志先指向 `%LOCALAPPDATA%\Docker\run\dockerInference`，处理后又指向 `%LOCALAPPDATA%\docker-secrets-engine\engine.sock`：Docker Desktop 初始化监听器时无法移除前一次运行留下的 AF_UNIX socket 文件，因此启动在不同组件处连续失败。这里的 `engine.sock` 属于 Secrets Engine 的临时通信文件。

本次观察到单独删除或重命名这些 socket 文件失败，但在 Docker Desktop 完全退出后，重命名其父目录可行。重新启动时 Docker 可以重新创建原路径。本仓库的脚本将这个恢复步骤限制在两个已知的临时目录，并保留原目录备份。

Docker 的反馈仓库中也有相近的 Windows AF_UNIX socket 启动问题及“清理一个组件后暴露下一个组件”的用户报告；该 issue 是问题反馈，不代表官方已经确认修复版本。[相关反馈 #460](https://github.com/docker/desktop-feedback/issues/460)

2026-10-04 补充验证：先确认没有正在执行的模型或合成任务，正常停止项目容器，再执行 `docker desktop stop --timeout 45`，退出成功。随后直接启动 Docker Desktop，仍复现 `dockerInference` 文件访问错误。因此，本机仅依赖“正常退出”不足以避免复发；项目启动入口已增加下面的预处理。

## 日常启动入口

使用项目的 `scripts/start-aigc.ps1` 启动。它会先通过 `ensure-docker.ps1` 检查本机 Docker，再恢复本项目容器，最后启动或复用应用：

```powershell
.\scripts\start-aigc.ps1 -JdkHome 'C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot'
```

- 本机 Docker 已健康运行：直接复用，不改运行目录。
- Docker Desktop 已完全退出：先调用限定恢复脚本，同时备份两处已知通信目录，再启动 Desktop 并等待引擎就绪。
- Desktop 正在启动或卡在错误窗口：有限等待，超时后给出诊断；脚本不会自动结束 Desktop。先从托盘或错误窗口退出，再重新运行项目入口。
- Java 尚在运行但 Docker 容器已停止：恢复本项目依赖并检查后再报告就绪，不能只凭 Java 进程存在就认定项目可用。

就绪探针固定连接本机 Linux 引擎，不依赖当前 Docker context；每次探针有独立超时，超时只结束该探针进程。默认等待上限 120 秒，可单独执行 `scripts/ensure-docker.ps1 -TimeoutSeconds 180` 调整。容器的就绪等待另行计算。普通项目入口仍关闭付费模型调用。

这是项目启动路径中的恢复措施。直接点击 Docker Desktop 自身图标会绕过该预处理，其底层 socket 问题仍可能出现。

本次真实验证中，恢复入口从 `Stopped` 状态启动，15.7 秒后确认本机引擎就绪；项目随后正常启动。再次调用项目入口保持原 Java 和 Docker 后端进程，页面返回 HTTP 200。将调用者 `DOCKER_CONTEXT` 和 `DOCKER_HOST` 临时设为不可用测试值后，入口仍连接本机引擎并在退出时还原这两项环境变量。重启前后数据库版本、任务状态及数量、5 个项目、1 份参考素材、3 个完成成片完全一致，没有新增模型请求。证据见 [启动恢复验收](acceptance/docker-startup-recovery.json)。

离线测试不操作真实 Docker：`scripts/test-docker-readiness.ps1` 覆盖 8 个编排场景及 3 个原生假 CLI 探针场景，`scripts/test-aigc-startup.ps1` 覆盖 15 个应用启动保护场景；两份脚本均已在 PowerShell 7 和 Windows PowerShell 5.1 验证。CI 增加相同 Windows 矩阵，远端运行结果以 Actions 为准。

## 何时使用

适用于日志明确出现上述 socket 路径，伴随无法访问或移除文件、初始化监听器失败的情况。如果问题是虚拟化未启用、WSL 安装错误、磁盘空间不足或容器自身启动失败，应按对应日志排查；此脚本只处理已知临时 socket 目录。

不要对正常运行中的 Docker 执行目录备份。先通过托盘菜单选择 **Quit Docker Desktop**，等待桌面程序和后端退出。Windows 的 `com.docker.service` 辅助服务可能继续运行，不要求停止；脚本也不会因为普通 `docker` 命令进程而拒绝执行。

## 使用步骤

在项目目录打开 PowerShell，先预览待执行操作：

```powershell
.\scripts\repair-docker-sockets.ps1 -WhatIf
```

预览会检查进程、路径和目录内容，但不会修改目录。确认显示的目标仅是下面两个临时目录后，执行备份：

```powershell
.\scripts\repair-docker-sockets.ps1
```

脚本返回每个原路径及对应备份路径。备份名称包含 UTC 时间和随机 UUID，例如 `run.socket-backup-<时间>-<UUID>`，放在原目录旁边；没有目录或目录为空时跳过。

完成后可手动打开 Docker Desktop；如果安装在通常的 `%ProgramFiles%\Docker\Docker\Docker Desktop.exe`，也可合并为：

```powershell
.\scripts\repair-docker-sockets.ps1 -StartDocker
```

`-StartDocker` 使用隐藏窗口启动方式，不代表 Docker 引擎已经就绪。等待 Desktop 显示引擎运行后，再检查：

```powershell
docker info
```

确认引擎可用后，再按项目原启动步骤运行应用。无需因这类 socket 故障重置数据库或重新提交模型任务。

## 脚本实际处理范围

| 目录 | 唯一允许的直接条目 |
| --- | --- |
| `%LOCALAPPDATA%\Docker\run` | `dockerInference`、`userAnalyticsOtlpHttp.sock` |
| `%LOCALAPPDATA%\docker-secrets-engine` | `engine.sock` |

脚本按精确进程名检查 Docker Desktop、`com.docker.backend` 及相关 Desktop 代理或构建进程。发现仍在运行时直接拒绝，不自动结束任何进程。第一次重命名前会验证全部候选目录，执行每次操作前还会重新检查进程和目录内容。

所有目标和备份路径都必须位于解析后的 `LOCALAPPDATA` 内；目标目录及其到该根目录之间的目录链不能是重解析点或目录链接。出现任何额外文件、嵌套目录、目标路径类型异常或内容变化时，脚本停止，要求人工检查。socket 文件只枚举名称，不读取文件内容，也不尝试逐个强制删除。

脚本不递归删除任何目录，不改 Docker 设置，不访问 VHD 数据文件，不删除镜像、容器、命名卷或 WSL 发行版。备份会保留；不要把仍可能不可访问的旧 socket 文件重新覆盖到 Docker 刚创建的运行目录中。

## 仍然失败时

如果提示 Docker 进程尚未退出，先完成正常退出再重试。若提示出现未知条目，先核实目录用途，不要直接扩大白名单。若父目录本身也无法重命名，或重新启动仍报同一底层文件访问错误，可正常重启 Windows 后重新检查日志。若日志转为其他错误，继续针对新的错误定位，不连续执行工厂重置。

脚本的语法和拒绝条件使用临时目录及 `-WhatIf` 验证；恢复是否奏效以实际 Docker 引擎状态和 `docker info` 为准。
