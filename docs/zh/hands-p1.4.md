# Hands P1.4 测量与审查

## 修改前的诊断 {#diagnosis-before-changes}

基线为 PR #3 的 `02e7594c`，分支 `feat/kestrel-hands-optimization`。本次从该分支创建独立分支，不合并旧 PR。尚未取得真机 trace，不能仅凭源码把十几秒延迟归因于模型。

Android 主 Agent 收齐工具调用后，才交给 shell/offload 调度。`HandsOffloadHandler` 检查就绪状态并占用操作器，`HandsOperator` 随后解析模型和应用，按需打开应用，截图、编码、保存，请求视觉模型，进行风险审批、显示瞄准动画、执行 Android 动作并等待下一次截图。现有 provider 首块诊断测量的是模型 TTFB，不是指令到首次 Android 动作。继续使用 PR #3 的每步计时字段。

源码确认：

- 每次点击后，包括唯一无障碍节点定位的点击，下一次决策都需要截图和视觉模型。没有动作队列。
- PR #3 在点击后等待 900 毫秒，滑动后 1,100 毫秒，打开应用后 1,800 毫秒，再轮询事件。事件边界晚于动作，可能漏掉切换事件。应用提示路径也固定等待 1,800 毫秒。
- 模型失败后会在等待 1,500 毫秒后复用旧截图；重试期间页面可能已经变化。本次改为重试重新截图。
- 打开应用前没有检查它是否已经处于前台。
- 审批可能远长于模型推理，使原本正确的坐标失效。

## 实现行为 {#implemented-behavior}

在观察和动作前订阅事件。区分窗口、内容、滚动及文本变化；通知和 Kestrel 浮层事件不能证明页面就绪。发生变化后，安静 250 毫秒、有可见内容且没有可见 ProgressBar 时，可以采集下一张截图。这是去抖策略，不能证明网络请求已经完成。持续动画有等待上限；没有可靠事件或节点时保留原动作等待回退，持续变化最多额外等待 1,000 毫秒。下一次模型决策始终读取新截图。

推理期间窗口切换时丢弃观察。内容变化时，普通点击可通过当前唯一节点重新定位；其他动作重新观察。点击在审批和瞄准后重新检查页面变化、唯一目标及其边界。唯一、可见、启用、精确匹配的节点通过原风险审批后使用 `ACTION_CLICK`。有歧义时仍使用视觉坐标；危险坐标点击在审批后必须重新截图并与原图完全一致，否则重新观察。节点点击失败时不会追加坐标点击。长按和双击保留手势。Enter 在审批后重新检查页面变化。已在前台的应用不再重复打开。停止、风险审批及 PR #3 的返回确认与停留设置保持有效。

已调查跳过下一次视觉决策的可能性，但暂不实施：唯一标签不能证明用户下一步的意图。没有添加设置专用路径，也没有推断执行危险动作序列。

## Trace 与报告 {#trace-and-reporting}

`run-summary.json` 增加单调时钟的 `milestones_ms`，相对已接受的用户消息计时：指令接收、首次可见主 Agent 文本或工具卡片、Hands 调用、工具派发、原生接收、操作器启动、首次截图、首次视觉响应、首次动作派发、页面变化、下一次动作及任务结束（包含返回确认）。`android_action_N` 记录每次派发尝试。直接 shell 或后台调用缺少用户指令节点时保留缺失值。`task_finished` 表示 Hands 返回结束。主 Agent 流式回复结束后，在 IO 线程补写 `agent_task_finished`，用于整体任务耗时及主 Agent 收尾占比；若未观察到结束，保留缺失值。复用已有 provider 首块诊断和 PR #3 单步计时，不重复记录。

运行 `python scripts/android/hands-report.py <exported-hands-directory>`，输出最近秩法 P50/P95、样本数、每次模型和页面等待耗时、动作尝试次数、返回结果及结束状态成功率。叶阶段占比的分母是操作器总耗时。排除包含子阶段的 `action_and_wait_ms`，未归属时间单独显示。没有 trace 时输出 `pending_device_measurement`，分位数与成功率为 null。旧 PR #3 trace 可用于单步计时，但不能重建指令到首次动作耗时。

## 同设备对照验收 {#paired-physical-device-acceptance}

任务：「打开系统设置，进入关于手机，读取 Android 版本。」

两版本使用同一 arm64 手机、Android 构建、模型与服务商、网络、凭据、语言、权限、Hands 设置及初始前台状态。应用提示参数保持一致，记录基线及候选提交与 APK 哈希。交替运行，每个版本至少 20 次，并标明冷启动或热启动。安装前比较证书；未备份本机数据时不得卸载旧应用。

通过设备可用的附件或文件导出方式取得每次 trace 和 summary。标注手机型号、Android 版本、模型 ID、网络、预期与实际读取版本、点错、重复点击和实际前台包名。同时报告操作器 `done` 比例与人工核实的版本读取成功率。分别验证自动返回及停留模式，以及模型等待、页面等待、审批时取消，审批拒绝、模型超时、截图失败、重复标签、弹窗、动画、无节点和设置已在前台的情况。编译与单测不能替代这些真机测量。

| 必需结果 | 基线 | P1.4 |
| --- | --- | --- |
| 指令到首次动作 P50/P95 | 待真机测量 | 待真机测量 |
| 模型调用次数与每次耗时 | 待真机测量 | 待真机测量 |
| 页面切换耗时 | 待真机测量 | 待真机测量 |
| 动作尝试、点错、重复点击 | 待真机测量 | 待真机测量 |
| 核实后的成功率及返回或停留行为 | 待真机测量 | 待真机测量 |

没有成对真机数据时，不声称性能改善或任务成功率。

## 无障碍开关关闭调查 {#accessibility-switch-investigation}

用户设备为小米 15 Pro、澎湃 OS 4，确认系统开关实际变为关闭，并非开关仍开启但连接故障。尚无 APK 版本、事发时间及设备日志，本次具体原因未确定。

Hands 源码没有 `disableSelf()` 或自动关闭授权的设置写入。现有恢复路径只在用户选择修复后添加启用项。但通用、经授权的 Shizuku 工具能够强停指定包或写入 secure 设置，排除 Agent 操作前也必须核对工具历史。[AOSP 强停处理代码](https://android.googlesource.com/platform/frameworks/base.git/+/6953b903c2457a26cd1288691261a2b02ac70a46/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java)会从启用列表移除被强停应用的服务。这只是可能机制，不能证明本机这次发生了强停。服务断线与授权移除需要分别诊断；单独出现崩溃或低内存退出，无法确定是谁更改了系统设置。

`nanomuse-hands status`、拒绝运行的结果和 run summary 现包含只读的 `accessibility_health`：系统授权状态、实际服务连接、最近 16 条生命周期事件，以及最多五条 Android 进程退出原因和时间。生命周期记录不包含界面内容。缺失或读取失败的证据保持未知。记录 `onInterrupt`，但不会据此认定连接或授权失效。没有增加自动恢复权限。

安装本构建后，如再次发生，请在重新打开开关前取得 `nanomuse-hands status`，并记录安装版本和大致时间。将 `permission_disabled` 与 `enabled_disconnected`、生命周期及进程退出记录，与聊天中的 shell/Shizuku 调用对齐。如果可以使用 ADB，保存以下只读输出：

```sh
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys accessibility
adb shell dumpsys activity exit-info io.github.limingyi2.kestrel
adb logcat -d -v threadtime
```

核对事发前后的系统强停记录、调用方和原因、服务断连、崩溃或 ANR，以及工具调用。分享日志前遮盖其他应用、屏幕内容和隐私信息。后台自启动与省电设置属于调查变量，尚不能称为本次故障的已验证修复。
