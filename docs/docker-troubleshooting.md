# Docker Desktop 启动故障与本机恢复记录

2026-10-02 本机环境：Docker Desktop 4.66.1.222799、Windows 11 build 26200、WSL2。恢复后 Docker 引擎版本为 29.3.1。

## 已核实的直接原因

Docker 后端日志连续显示两个组件启动失败：

- Inference manager 无法清理 `%LOCALAPPDATA%\Docker\run\dockerInference`。
- 处理第一处后，Secrets Engine 无法清理 `%LOCALAPPDATA%\docker-secrets-engine\engine.sock`。

Windows 返回 `The file cannot be accessed by the system`。这两个零字节端点的文件属性均为 `Archive, ReparsePoint`；单独移动端点及 `fsutil reparsepoint query` 均失败，后者返回错误 1920。Docker 将组件启动失败升级为整个后端启动失败，因此引擎命名管道不存在，项目容器无法运行。

这是 Windows AF_UNIX 运行端点残留无法处理的故障表现。Docker 官方 GitHub 仓库已有相同环境与错误的用户报告：[启动故障报告 #554](https://github.com/docker/desktop-feedback/issues/554)、[组件启动失败报告 #531](https://github.com/docker/desktop-feedback/issues/531)。这些是问题报告，不代表本机已确定底层 Windows 内核故障原因。异常退出可能触发残留；本机具体触发时刻及原因未确认，正常退出也不能保证不会复发。

## 本次恢复

1. 使用 `docker desktop stop` 关闭失败的启动，确认 Docker Desktop 与后端进程已退出。
2. 检查两个父目录是普通目录，仅包含预期的零字节套接字重解析点；校验原路径和备份路径都位于其预期父目录。
3. 将两个父目录改名为带时间戳的备份，保留无法访问的端点，创建空的原目录。
4. 启动 Docker Desktop，验证 `docker info` 成功，再启动原项目 MySQL、Redis、MinIO 和默认免费创作应用。

本次没有执行 factory reset、WSL 注销、容器卷删除、文件系统递归删除或 Docker 设置变更。只修复运行目录，WSL 数据盘和容器持久卷保留。备份位置及操作元信息保存在本机忽略版本控制的 `.local/docker-socket-recovery.json`；备份中的端点仍可能无法单独访问，不应反复强制删除。

## 再次出现时

先根据最新日志确认是否仍为这两个端点的同类错误。引擎正常运行时不处理运行目录。只有 Docker 完全停止、目录内容核验通过时，才重复本次父目录备份方法；若有其他文件或新的错误，保留现场并重新排查。不要将这一方法泛化到 Docker 的 `wsl` 目录或其他数据目录。

启动成功只解决本地运行问题。旧服务未知任务按用户要求保留历史状态，当前适配器已停用；Docker 恢复不会重新发送这些任务，也不会改变原调用额度。
