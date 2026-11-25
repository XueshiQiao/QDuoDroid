# QDuoDroid (QDuo for Android)

Android 版 **QDuo**：结合 **低功耗双通道无障碍选词感知**、**Shizuku 全局剪贴板监听** 与 **系统选词/分享直出**，实现类似 macOS PopClip / QDuo 的全系统悬浮动作条与边缘自适应底部结果卡片。

## 核心功能

- **多通道零轮询触发（纯事件驱动）**：
  1. **选词即弹（无障碍双通道）**：严格只订阅 `TYPE_VIEW_TEXT_SELECTION_CHANGED`、`TYPE_WINDOW_STATE_CHANGED` 与 `WINDOWS_CHANGE_ADDED`（不监听滚动和内容变化），在支持选区读取的应用内直接弹出悬浮条；在 Chrome / 微信 / 小红书等不暴露文本的界面检测到浮动选词条后弹出悬浮条，点击动作自动代点「复制」并通过 Shizuku 毫秒级回传文本。连接 Shizuku 后支持自动授予 `WRITE_SECURE_SETTINGS` 并一键开启无障碍。
  2. **Shizuku 全局剪贴板监听**：通过 Shizuku `UserService`（`com.android.shell`）挂载系统 `IClipboard` 监听（兼容 Android 11–16+ 反射签名，无后台轮询），复制文本即弹出胶囊悬浮条。
  3. **系统 `PROCESS_TEXT` & `SEND` 入口**：系统选词菜单「QDuo」与纯文本分享直出底部结果页，零切换动画、不拉起主界面。
- **真实翻译与多服务商大模型（移植自 macOS QDuo `AIModelKit`）**：
  - **翻译**：默认直连 Bilibili 免费翻译（`Index-Translate-35B-A3B`），无需 API Key，或可切换使用已配置大模型。
  - **润色 · 总结 · 解释 · 搜索 · 朗读**：支持 DeepSeek / OpenAI / Anthropic / Gemini / Qwen (DashScope) / Doubao (Ark) / 自定义网关，填入 Key 后自动拉取 `/models` 列表并点选，**默认关闭思考（Gemini 默认低档 `low`）** 以保证划词秒回。
- **双层防冻结与后台保活（适配 OriginOS 等国产 ROM）**：
  1. **第一层（后台白名单）**：连接 Shizuku 时自动写入 `deviceidle whitelist` 与 `RUN_IN_BACKGROUND` / `RUN_ANY_IN_BACKGROUND`。
  2. **第二层（同步 Binder 探活 + `am` 强行解冻唤醒）**：当主进程被系统冻结导致同步回调失败时，Shizuku 子进程暂存文本并通过 `am start-foreground-service` 借助 `AMS` 强行解冻唤醒主进程。
- **应用内触发路径统计与实时日志**：首页直观展示各通道命中次数、最近触发来源、无障碍实际扫描 vs 噪音过滤次数及最多 150 条分路径日志。

## 构建与安装

```bash
# 运行单元测试与编译 Debug APK
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon

# 产物位置（文件名带版本号和 build 号，例如 QDuoDroid-v0.3.6-build20-debug.apk）
ls -lh app/build/outputs/apk/debug/

# ADB 安装（需手机亮屏解锁并允许 USB 安装）
adb install -r app/build/outputs/apk/debug/QDuoDroid-v<版本号>-build<build号>-debug.apk
```

## 架构与踩坑笔记

关于双层防冻结原理、`ShizukuProvider` 声明、`IBinder.DeathRecipient` 防僵尸进程及 OriginOS 实测排查详情，见：
- `~/dotfiles/notes/android-Shizuku-防冻结与后台剪贴板唤醒架构指南.md`
