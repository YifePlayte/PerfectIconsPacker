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

默认主题为 `HyperOS4`（`SettingsRepository.DEFAULT_ICON_STYLES`）；资源页选择的主题
会持久化，切换后需重新同步。

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

- `libsu` 提供 root shell，`RootInstaller` 负责检测方案与执行安装命令。
- 检测只做**一次** shell 往返，一条命令同时取回三样东西：
  `id`（是否真拿到 root）、`su -v`（方案品牌）、元模块 id。
  拆成多次 `exec()` 会让它们逐个争用 `MainShell` 的创建锁，任何一次卡住
  `detect()` 就永不返回，界面会停在「正在检测」。
- 方案识别读 `su -v` 的品牌后缀，而不是磁盘目录：
  - Magisk `<version>:MAGISKSU`、KernelSU `<version>:KernelSU`、
    APatch `<version>:APatch`；
  - `su` 由当前生效的方案本体提供，且三者的 `-v` 都在连接 daemon 之前输出，
    所以未授权也能读到品牌；
  - 刻意不看 `/data/adb/magisk` 之类的目录：换装后残留目录会盖过真实方案；
  - 无法识别的品牌如实归入 `Other`，不猜测。
- root 结论看 `id` 的输出，不用 `Shell.isAppGrantedRoot()` 的三态缓存：
  那套缓存（`Utils.currentRootState` / 主 shell）在运行期间授予或撤销 root 后
  不会自行失效，重新检测必须用现场命令实测。
- 重新检测时的清缓存规则：
  - 反射把 `Utils.currentRootState` 置回 `-1`（`proguard-rules.pro` 保留该成员）；
  - **只在当前主 shell 不是 root 时**才关闭它。`BuilderImpl.start()` 拿不到 root
    会退化成 `exec("sh")`，而这个非 root shell 会被 `MainShell` 一直复用，
    用户后来授权也不会换掉它；但已经工作的 root shell 不去打断。
- 进入首页的自动检测沿用现有 shell，避免每次切页都重建会话、反复触发授权弹窗。
- 检测侧有兜底：ViewModel 用原子互斥防止并发探测，并给 `detect()` 加超时，
  保证 `isDetecting` 一定会复位，按钮不会永久变灰。
- 只构建一个模块 zip；安装时按方案选择命令：
  - Magisk：`magisk --install-module <zip>`
  - KernelSU：`ksud module install <zip>`
  - APatch：`/data/adb/apd module install <zip>`（`apd` 不在 PATH，须写绝对路径）
- 三种命令安装的是同一个文件，模块内容不做任何分支。
- 安装输出用 libsu 的 `Job` + `CallbackList` 流式读取，不要自己
  `bufferedReader().useLines`：libsu 在内部用唯一结束哨兵逐行读，读到哨兵即返回；
  自己读到流末尾会永久阻塞，让 `exec0` 一直持锁，之后所有 `Shell.cmd()` 都卡死。
- `CallbackList` 只把每行投递给回调、**自身不存储数据**，所以 `result.out` 恒为空；
  行必须在回调里自己收集，且 `exec()` 返回时队列里可能还有未跑完的回调，
  要先 `awaitTermination` 等它们结束再读取。
- stdout 与 stderr 是两条独立的流（`ShellImpl` 里没有 `redirectErrorStream`），
  而安装器的进度与错误都写 stderr，因此必须
  `.to(stdoutCallback, stderrCallback)` 两路都挂，只挂 stdout 会得到空日志。
- 安装日志界面上只保留最近若干行：大模块有数万行，逐行复制整个列表是 O(n²)，
  会把主线程拖死。该窗口只用于界面显示；`InstallResult.output` 保存完整行，
  但**不要**拿它去重建界面列表（`CallbackList` 传进来的那个列表长度为 0）。
- KernelSU 与 APatch 都把 `/system` 挂载委托给元模块
  （`/data/adb/metamodule`）：没有元模块时模块里的 `system/` 不会生效，
  检测不到时提示用户，而不是生成另一套模块。Magisk 内建挂载，不需要。
- 仅操作应用生成的 zip 与已知模块目录，不执行用户输入拼接的 shell。
- 失败时返回退出码与 stderr，不自动重启设备。
