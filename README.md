# 问否（Wenfou）

面向中国市场的 AI 问答分享应用，安卓优先，后续接微信小程序。

- **Android v0.3**：Compose 界面、品牌字标、外部 AI 分享链接/文字导入、前台剪贴板提示、编辑核对、本地分享预览。当前尚未连接服务端。
- **Server v0.4**：可部署的邀请制问答社区 API，PostgreSQL 持久化、登录会话、人工审核、公开流、收藏、举报、撤回与账号注销。无真实模型调用。
- **部署目标**：Ubuntu 24.04 x86_64，2 核 2 GB；Docker Compose，同机 API + PostgreSQL，生产加 Caddy HTTPS。

## 部署交接

**执行 agent 请先完整阅读 [deploy/HANDOFF.md](deploy/HANDOFF.md)**，按交付 commit 部署。文档包含必要输入、环境生成、数据库迁移、管理员初始化、两账号验收、备份恢复与更新回滚；不需要把密码或密钥发送给开发 agent。

- [API 与环境配置](docs/server-v0.4.md)
- [OpenAPI v0.4](contracts/openapi-v0.4.json)
- [外部导入范围与验证](docs/import-v0.3.md)
- [品牌/UI 设计](docs/design-v2.md)

## 开发验证

```bash
python3 -m venv .venv
.venv/bin/pip install --require-hashes -r server/requirements-dev.txt
PYTHONPATH=server .venv/bin/pytest server/tests scripts/test_configure.py -q
```

`Server` CI 另使用真实 PostgreSQL 验证迁移/并发，并通过 Docker Compose 完成部署、API smoke、重启、备份恢复和重复部署。原 SQLite 隐私领域原型及其测试保留，未直接暴露成 HTTP。

Android 需要 JDK 17 与 Android SDK 36：

```bash
cd clients/android
bash gradlew :app:assembleDebug :app:testDebugUnitTest
```

开发分支为 `codex/server-foundation`（从 `codex/android-foundation` 继续），不可假设 main 已包含这些功能。部署前查阅当前版本的 CI 结果；完成服务器配置并不代表备案、真实 Android 联网或公网运营验收已完成。
