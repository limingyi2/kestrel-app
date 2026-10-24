# Kestrel 架构

Kestrel 是 [limingyi2/kestrel-app](https://github.com/limingyi2/kestrel-app) 中的 Android 应用，仓库通过 GitHub Fork 继承 [nanoMuse](https://github.com/nano-muse/nanoMuse)。Windows Brain 保留在 [Vigil](https://github.com/limingyi2/vigil)，本仓库没有复制 Brain，也不合并两套 Agent 内核。

## 边界 {#boundaries}

| 组件 | 源码与职责 | 当前状态 |
|---|---|---|
| Android | `android/src/android/app`，手机本地 Agent、模型配置、会话和权限 | 继承实现，本轮初始化品牌 |
| Linux 沙箱 | `com/openminis/app/sandbox`、`android/deps/proot`、rootfs 与 native offload | 完整保留 |
| Skills、MCP | 原有 Skills 仓库、MCP 客户端、CLI 与沙箱路径 | 保留运行契约 |
| Relay | `cloud/`，认证、设备中继、同步；计划部署首尔 Ubuntu | 继承源码，尚未部署或验证 Kestrel 服务 |
| Windows Bridge | 适配现有 Vigil Brain 接口 | 仅设计，没有复制 Brain |
| Protocol | 任务标识、状态回执、权限范围 | 下述提案，尚未实现传输 |

数据流：Android 本地 Agent 使用本地 Linux 沙箱；跨设备任务经过首尔 Relay、Windows Bridge 到现有 Vigil Brain。继承的桌面插件、Python runtime、iOS 源码保留以便上游合并，不是 Kestrel 的 Windows Brain，本轮不发布这些产品。

## Android 与沙箱契约 {#android-and-sandbox-contracts}

安装 ID 为 `io.github.limingyi2.kestrel`。Kotlin 包名和 Gradle namespace 保留 `com.openminis.app`、`io.github.nanomuse.*`。外部链接使用 `kestrel://`，`OwnScheme` 转为内部 `minis://`，不向系统注册 `minis` 或 `nanomuse`。独立安装 ID 对应独立应用数据，不能覆盖升级 nanoMuse。

保留 `/var/minis`、`minis-global`、`MinisSkills`、`minis-mcp-cli`、`android-*`、`X-Minis-Token`、rootfs 布局和 offload 帧。socket 继续由 `OffloadSocketName.forApp(BuildConfig.APPLICATION_ID)` 派生为 `io.github.limingyi2.kestrel.native-offload`，保留 PID 回退和向 proot 传入实际绑定名的机制。

继承的 nanoMuse Cloud 地址和账号流程仍然存在，并保留真实服务名称。它们不代表已经接入 Kestrel 首尔服务器。自建中继需要运维提供地址、有效 TLS 和账号配置。本仓库不写入私有主机或凭据。正式发布前必须验证登录、隐私、同步及 hub 流程，说明数据去向。

## Relay 与 Windows Bridge {#relay-and-windows-bridge}

Relay 负责账号及设备认证、任务路由、持久化状态与回执、账号隔离、配额、过期和防重放，不成为新的 Agent 内核。保留现有 `/v1/hub` 兼容性，阅读 [hub.md](hub.md)、[cloud.md](cloud.md) 后再实现有明确版本的适配层。

Bridge 从 Windows 主动建立经过认证的 TLS 连接，将已授权任务转换为 Vigil 现有 API 调用，再返回进度与结果。实施前先检查真实接口。不直接把 Brain 暴露到公网，不复制其源码或迁移存储，不把中继认证等同于工具执行授权。

## 任务协议提案 {#proposed-task-protocol}

以下是集成提案，不代表现有 hub 已实现。

| 字段 | 规则 |
|---|---|
| `protocol_version` | `kestrel.task.v1`，明确拒绝未知主版本 |
| `task_id` | 发起端生成 UUIDv4，跨设备及重试保持不变 |
| `attempt_id` | 每次执行尝试生成新的 UUIDv4，已确认尝试不能重复执行 |
| 账号与设备 | 中继从认证信息确定账号，核对源设备与目标设备归属，不信任请求中的账号文本 |
| `parent_task_id` | 可选委派关联，不代表授权 |
| `created_at`、`expires_at` | UTC RFC3339，过期不得开始，校验时钟偏差 |
| `sequence` | 同一尝试内单调递增，重复回执幂等，拒绝冲突重放 |
| Payload | 有类型的操作与参数、大小限制、内容摘要，不携带模型 Token 或签名私钥 |
| Permission | 经过认证的证据绑定操作、规范化参数摘要、目标设备、批准者、过期时间和一次性 nonce |

回执状态为 `accepted`、`awaiting_permission`、`running`、`succeeded`、`failed`、`cancelled`、`expired`，包含任务与尝试 ID、序号、UTC 时间和可选错误或结果引用。`accepted` 只表示持久化收到，不表示完成。终态不可变，重连从最后持久化序号恢复。取消需要目标端确认，断线不等于成功；结果未知时先对账再重试有副作用的操作。

每个执行端保留自己的权限门。需要时同时经过 Android 与 Vigil 授权。缺失或过期批准、参数摘要不符、未知风险类型都拒绝执行。设备连接信任不自动允许支付、破坏性操作或凭据访问，单步批准不能挪给后续步骤。日志脱敏，大结果使用账号隔离且会过期的访问引用。

## 上游更新 {#upstream-updates}

```sh
git remote -v
git fetch upstream --tags
git switch -c chore/update-nanomuse
git merge upstream/main
python scripts/rebrand.py
python scripts/rebrand.py --check
```

对照 `scripts/rebrand.py` 中保留的上游实现审查冲突；默认入口使用仅限 Android 的 `scripts/kestrel_brand.py` 白名单，不调用 `upstream_main` 初始化 Kestrel。图标生成器需要明确选择临时或正式源资产后才能运行。保留 LICENSE、NOTICE、第三方声明、`.gitmodules` 和子模块 SHA。通过 PR 和 Android 验收后再合并，不向 Vigil 远端推送。

## 发布前验收 {#acceptance-before-release}

使用继承的 Android CI 构建 arm64 debug，包括 proot/rootfs/rclone、`assembleDebug`、指定单测、instrumentation 编译和 APK badging，核对成品名称及安装 ID。本轮不生成正式 APK、不添加签名密钥、不打发布标签。

在真实 arm64 手机与 nanoMuse 并存安装，启动 Linux shell，通过 native offload 执行命令，导入并执行 Skill，连接 MCP，打开附件和 `kestrel://settings/soul`，验证权限允许、拒绝和重启。模拟器选项只能验证聊天，不能验收沙箱。Bridge 实现后再完成 Android、首尔 Relay、Windows 联调。
