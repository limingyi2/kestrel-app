# Kestrel 初始化

本 Fork 将 Android 应用初始化为 Kestrel，不发布 APK，也不部署 Relay。[Kestrel 架构](kestrel-architecture.md) 说明已有实现、协议提案和待完成的集成工作。

| 品牌层 | 本轮决定 |
|---|---|
| Android 启动器、本地化应用文案、默认 Agent 名、前台通知 | Kestrel，覆盖现有 18 个资源语言目录 |
| Application ID 与快捷方式目标 | `io.github.limingyi2.kestrel` |
| 外部 deep link | `kestrel://`，归一化为现有内部 `minis://` |
| 更新 | 两条 Android 更新路径使用 `limingyi2/kestrel-app`，不再查询上游下载索引 |
| 源码、Issue、分享及许可证链接 | Kestrel 仓库，上游归属链接仍指向 nanoMuse |
| 内部包名、Skills、MCP、沙箱路径、socket 传输 | 保留，socket 自动跟随 application ID |
| 图标、头像、配色 | 暂用现有上游资产，可替换，不是正式 Kestrel Logo |
| 正式 Logo | 等待确定的金色圆环、透明背景资产，本轮不设计最终 Logo |
| nanoMuse Cloud、Desktop 引用 | 实际上游外部产品保留原名，不代表已部署 Kestrel 服务 |
| iOS、Python、Web、上游桌面端 | 保留继承源码，不在本轮 Android 初始化范围 |

幂等入口是 `scripts/rebrand.py`，`--check` 只读。脚本保留原 nanoMuse 实现供合并审查，实际调用 `scripts/kestrel_brand.py` 中的 Android 白名单。不做全仓库字符串替换。资源 key、法律注释、外部产品地址及既有存储路径不是应用显示名。

LICENSE、NOTICE、THIRD_PARTY_NOTICES.md 和子模块引用保留上游内容。Kestrel 修改通过本 Fork 的 Git 历史提供，遵循相同 GPL-3.0-or-later 条款。上游文件沿用 `// nanoMuse:` 标记以便合并审查。各 README 的 Fork 提示下保留原 nanoMuse 文档，其中下载链接属于上游，不是 Kestrel 发布。

正式发布前需替换为获批图标，真机检查 Kestrel 界面，验收 Linux、Skills、MCP，部署并配置首尔 Relay，实现并审查 Vigil Bridge 与协议适配层，确定统一版本及签名策略。当前没有 Kestrel Release，首次批准发布前，GitHub 更新检查可能显示无版本或最新版本不可用。
