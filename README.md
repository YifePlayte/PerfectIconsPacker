# PerfectIconsPacker

面向 **HyperOS 4** 的“完美图标补全”打包器。

从上游图标资源仓库直接拉取资源，在设备本地组装并导出：

- 模块 zip（同一产物同时适配 Magisk 与 KernelSU）
- MTZ 主题包

应用使用 Kotlin + Jetpack Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) 构建，root 能力通过 [libsu](https://github.com/topjohnwu/libsu) 提供。

## 状态

已实现：直接拉取上游资源、HyperOS 4 模块与 MTZ 构建、图标自定义、SAF 管理与保存、Magisk/KernelSU 安装。

- [架构设计](docs/ARCHITECTURE.md)
- [主题格式](docs/THEME_FORMAT.md)
- [自定义模型](docs/CUSTOMIZATION.md)

## 约束

- 只支持 HyperOS 4 的主题结构。
- 图标资源直接来自 `pzcn/Perfect-Icons-Completion-Project`，不经过中转仓库。
- 图标资源仓库只读：不修改、不提交、不执行其中任何脚本。
- 参考项目 `Perfect-Icons-APP` 中的 shell 脚本**禁止执行**，其中大量变量需要外部注入。

## 许可证

本项目基于 [GPL-3.0](LICENSE) 发布。

图标资源版权归原作者所有，来自 `pzcn/Perfect-Icons-Completion-Project`，不适用本项目的许可证。
