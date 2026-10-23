# Kestrel Hub 与 Vigil Bridge

Kestrel 跨设备连接复用 nanoMuse Cloud 现有账号接口、`/v1/devices` 设备发现和版本化 `WS /v1/hub` 帧，不新增配对流程或任务协议。账号登录是可选的。模型凭证、聊天记录、会话、Linux 沙箱、Skills 和 MCP 仍保存在各自设备上，除非用户主动使用已有的外发功能。

## 源码审计

| Cloud 开关或配置 | 对 Hub 的影响 | P4 建议 |
|---|---|---|
| `cloud_service` | 控制账号接口，并关闭所有 Hub 连接。 | 必须开启。使用账号认证和 Hub 时不能关闭。 |
| `hub` 运行时开关 | 控制 `/v1/hub` 和 `/v1/devices`。 | 设备发现需要开启。 |
| `HUB_ENABLED` | 不注册 Hub WebSocket 路由，也会关闭 `/app` 控制台。 | 保持开启。 |
| `sync` | 只控制 `/v1/sync/*`；不影响 Hub 在线状态、调用和设备列表。 | 不需要跨设备会话同步时可以关闭。 |
| `free_allowance` | 控制模型额度，不影响登录或 Hub。 | 仅提供账号和设备转发的 Relay 可以关闭。 |
| `signups` | 阻止新账号申请验证码；已有账号仍可登录。 | 创建好需要的账号后，可以关闭。 |
| `UPSTREAM_KEY` | 用于 Cloud 托管模型请求，Hub 不使用它。 | 部署时留空。Kestrel 模型 API Key 不需要发送到 Relay。 |

`cloud_service` 开启时，Relay 仍是完整服务，账号、资料、模型、媒体和管理路由仍存在。当前没有仅开放 Hub 的 API 白名单。`UPSTREAM_KEY` 留空会让托管模型请求失败关闭，但部署仍应使用管理开关、强随机 `CLOUD_SECRET`、防火墙和日志检查。Bridge 不暴露 Vigil HTTP API，只向外建立 TLS WebSocket，并且只响应现有 `info` 动作，其他动作一律拒绝。

Hub 凭证按账号和设备安装分别签发。Android 通过现有邮箱验证码接口，以 `device_kind=phone` 和持久安装 ID 获取凭证；Vigil 使用 `device_kind=computer`，并以 Windows DPAPI 加密保存凭证。Relay 会看到账号标识和设备发现所需的元数据。Android 的 Hub-only 登录流程不会上传模型 API Key、会话记录或资料同步内容。

## 首尔部署准备

目前没有执行服务器或 DNS 部署。`relay.palimpsestai.xyz` 是计划中的域名，尚未验证线上可用性。

1. 将域名 A/AAAA 记录指向 Ubuntu 主机；启动 Caddy 前确认公网解析和连通性。
2. 在 Ubuntu 上使用仓库 `cloud/` 中单进程 Docker Compose。未跟踪的 `.env` 文件设为 `0600`，配置 `CLOUD_DOMAIN=relay.palimpsestai.xyz`、`PUBLIC_BASE=https://relay.palimpsestai.xyz`、随机生成的 `CLOUD_SECRET` 和管理员控制的 `ADMIN_TOKEN`。
3. 保持 `HUB_ENABLED=1`，并将 SQLite 运行时控制设为 `cloud_service=on`、`hub=on`、`sync=off`。`UPSTREAM_KEY` 留空、关闭 `free_allowance`。仅在创建目标账号期间开放注册。
4. 公网只开放 Caddy 所需 TCP 80、443。SSH 限制为运维 IP，不映射 Relay 的 8787 容器端口或管理端口。
5. 检查 `/healthz`，再使用两个测试设备验证邮箱登录、`/v1/devices`、WebSocket 重连、`ping/pong` 和只读 `info`。
6. 持久化 `cloud/data/` 和 Caddy 命名卷。分别备份 SQLite 数据目录及 Compose/`.env`，加密保存密钥备份。升级前在独立主机验证恢复。回滚时恢复上个镜像或源码版本及对应数据库备份。

运行时开关保存在 SQLite，可在现有管理控制台修改。正式公开长期使用前，应先为 Relay 增加 Hub-only 路由白名单。本 PR 记录现有边界，不修改上游 Relay API。

## 使用方法

- Android：从聊天抽屉打开**设备**，填写 Relay 地址，发送并输入邮箱验证码。此操作只连接 Hub，不会添加 Cloud 模型 Provider。
- Windows Vigil：安装更新后的 Brain 依赖，在仓库目录运行 `python brain/kestrel_hub_bridge.py login`。输入相同 Relay 和账号邮箱，完成后重启 Vigil Brain。`status` 查看本机凭证配置；`logout` 会在可连接时吊销凭证并删除本机 DPAPI 文件。
- Android 设备列表会显示同账号设备的在线状态。Reach 的 `info` 返回电脑名称、操作系统和 Vigil 版本。Bridge 只声明 `info`，不会执行 Shell 或其他调用。

没有本机 Relay 凭证时，Bridge 不会联网。它使用现有 Brain 进程生命周期，不修改 Vigil Brain、工具权限、本机 HTTP API 或 Agent 执行协议。
