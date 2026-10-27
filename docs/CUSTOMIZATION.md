# 自定义模型

## 目录

```text
files/custom/
  static/<id>/...
  dynamic/<id>/config.xml
  addons/<任意层级>
  disabled/static/<id>
  disabled/dynamic/<id>
  transform_config.xml
```

目录是唯一事实来源。应用内编辑与 SAF 外部编辑都直接作用于这些真实文件。

## 应用标识

系统查找顺序（优先级从低到高）：

1. 包名：`com.example`
2. 类名：`com.example.MainActivity`（类名以包名开头）
3. 包名#类名：`com.example#com.other.Launcher`

应用内新建条目时：

- 从已安装应用选择，列出该应用的包名与全部桌面入口，可多选；每个选中的标识生成独立条目。
  - 入口指声明了 `MAIN`/`LAUNCHER` 的 activity 与 activity-alias。
  - 被禁用的入口同样列出：应用切换桌面图标的方式就是启用一个带独立图标的别名，
    当前禁用的别名仍可能被应用切换到桌面。
  - 声明了自己图标的入口排在前面，其余按类名排序。
- 每行显示该标识实际对应的图标：入口自带图标时用入口图标，否则用应用图标。
- 也支持手动输入标识。
- 标识不得包含 `/`、`\`、NUL、`..`，不得以 `.` 开头。

## 静态图标

- `static/<id>/` 内只存放 `0.png`（背景）与 `1.png`（前景）。
- 其它文件（如 `10.png`、`0.jpg`、`background.png`）视为脏文件，不参与构建；导入时可忽略或提示清理。
- 必须同时具备 0 与 1；缺图时标记未完成，构建时跳过并警告。
- 单张 PNG 必须为正方形；导入时只做校验，不重编码。

### 与单张图标的关系

桌面的解析顺序是**动态分层 > 静态分层 > 单张**，因此同一个 `<id>` 同时存在
`icons/res/drawable-xxhdpi/<id>/` 与 `miui_mod_icons/<id>.png` 并不冲突，系统会
自然选中优先级更高的那份。

- 自定义分层只是把该 id 的静态分层换掉，**不会删除同名的上游单张**。
- 删掉自定义条目后，上层的静态分层消失，单张重新生效。
- 这正是禁用与自定义的区别：禁用删除该 id 的全部静态形态，自定义只覆盖其中一层。

### 批量导入（icons 格式）

要求 zip 内存在 `res/drawable-xxhdpi/`。只导入该目录下同时具备 `0.png` 与 `1.png` 的子目录条目；目录内其它文件视为脏文件并忽略。自动处理一层多余根目录。导入前汇总新增/覆盖/跳过（含缺层、非正方形、非法 id）。

## 动态图标

- `dynamic/<id>/` 根级必须存在 `config.xml`；层数不限，目录内所有文件保留。
- 强校验，任一不满足即拒绝：
  1. `config.xml` 可解析，根节点 `Config`；
  2. 至少一个数字层，编号从 0 连续；
  3. **每层都有 `quiet/quietImage.png`**；
  4. **每个 fancy 层都有 `fancy/manifest.xml`** 且可解析；
  5. 没有 fancy 的层必须在 config 中声明 `layer_only_quiet<N>=true`；
  6. 至少一个 fancy 层。
- 根级 `quiet/quietImage.png` 不强制；预览优先根级，缺失时用第 0 层。
- 单应用 zip：
  - 根级有 `config.xml` → 直接使用；
  - 只有一个顶层目录且其内有 `config.xml` → 去掉该层；
  - 否则拒绝。
- 解压到临时目录，校验后原子替换。

### 批量导入（dynamicicons 格式）

要求 zip 内存在 `layer_animating_icons/`。其下每个直接子目录视为一个 `<id>`，逐个执行完整强校验；失败条目整条跳过并列出缺失层。自动处理一层多余根目录。

## 高级附加（主题组件原始文件）

高级附加是主题组件文件，不是图标。组件 zip（如 `com.android.systemui`、`framework-miui-res`）本身包含 `res/`、`nightmode/` 等组件资源。

- 存放：`addons/<文件名>`，可建立子目录分类。
- 处理：**按原始字节保存、按原始字节打包**；不解压、不改写、不解析内容、不重命名。
- 组件文件名即组件标识，改名会导致主题管理器无法识别。

输出位置：

| 产物 | 位置 |
|---|---|
| 模块 | `system/media/theme/default/<文件名>`（平铺，与 `icons`、`dynamicicons` 同级） |
| MTZ | `addons/<相对路径>`（保留子目录） |

注意事项：

- 模块平铺时，不同子目录中的同名文件会冲突；构建前报错，不静默覆盖。
- 禁止使用文件名：`icons`、`dynamicicons`、`transform_config.xml`。
- 不限制扩展名；但 UI 应说明典型内容是组件 zip。
- 文件权限 0644，目录 0755，不保留可执行位。

## 禁用

- `disabled/static/<id>` 与 `disabled/dynamic/<id>` 相互独立。
- 效果是删除默认携带内容：
  - 静态：分层目录 + 同名单张 `miui_mod_icons/<id>.png`
  - 动态：`layer_animating_icons/<id>`
- 应用顺序：合并默认 → 静态禁用 → 动态禁用 → 自定义静态 → 自定义动态 → 遮罩 → 附加。
- 无效标记可一键清理。

## SAF

`CustomizationDocumentsProvider` 暴露上述目录，支持系统文件管理器直接编辑。写入时校验路径与大小，禁止逃逸根目录。外部修改后应用重扫构建，不做缓存合并。
