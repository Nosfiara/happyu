# happyu

![happyu preview](src/main/resources/img/a7e3bab7-08c0-47ab-bfbd-2a78d3baf33b.png)
![happyu preview](src/main/resources/img/e638acac-0a5e-4f05-af77-1b1aa6707af8.png)

happyu 是一个给 IntelliJ IDEA 用的低调摸鱼浏览器插件。

它会在 IDEA 侧边的 happyu 工具窗口中打开网页，支持多个网页标签。插件默认屏蔽图片、视频、favicon 等媒体资源，并把网页尽量处理成文字优先的阅读模式。页面颜色会跟随 IDEA 当前主题，适合在休息间隙低调浏览搜索结果、热搜、榜单、文档和其他文字内容。

简单说：想看点网页，但又不想离开 IDEA，也不想让页面花花绿绿太显眼，就用它。

## 功能

- 点击右侧工具栏的 `happyu`，或通过 `Tools -> Open happyu` 打开浏览器工具窗口。
- 点击窗口标题栏的 `+` 新建网页标签；可切换、关闭标签，网页标题会自动更新到标签上。
- 原本要求新窗口打开的网页链接会在工具窗口内新建标签。
- 可通过 IDEA 工具窗口菜单移动到左侧、底部，或切换为浮动窗口。
- 默认打开的首页为 `https://www.baidu.com/`。
- 可自由输入网址。
- 每个网页标签提供独立的地址栏、返回、前进、刷新和图片开关。
- 默认屏蔽图片、favicon、视频等媒体资源，并注入 CSS 隐藏图片、背景图、视频、canvas 等元素。
- 快捷键 `Ctrl+Alt+I` 切换图片屏蔽状态。macOS 上如果和系统快捷键冲突，可在 IDEA 的 Keymap 里调整 `Toggle happyu Images`。
- 网页背景色、文字色、链接色会按 IDEA 当前明暗主题重新注入。

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
