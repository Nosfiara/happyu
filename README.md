# happyu

![happyu preview](src/main/resources/img/a7e3bab7-08c0-47ab-bfbd-2a78d3baf33b.png)
![happyu preview](src/main/resources/img/e638acac-0a5e-4f05-af77-1b1aa6707af8.png)

happyu 是一个给 IntelliJ IDEA 用的低调摸鱼浏览器插件。

它会在 IDEA 侧边的 happyu 工具窗口中打开网页，支持多个网页标签。插件默认屏蔽图片、视频、favicon 等媒体资源，并把网页尽量处理成文字优先的阅读模式。页面颜色会跟随 IDEA 当前主题，适合在休息间隙低调浏览搜索结果、热搜、榜单、文档和其他文字内容。

简单说：想看点网页，但又不想离开 IDEA，也不想让页面花花绿绿太显眼，就用它。

## 功能

- 点击右侧工具栏的 `happyu`，或通过 `Tools -> Open happyu` 打开浏览器工具窗口。
- 点击窗口标题栏的 `+` 新建网页标签；可切换、关闭标签，网页标题会自动更新到标签上。
- 标签较多时，将鼠标放在标签栏上滚动滚轮，可横向查看超出宽度的标签，当前页面保持不变。
- 自动保存未关闭标签页的网址、顺序、标题、图片开关和当前选中项，重新打开项目后恢复。恢复时只加载选中的网页，其他标签切换时再加载。
- 右键点击已加载的后台标签，选择 `Sleep Tab` 可手动休眠；休眠标签显示 `[Sleeping]`，点击后按最后的网址重新加载。
- 在 `Settings -> Tools -> happyu` 中可开启自动休眠并设置闲置时间（默认关闭，默认时长为 15 分钟，可设为 1～1440 分钟）。后台闲置从切换离开标签时开始计算，每约 30 秒检查一次，当前选中的标签不会休眠。
- 原本要求新窗口打开的网页链接会在工具窗口内新建标签。
- 可通过 IDEA 工具窗口菜单移动到左侧、底部，或切换为浮动窗口。
- 默认打开的首页为 `https://www.baidu.com/`。
- 可自由输入网址。
- 每个网页标签提供独立的地址栏、返回、前进、刷新、图片开关和缩放百分比选项。缩放菜单提供 25%～500% 的常用比例，`100% (Default)` 标识默认缩放；使用 `Ctrl+滚轮` 缩放页面时，菜单会同步实际比例。切换比例无需刷新页面，比例随标签页保存并在休眠唤醒后恢复。
- 默认屏蔽图片、favicon、视频等媒体资源，并注入 CSS 隐藏图片、背景图、视频、canvas 等元素。
- 快捷键 `Ctrl+Alt+I` 切换图片屏蔽状态。macOS 上如果和系统快捷键冲突，可在 IDEA 的 Keymap 里调整 `Toggle happyu Images`。
- 网页背景色、文字色、链接色会按 IDEA 当前明暗主题重新注入。
- 点击标题栏的对话图标可开关底部 Agent 风格输入面板，也可在 `Settings -> Tools -> happyu` 中设置；默认关闭，开关状态保存在 `.idea/happyu-settings.xml`。拖动面板顶部横条可调整高度，支持输入、`Enter` 本地发送、`Shift+Enter` 换行及模式/模型外观选择，只用于本地伪装，不连接模型、不发送或持久化输入内容。

标签页状态和休眠标记通过 IDEA 持久化服务独立保存在项目的 `.idea/happyu-tabs.xml`，休眠设置保存在 `.idea/happyu-settings.xml`，不写入 `workspace.xml`。它们保存标签页信息，不保存页面内容、前进后退历史或 Cookie，登录状态仍由 JCEF 管理。

休眠会释放该标签的浏览器实例，保留网址、标题和图片开关。唤醒时重新加载，未提交表单、页面内存状态和前进后退历史可能丢失。插件不会主动清除 Cookie，但部分网站仍可能需要重新登录。当前版本不会检测后台表单、播放或上传任务，开启自动休眠前请留意这些页面。

## 兼容版本

- IntelliJ IDEA 2024.1 到 2026.3 系列。
- 对应 IntelliJ Platform build：`241` 到 `263.*`。

## 运行

```bash
./gradlew runIde
```

如果本机已经安装 IntelliJ IDEA，也可以使用本地 IDE SDK：

```bash
gradle runIde -PideaLocalPath="/Applications/IntelliJ IDEA.app/Contents"
```

## 打包

```bash
./gradlew buildPlugin
```

生成的插件包在 `build/distributions/`。

## 浏览器卡住时收集诊断

Windows 下，如果 happyu 和其他 JCEF 插件同时卡住，但代码编辑正常，请在重启 IDEA 前运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Collect-JcefDiagnostics.ps1
```

脚本通过本机 JDK 的 `jcmd` 连续抓取 3 份原始线程转储，记录 IDEA/JCEF 进程信息，并复制最近的 IDEA/JCEF 日志。结果保存在 `build/diagnostics/jcef-时间戳/`，不会重启或终止 IDEA/JCEF。

如果同时运行多个 IDEA 实例，使用 `-IdeaProcessId 进程号` 指定卡住的实例；自定义日志目录时可传入 `-LogDirectory "日志目录"`。

## 开源协议

本项目基于 [MIT License](LICENSE) 开源。
