# Android App

Kestrel 是一款以本地运行优先的 Android 智能体应用，基于 OpenMinis 1.13 GPL-3.0 版本开发。聊天记录、智能体数据、服务商配置和凭据都保存在设备上。使用 Kestrel 不需要账号，Android App 不会请求 nanoMuse Cloud 的账号、额度、资料或聊天同步接口。你可以为服务商配置 API key，或使用该服务商支持的认证方式。Android App 会保存服务商凭据，并且只在该服务商处理请求时发送凭据。

App 保留上游 `com.openminis.app` Kotlin 包名和 Linux 沙箱约定。Kestrel 代码位于 `io.github.nanomuse.*` 下；修改上游文件时会标记 `// nanoMuse:`。App ID 为 `io.github.limingyi2.kestrel`。系统收到的链接使用 `nanomuse://`，App 内链接仍使用上游的 `minis://` 名称。

## 安装和首次启动

在 Android 8.0 或更新版本安装 arm64 APK。首次打开时，Kestrel 会显示「欢迎使用 Kestrel」，用户可以选择「配置模型」或「暂时跳过」。跳过会保存引导完成状态并直接进入主界面，不要求 Provider、API Key 或模型；重启后也不会再次强制显示引导。模型和 Hands 页面也可以跳过。之后可在「设置 → 模型」添加服务商。没有聊天模型时发送消息会显示配置提示，不会启动模型请求。会话和配置会保存在设备上。

「设置 → 模型」分别管理聊天、操作屏幕、生成图片、生成视频和 Hands 所用的模型。内置服务商目录提供端点预设和能力信息。原有服务商类型、API key 保存、服务商 OAuth、自定义端点、模型组和模型发现仍可使用。保存的 API key 不会发送到 nanoMuse Cloud。

升级时，Kestrel 会在本机移除旧版 nanoMuse Cloud 服务商和已保存的账号 Token，不会联系中继服务器。现有会话会转为本地会话并继续显示。App 不再初始化旧版账号资料同步或聊天同步任务。旧的账号和数据控制链接会显示 Kestrel Relay/Bridge 暂停接入的说明。

## 本地智能体和安全机制

| 组件 | Android 功能 |
| --- | --- |
| Linux 运行时 | 保留 Alpine rootfs、proot、Shell、文件、浏览器、记忆、定时任务和 Android 原生 offload handler。rootfs 路径、命令名称、socket 协议和 Agent Core 均保持不变。 |
| Skills 和 MCP | 保留 Agent Skills、内置连接器目录、自定义 MCP 服务器及其服务商认证流程。 |
| Hands | 可选的无障碍和悬浮窗权限可让智能体操作手机。现有本地权限授予、Sentinel 风险检查和审批卡片继续生效。 |
| 文件和会话 | 聊天会话、附件、记忆、目标、例程、动态、形象和服务商配置会保存在 App 存储中，并在重启后保留。 |
| 跨设备 Reach | Android 源码保留 Reach 和 Hub 实现及线协议。依赖账号认证的跨设备入口暂时停用，等待 Kestrel Relay/Bridge。旧 nanoMuse 账号 Token 不会用于启动 Hub socket。 |

### Hands 耗时与任务结束

Hands 会把每一步的耗时写入现有 JSONL trace，包括截图采集、图像编码和保存、屏幕模型请求、无障碍节点查找、风险审批、Android 动作，以及稳定等待或模型指定的等待。`run-summary.json` 会记录结束状态和端到端耗时。P1.4 在动作前订阅窗口与内容变化事件，变化后安静 250 毫秒且有可见内容、没有进度条时即可重新截图；事件或节点不足时仍保留点击 900 毫秒、滑动 1,100 毫秒的回退，持续变化最多额外等待一秒。详见 [P1.4 诊断与真机测量](hands-p1.4.md)。模型请求超时为 90 秒，截图失败最多重试一次。

任务成功或发生错误后，默认返回 Kestrel 聊天。可在 Hands 设置关闭「任务结束后返回 Kestrel」，让界面留在目标应用。用户取消或任务需要用户继续处理时也会留在目标应用。点击停止会取消正在进行的模型请求，并打断模型要求的等待。风险审批被拒绝时任务会停止，不会绕过审批。如果 Android 阻止应用切换，Kestrel 不会绕过系统限制，可通过前台通知或桌面图标重新打开。每次运行都会在 `run-summary.json` 和 Hands 工具结果中记录 `return_requested`、`launch_attempted`、`launch_accepted`、`foreground_confirmed` 及失败原因。`launch_accepted` 只表示 `startActivity()` 已返回；只有 `MainActivity` 恢复后才报告前台返回成功。Android 可能在不抛异常的情况下阻止后台启动。

CI 没有真实设备耗时基线。请在 arm64 真机上验证「设置 → 关于手机」流程，检查每一步 trace 与端到端耗时，并测试成功返回、停留模式、用户取消、审批拒绝、模型超时和截图失败。完成这些真机验证后才能把耗时作为设备基准。

Shell、文件、Hands、浏览器、MCP 和远程操作审批仍走现有本地权限和沙箱路径。移除账号系统不会扩大工具权限。

## 构建和测试

仓库 CI 使用 JDK 21、Android SDK CMake 3.22.1、NDK r27c 和 Go 1.25+ 构建原生依赖。缺少原生依赖时，先构建依赖，再执行：

```sh
cd android/src/android
./gradlew :app:testDebugUnitTest --console=plain --no-daemon
./gradlew :app:assembleDebug --console=plain --no-daemon
```

CI Debug APK 保留现有 App ID。可信任的 `push` 和 `workflow_dispatch` 构建使用专门的测试 PKCS12 签名密钥；密钥仅存放在仓库 Actions Secrets：`KESTREL_DEBUG_KEYSTORE_BASE64`、`KESTREL_DEBUG_KEYSTORE_PASSWORD`、`KESTREL_DEBUG_KEY_ALIAS` 和 `KESTREL_DEBUG_KEY_PASSWORD`。缺少任意一项时工作流会失败，不会静默使用 runner 临时密钥生成可安装 CI 包。Pull request 构建不会接收签名 Secrets，产物会标记为临时签名，不应用于升级安装。不得将私钥提交到仓库或用作正式发布密钥。CI `versionCode` 规则是 `50 + GITHUB_RUN_NUMBER`；同一 Android workflow 的运行编号全局递增，因此每个 CI 构建版本号更高。安装前请核对 Badging 步骤中的 App ID、版本号和证书 SHA-256。签名证书不同的 APK 无法覆盖已安装应用；卸载会清除本机数据。

选择 arm64 ABI 后，Debug APK 位于 `android/src/android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。模拟器使用 x86_64，请在 Android 真机上测试 arm64 APK。

## 延伸阅读

- [Android 沙箱和设备功能](every-device.md)
- [Hands 和操作器](gui.md)
- [服务商 key](own-key.md)
- [权限和隐私](privacy.md)
- [构建 Android App](../../android/BUILDING.md)
