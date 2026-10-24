# Hands：单模型视觉决策与连续操作

根因审计与相应的最小改造。基线：PR #6 合并后的 `main`（`5fb427ed`）。设备背景：
小米 15 Pro；用户反馈的症状是拼多多搜索任务长时间停留在「正在看屏幕…」并反复观察
页面而不点击。

## 真实调用链

```
用户指令 → 对话 Agent 循环（ChatViewModel，对话模型）
  → 模型发起 shell_execute: nanomuse-hands run --task …
  → NativeOffloadServer → HandsOffloadHandler.run（整个任务期间阻塞）
  → HandsOperator.run，每一步：
       胶囊状态「正在看屏幕…」                       (nm_hands_looking)
       通过无障碍服务截图                            （隐藏胶囊，最多两次尝试）
       JPEG 720px 编码 + 灰度指纹                    （循环看护、黑屏检测）
       ScreenWords.visibleControls                   （有界的实时控件标签表）
       provider.sendMessage(…, AgentContentPart.ImageData)  ← 原始截图直接发给
                                                       用户配置的 Hands 模型
       HandsAction.parse                             （单个动作或 2–6 步计划）
       过期观察拦截                                   （见 R2 —— 问题热点）
       本地执行                                       （唯一节点点击或手势注入、
                                                       风险审批、带验证的输入）
       事件驱动的页面稳定等待                          （HandsPageObserver，0.9–2.8s）
  → 结果 JSON {outcome, answer, steps, last_screen, trace, log} → 对话 Agent
  → 对话模型可能对 last_screen 调用 read_image（模型自主发起，并非代码自动调用）
```

## 审计问题的回答

1. **Hands 是否把原始截图直接发给配置的多模态模型？** 是。`HandsOperator.buildMessages`
   把当前截图包成 `AgentContentPart.ImageData`，经常规 `ProviderFactory` 路径直接调用
   用户配置的 Hands 模型（`Hands.screenModel`）。整个 Hands 循环中不存在任何中间描述
   模型。Vision Group 只在对话模型本身无视觉能力时影响对话侧的 `read_image` 工具。
2. **模型返回的动作是否由本地执行器执行？** 是。点击优先落到唯一匹配的可见可点击
   无障碍节点（否则用模型给出的网格坐标），先过风险审批，再以无障碍点击或注入手势
   执行。输入用 `ACTION_SET_TEXT` 并校验字段值。
3. **每一步为什么触发新的模型请求？** 设计如此：每步一个「感知→决策→执行」循环，
   因为每个动作都会改变屏幕。除此之外，有三条路径会在不执行动作的情况下消耗额外
   请求：过期观察拦截（R2）、格式错误（至多重试 3 次）、模型错误（至多 3 次，退避
   1.5 秒）。
4. **`read_image` 是否被外层 Agent 自动调用？** 没有任何代码路径自动调用它。但系统
   提示词原先告诉 Agent `last_screen`「可以用 `![screen](<last_screen>)` 展示」，模型
   常常会先调用 `read_image` 看一眼再展示——这就是用户看到的重复读图。已通过提示词
   措辞 + 工具结果中的显式说明修复（F3）。
5. **各阶段耗时？** 已有完整埋点：每步写入 `trace.jsonl`（`timings_ms`：截图采集/
   编码/保存、无障碍控件、模型、风险审批、瞄准等待、动作执行、页面稳定等待），
   每次运行汇总到 `run-summary.json`（总量、次数、里程碑、返回尝试）。
6. **是否存在重复读取/重复判断同一页面？** 没有对同一张截图的重复读取，但由于 R2，
   同一页面被反复重新观察和判断；而且这些被跳过的步骤不经过 `HandsLoopWatch`，
   循环看护完全看不到它们。超时后重复推理只发生在模型错误重试路径（上限 3 次）。

## 「Requested focus」的来源

`ChatViewModel.executeReadImageTool`（ChatViewModel.kt:9858）。当对话模型具备原生
视觉且 `read_image` 调用带 `prompt` 参数时，工具把自己的文本输出追加
`[Requested focus: …]`。它只是工具结果里的元数据，本身不会触发另一次模型调用。

## 根因

- **R1 —— 所有阶段共用一个状态文案。** 胶囊从步骤开始到模型返回一直显示
  `nm_hands_looking`（「正在看屏幕…」），也就是覆盖整个截图 + 模型调用（模型超时
  上限 90 秒），每次审批之后还会再设一次。模型调用慢和卡死无法区分。
- **R2 —— 过期观察拦截丢弃了正确的决策。** 每一步开头（截图之前）都会新建一个
  `HandsPageObserver`。观察开始到模型决策之间的任何无障碍内容事件（动画、时钟、
  轮播——拼多多首页持续产生这类事件）都会把 `changed()` 置位，于是已决定的动作被
  以 `stale_observation_skipped` 丢弃，除非这次点击恰好能落到唯一节点上重新定位。
  下一步在几乎相同的屏幕上重新截图、重新决策。由于跳过发生在 `HandsLoopWatch.step`
  之前就直接 `continue`，这种循环可以远超循环看护的界限持续下去——表现为「模型
  一直在看，迟迟不点」。
- **R3 —— 外层 Agent 重复读取最终截图。** 原提示词措辞引导模型把 `last_screen`
  当作需要查看的图片；`read_image` 随后重新解码一张 Hands 模型已经看过的 JPEG，
  并多消耗一轮模型调用。
- **R4 —— 固定的稳定等待下限带来延迟。** `TAP_SETTLE_MS`（900ms）、
  `SWIPE_SETTLE_MS`（1100ms）、`OPEN_APP_SETTLE_MS`（1800ms）是下限；页面观察在
  事件稳定时通常会更早结束，但安静无事件的页面总是要付满下限。保持不变——事件
  路径已经实现了有明确目标的等待，再压缩下限有在页面切换中途点击的风险。

## 修复

- **F1 —— 实时目标直接执行；坐标回退必须先验证（R2）。** 内容事件之后如何处理按定位
  方式区分（`HandsStalePolicy`，纯 Kotlin、有单测）：落在唯一实时无障碍节点上的点击
  直接执行，因为执行器会在执行时重新解析节点；回退到模型截图坐标的点击必须先通过与
  模型所见截图逐字节一致的新鲜度校验（`VERIFY`），无法重新验证的坐标绝不点击
  （`stale_coordinate_rejected`）。窗口切换一律重新观察——包括 `status` 结论：否则
  会接受一个针对已消失屏幕的完成声明。非点击动作保留各自的实时校验（输入和 Enter
  要求聚焦字段），危险点击保留审批时的新鲜度校验。跳过次数计入 trace 和运行汇总。
  校验路径对持续动态的页面有界：像素不一致时，若坐标处的*实时*无障碍标签仍与模型
  所称目标吻合，则视为未过期并直接执行（`ACT_ON_LABEL`——支付/发送类点击绝不适用
  此豁免）；无此证明则重新观察，连续失败达 `MAX_COORDINATE_VERIFY_FAILS`（3）次后
  以 `coordinate_verify_exhausted` 结束运行并把原因写给用户，等待介入。预算按目标
  计：模型改变策略或真实页面切换都会重新计。过期坐标绝不会被点击。
- **F2 —— 分阶段胶囊状态（R1）。** 新增文案区分：观察屏幕、判断下一步、执行操作、
  等待页面响应、验证操作结果，覆盖全部 16 个语言文件。步骤之间胶囊显示通用工作
  标题，不再出现「第 0 步」。
- **F3 —— 消除重复 `read_image`，带行为级约束（R3）。** 提示词段落明确：运行结果
  文本是权威的，`last_screen` 用内联图片展示（`![screen](<last_screen>)`）、不得
  `read_image` 重读；运行结果 JSON 携带同样说明。在提示词之外，`ReadImageDedup`
  按用户回合作行为级强制：*已完成*运行（`outcome: done`，由 shell 工具结果登记）
  的 `last_screen` 在本回合内完全不读——首次 `read_image` 即返回说明文本，因为
  Hands 模型已判断过这些像素、答案已在结果里；失败、停止与需用户输入的运行不登记，
  运行出问题时 Agent 仍可看图。其余 Hands 截图每回合可读一次，重复读取返回说明。
  闸门以运行 id 和文件名为键，结果里的 `minis://` URL 与读图解析出的宿主路径落到
  同一条目。普通图片完全不受限，Vision Group 路由不动，新的用户消息会重开两个闸门。
  每次 `read_image` 执行都带来源写入日志。
- **F4 —— 诊断数据（任务 §6）。** `run-summary.json` 新增 `stale_observation_skips`
  与 `coordinate_verify_failures`；trace 区分 `stale_observation_skipped`、
  `stale_coordinate_rejected` 与 `coordinate_verify_exhausted`。

其余一切——模型选择顺序、多模态请求路径、动作协议、计划执行、风险审批、取消
机制、审计记录、返回 Kestrel 策略——均未改动。

## 测试

JVM 单元测试覆盖抽出的决策点：过期观察策略（窗口变化 vs 仅内容变化、节点定位点击 vs
必须证明新鲜度的坐标回退、窗口切换后的完成声明重新验证、节点移动、取消）、有界坐标
回退的端到端模拟（在持续动画的首页上驱动每步完整决策序列：实时标签吻合则每次都执行
点击；不吻合则恰好重新观察 MAX_COORDINATE_VERIFY_FAILS 次后明确终止）、Hands 截图
读取的行为级闸门（已完成运行的 `last_screen` 首次读取即被拦截，`minis://` 与宿主路径
两种形式一致；失败或停止的运行仍可读；普通图片不受限；新用户消息重开），以及禁止对
`last_screen` 重复调用 `read_image`（同时保留内联展示）的提示词契约。现有 Hands
action、plan、page-state、loop-watch 测试套件必须继续通过。本文不声称任何真机提速
数据：CI 结果与小米 15 Pro 真机结果必须分开报告。
