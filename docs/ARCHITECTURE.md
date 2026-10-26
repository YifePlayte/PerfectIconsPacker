# 架构

## 分层

```text
ui/        Compose + Miuix 页面、导航、ViewModel
domain/    纯 Kotlin：图标合并、自定义校验、模块/MTZ 打包
data/      JGit 资源获取、自定义目录存储、设置、root 安装
core/      数据模型与通用工具（路径、zip、图片校验）
provider/  SAF DocumentsProvider
```

依赖方向：`ui → domain → data → core`。`domain` 不依赖 Android 框架，便于单元测试。

## 数据流

```text
GitHub 上游仓库
   │  JGit meta.git（commit + tree，blob:none）
   │  JGit blobs.git（按 SHA 批量拉取）
   ▼
repo/snapshot/（当前主题的物化资源，只读）
   │
   ├── custom/static      用户静态覆盖
   ├── custom/dynamic     用户动态覆盖
   ├── custom/addons      主题组件原始文件（不解析）
   ├── custom/transform_config.xml
   └── custom/disabled    禁用标记
   ▼
ThemeAssembler（确定性合并）
   ▼
AssembledTheme（文件引用，不复制图片）
   ├── ModuleBuilder → 通用模块 zip（Magisk/KSU）
   └── MtzBuilder    → MTZ zip
```

## 资源获取

上游仓库地址与可选的加速链接由用户配置，默认指向
`pzcn/Perfect-Icons-Completion-Project`，应用不内置任何加速服务：

- `RepoEndpoint.candidates()` 生成请求顺序：加速端点在前，直连仓库恒为最后一项。
- 加速链接支持前缀形式（`https://proxy.example/`）与 `{url}` 模板两种写法。
- `IconRepository` 的端点列表以 lambda 传入，每次网络调用重新读取设置；
  改完仓库地址或加速链接后无需重启应用即可生效。
- 每个候选端点都会重试（4 次、指数退避），任一成功即停止。
- 快照记录 `sourceRepo`（直连仓库地址，加速端点只算传输层），用于识别
  换了仓库但快照仍是旧来源的情况。

使用双对象库，避免浅克隆无法按 SHA 取 blob 的问题：

- `repo/meta.git`：`depth=1` + `filter=blob:none`，仅提交和 tree。
- `repo/blobs.git`：非浅、无工作区，保存按 SHA 拉取的 blob。

流程：

1. 拉取 `meta.git` 的 `main`。
2. 遍历 tree，按主题筛选需要的路径。
3. 与 `snapshot.json` diff，得到新增/变更 blob。
4. 分批（默认 100）向 `blobs.git` fetch。
5. 校验 SHA-1，物化到 `repo/snapshot/`。
6. 原子替换快照与清单。

筛选规则：

```text
icons/<id>/0.png                静态分层，只取 0 与 1
icons/<id>/1.png
themes/<style>/icons/**         主题静态覆盖（同样只取 0/1）与单张图标
themes/<style>/layer_animating_icons/**   动态，保留全部层
themes/common/layer_animating_icons/**
```

## 打包

- 图片不做重编码。
- 内层 zip：`icons`、`dynamicicons`。
- PNG 与内层 zip 使用 STORED，XML/文本使用 DEFLATE。
- 使用临时文件 + 原子重命名，失败不留下半个产物。
- 产物写入 `files/builds/output/`，构建成功后只保留最新一个，旧产物随即删除；
  构建失败时不动上一个可用产物。
- 所有 zip 写入路径统一校验，禁止 `..`、绝对路径、NUL。

## 自定义暴露

`CustomizationDocumentsProvider` 把 `files/custom/` 暴露给系统文件框架：

- 根目录：`static`、`dynamic`、`addons`、`disabled`、`transform_config.xml`
- `addons/` 内是主题组件原始文件（如 `com.android.systemui`），按字节保存，不解压。
- 支持浏览、创建、重命名、删除、写入、缩略图。
- 应用内编辑与外部编辑共用同一目录，不做双向同步。
- 通过 `FileObserver` 与生命周期重扫感知外部修改。
- 自定义页支持下拉刷新（`PullToRefresh`），用于同步通过 SAF 在外部完成的修改：
  目录是唯一事实来源，刷新即重新扫描，不合并缓存。
- 预览缓存以「路径 + mtime + size」为键，图层被替换或清除后立即重新解码，
  不会残留旧缩略图。

## Root

- `libsu` 检测 root 与当前方案（Magisk / KernelSU），也负责执行安装命令。
- `Shell.isAppGrantedRoot()` 是三态而不是布尔值：`true` 已授权、`false` 无 `su`
  因此不可能有 root、`null` 表示存在 `su` 但授予状态未验证。`null` 不能当作拒绝。
- 结论是**进程级缓存**，分两层：
  - `Utils.currentRootState`：`-1` 未知 / `0` 无 `su` / `1` 有 `su` 未验证 /
    `2` 已确认，首次判定后不再重算；
  - 建好的主 shell，非 root shell 会被后续所有命令复用。
- KernelSU 只在应用已授权时才让 `su` 可见，未授权时的 `false` 会被一直缓存，
  于是运行期间再授权不生效，重启应用也不会改变结论。
- 因此手动「重新检测」**无条件重置**这两层缓存：关闭缓存的 shell，并用反射把
  `currentRootState` 置回 `-1`，随后跳过缓存结论直接执行一次 `su` 实测。
  授权与撤销都能立即反映到界面。反射依赖字段名，`proguard-rules.pro` 保留该成员。
- 进入首页的自动检测沿用缓存，避免每次切页都重建 shell、反复触发授权弹窗。
- 只构建一个模块 zip；安装时按方案选择命令：
  - Magisk：`magisk --install-module <zip>`
  - KernelSU：`ksud module install <zip>`
- 两种命令安装的是同一个文件，模块内容不做任何分支。
- KernelSU 依赖 metamodule 提供 `/system` 挂载；
  检测不到时提示用户，而不是生成另一套模块。
- 仅操作应用生成的 zip 与已知模块目录，不执行用户输入拼接的 shell。
- 失败时返回退出码与 stderr，不自动重启设备。
