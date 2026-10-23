# HyperOS 4 主题格式

## 产物结构

### 模块（单一 zip，Magisk / KernelSU 通用）

```text
module.prop
customize.sh
META-INF/com/google/android/update-binary
META-INF/com/google/android/updater-script
system/media/theme/default/icons
system/media/theme/default/dynamicicons
system/media/theme/default/<组件文件名>     高级附加，平铺
system/media/theme/miui_mod_icons/<id>.png
```

`icons`：

```text
res/drawable-xxhdpi/<id>/0.png
res/drawable-xxhdpi/<id>/1.png   静态分层，只保留 0 和 1
transform_config.xml
```

`dynamicicons`：

```text
layer_animating_icons/<id>/...
```

### MTZ

```text
description.xml
icons                          静态 + 动态 + transform_config
miui_mod_icons/<id>.png
addons/<组件相对路径>           高级附加，保留子目录
preview/preview_icons_4x3_0.jpg  可选，默认生成
wallpaper/default_wallpaper.png  可选，默认不生成
```

`icons`：

```text
res/drawable-xxhdpi/<id>/{0,1}.png
layer_animating_icons/<id>/...    动态，层数不限
transform_config.xml
```

## 模块包的通用性

模块只产出一个 zip，不区分 Magisk 与 KernelSU：

- 根目录同时满足两套规范：`module.prop`、`customize.sh`、`system/`，以及 Magisk 使用的 `META-INF/com/google/android/{update-binary,updater-script}`。
- Magisk 通过 `update-binary` 安装，并 source `customize.sh`。
- KernelSU 通过 `ksud module install` 安装，忽略 `META-INF`，同样 source `customize.sh`。
- `customize.sh` 不设置 `SKIPUNZIP`，让两套安装器都执行默认完整解压；脚本内不做方案分支，不使用方案专属路径或命令。
- 应用负责检测当前 root 方案并调用对应安装命令，模块文件本身完全相同。
- KernelSU 若未启用提供挂载能力的 metamodule（如 `meta-overlayfs`），`system/` 不会被挂载；应用需提示该前置条件。

## 与旧结构的差异

| 项 | 旧模块 | HyperOS 4 模块 |
|---|---|---|
| 动画图标 | 在 `icons` 内 | 拆分到 `dynamicicons` |
| 静态图标 | `icons` 内 `res/` | 不变 |
| 单张图标 | `miui_mod_icons/` | 不变 |
| MTZ | 单个 `icons` | 不变 |

## transform_config.xml

默认使用 HyperOS 4 系统遮罩（`SupportLayerIcon`、`UseDynamicIcon`、`ConfigIconMask`）。用户可以覆盖，但只接受结构合法、含非空 `ConfigIconMask` 的 XML。

## 路径与优先级

```text
基础静态 icons/<id>
  < 主题静态 themes/<style>/icons/<id>
  < 自定义 static/<id>
基础动态 themes/common/layer_animating_icons/<id>
  < 主题动态 themes/<style>/layer_animating_icons/<id>
  < 自定义 dynamic/<id>
```

禁用先于自定义生效，因此自定义可以重新提供被禁用的图标。

保留路径（高级附加不得覆盖）：`icons`、`dynamicicons`、`transform_config.xml`、`description.xml`、`module.prop`、`META-INF/`。

## 预览图与壁纸

两者都由构建过程生成，不打包任何第三方素材：

- 预览图 `preview/preview_icons_4x3_0.jpg`：从主题内静态图标中取最多 12 个，
  0/1 叠加后合成 4×3 网格，1080×1560，JPEG 质量 92。
  - 优先顺序来自**本机已安装且存在桌面入口的应用**，按应用名称排序：
    中文按拼音、英文按字典序（同一条 `SIMPLIFIED_CHINESE` Collator 序列）。
  - 只保留主题里确实存在的标识；未适配的应用会被跳过，剩余槽位用主题图标按
    标识字典序补齐，保证预览始终能填满。
- 壁纸 `wallpaper/default_wallpaper.png`：1080×2340 深色渐变，程序绘制。

设置页可分别关闭预览图、开启壁纸；两者互相独立。

## 高级附加组件

高级附加是主题组件原始文件，不属于图标。典型内容是主题包内的组件 zip（如 `com.android.systemui`、`framework-miui-res`），内部包含 `res/`、`nightmode/` 等资源；应用只按字节搬运，不关心其内容。

映射规则：

| 产物 | 路径 |
|---|---|
| 模块 | `system/media/theme/default/<文件名>` |
| MTZ | `addons/<相对路径>` |

- 原样复制，不解压、不重写、不改名。
- 模块侧平铺；同名冲突在构建前报错。
- 禁止占用 `icons`、`dynamicicons`、`transform_config.xml`。

## 脏文件与层级规则

- **静态分层只认 `0.png` 和 `1.png`**。
- 上游静态目录中出现的其它文件（如 `0.jpg`、`background.png`、`foreground.png`、`10.png`、`110.png`）属于脏文件，打包时忽略，不写入产物。
- 静态条目必须同时具备 `0.png` 与 `1.png`；缺失任一层视为不完整，跳过并告警。
- **动态分层不受层数限制**：`layer_animating_icons/<id>/` 内所有文件原样保留。
- 动态条目强校验：
  - 根级 `config.xml`，可解析；
  - 数字层从 0 连续，至少一层；
  - 每层 `quiet/quietImage.png`；
  - 每个 fancy 层 `fancy/manifest.xml`，可解析；
  - 无 fancy 的层必须在 config 中声明 `layer_only_quiet<N>=true`；
  - 至少一个 fancy 层。
- 根级 `quiet/quietImage.png` 可选；预览缺失时回退到第 0 层。
- 主题目录 `themes/<style>/icons/<id>/` 同样只取 0/1；单张主题图标 `<id>.png` 不受影响。

## 安装脚本

`customize.sh` 只做：

1. 检查 Android / HyperOS 版本。
2. 定位 `system/media`（兼容符号链接）。
3. 解压 `icons`、`dynamicicons`、`miui_mod_icons`、附加文件。
4. 设置权限。
5. 清理 `package_cache`。

不递归删除用户目录，不执行网络请求，不拼接未校验变量。
