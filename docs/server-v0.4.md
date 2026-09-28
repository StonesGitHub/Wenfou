# 问否 v0.4 服务端与客户端接入契约

部署 agent 从 [deploy/HANDOFF.md](../deploy/HANDOFF.md) 开始。完整 OpenAPI 为 [contracts/openapi-v0.4.json](../contracts/openapi-v0.4.json)。旧 api-draft.yaml 仅是历史规划，不能按它调用本版。

## 运行结构

Caddy（仅 production，80/443）→ API（2 个 Uvicorn worker，宿主机仅 127.0.0.1:8000）→ PostgreSQL 16（无宿主机端口）。同机单节点，无高可用；实例损坏时依赖异机备份恢复。

环境生成器只使用 Python 标准库。`deploy/.env` 权限 600，不 shell source，不提交 Git。配置生成器拒绝覆盖，避免已有数据库与新密码不一致。

| 变量 | 取值 / 用途 |
|---|---|
| WENFOU_MODE | local 或 production，选择 Compose 文件 |
| WENFOU_ENVIRONMENT | development 或 production，生产要求 PostgreSQL、HTTPS、限流 |
| WENFOU_PUBLIC_URL | local 为 http://localhost:8000；production 为 https://实际域名 |
| WENFOU_LOCAL_PORT | 宿主机环回端口，默认 8000，1024..65535 |
| WENFOU_DOMAIN | 生产 API 域名，无 scheme/路径 |
| ACME_EMAIL | 生产证书邮箱 |
| POSTGRES_DB | 初始 wenfou；恢复目标命名 wenfou_restore_YYYYMMDD_HHMMSS |
| POSTGRES_PASSWORD | 集群 owner 随机密码，仅 DB 与迁移容器使用 |
| APP_DB_PASSWORD | 应用角色随机密码，仅具表 CRUD 权限 |
| WENFOU_REGISTRATION_ENABLED | true/false；开启也始终要求邀请码 |

脚本环境可覆盖 `WENFOU_ENV_FILE`（绝对路径）、`WENFOU_BACKUP_DIR`（备份路径）、`WENFOU_IMAGE_TAG`（明确版本标签）。脚本不打印秘密；不要执行未脱敏 `docker compose config` 并分享其输出，校验用 `config --quiet`。
API 内部配置还包括 SESSION_HOURS（默认 168）、DOCS_ENABLED（默认 false）、RATE_LIMIT_ENABLED（生产不允许 false）、RELEASE。Docker 部署固定这些默认值；本机开发可使用 WENFOU_ 前缀覆盖。

## 账号与安全边界

注册要求邀请码+用户名+昵称+密码。邀请码有剩余次数和过期时间；消费与创建账号同一事务，失败回滚。用户名 ASCII 字母/数字/下划线，3–32 字符，忽略大小写；密码 12–128 字符，Argon2id 哈希（19 MiB/2 次/1 并行）。没有验证码短信、邮箱验证、找回邮件或第三方 OAuth；这是邀请制验证账号方案。

登录返回不透明 Bearer token，服务端只保存 SHA-256。7 天过期，最多 10 个会话；改密码撤销全部会话。管理员通过服务器 CLI 创建，无默认凭据，公开注册无法提升权限。注销用户会撤销会话、清除其帖子正文并撤下、清除本人收藏、匿名化账号；匿名审计记录保留，旧备份按保留周期到期。

API 对所有业务路由按 IP 300 次/分钟限流；登录 20 次/5 分钟/IP 与 10 次/5 分钟/用户名；注册 5 次/小时/IP；新提交 10 次/分钟/IP。固定窗口计数存数据库并单独提交，跨 worker 和 401 重试生效，429 带 Retry-After。共享 NAT 下额度也共享，必要时按真实测试流量调整代码后重新验证。

客户端不提交 owner_id、role 或发布 status；所有权从登录会话获得。普通用户不能审核。只有已审核快照公开；撤回/下架立即从详情、信息流和收藏中隐藏。来源 URL 仅验证公开地址格式与平台域名，不发网络请求，不能用导入接口抓内网或登录页面。无 URL 的文本可选其他来源；`source_verified=false` 始终明确返回。

请求体上限 512 KiB；问题 10000 字符、答案 100000 字符、分享理由 2000 字符。服务端按普通文本保存，后续 Web/小程序客户端必须转义展示，不能直接当 HTML 执行。私密原始整段聊天无需上传，只上传用户确认的一轮。API 日志不记录请求正文、令牌或密码；校验错误也不回显 input。

## 路由

时间戳均为 UTC Unix 毫秒。公共接口可不登录，其他路由 `Authorization: Bearer <token>`。分页 `limit=1..50`，公开流/本人帖子/收藏使用 `next_cursor`；管理列表按 offset 分页。Feed 仅返回 `answer_preview`（前 600 字符）与 answer_length，详情返回完整 answer。

| 操作 | 方法与路径 | 结果 |
|---|---|---|
| 注册 | POST /v1/auth/register | 201 token + user |
| 登录 | POST /v1/auth/login | 200 token + user |
| 退出 | POST /v1/auth/logout | 204，当前会话失效 |
| 个人信息 | GET /v1/me | 用户 id、username、display_name、role |
| 修改密码 | POST /v1/me/password | current_password/new_password；204，重新登录 |
| 注销 | DELETE /v1/me | password/confirm:true；204，管理员不可自助注销 |
| 提交问答 | POST /v1/posts | 202 {id,status:pending} |
| 我的提交 | GET /v1/me/posts | 自己所有状态，含 review_reason |
| 我的提交详情 | GET /v1/me/posts/{id} | 含未公开全文，仅本人 |
| 公开流 | GET /v1/feed | {items,next_cursor} |
| 公开详情 | GET /v1/posts/{id} | 仅 published，否则 404 |
| 撤回 | DELETE /v1/posts/{id} | 204，仅作者，重复调用幂等 |
| 收藏/取消收藏 | PUT/DELETE /v1/posts/{id}/favorite | 204，幂等 |
| 收藏列表 | GET /v1/me/favorites | 仅仍然 published 的帖子 |
| 举报 | POST /v1/posts/{id}/reports | reason；201，同人同帖返回已有举报 |
| 审核列表 | GET /v1/admin/posts?status=pending | 管理员，含完整待审核内容 |
| 审核/下架 | POST /v1/admin/posts/{id}/review | decision:approve/reject/remove，reason 必填 |
| 举报列表 | GET /v1/admin/reports | 未处理举报 |
| 处理举报 | POST /v1/admin/reports/{id}/resolve | reason，记录处理但不自动下架 |
| 存活/就绪 | GET /health/live、/health/ready | ready 检查真实 DB schema |

发布请求示例（来源链接可为空）：

```json
{
  "question": "怎样开始一个新习惯？",
  "answer": "把目标缩小到每天两分钟。",
  "note": "准备试一周",
  "source_platform": "豆包",
  "source_url": "",
  "confirm_public": true
}
```

必须带 `Idempotency-Key`，16–80 位字母数字/下划线/短横线，推荐每个新提交 UUID（重试沿用原键）。同用户同键同内容返回原帖子 id/当前状态；改内容复用键返回 409。重试撤回后的提交不会重新发布。帖子内容发布后不可原地修改，需新建提交重新审核。

常见错误：401 重新登录；403 权限/邀请码/关闭注册；404 不存在或不可见；409 状态/用户名/幂等冲突；413 体积超限；422 字段或游标无效；429 按 Retry-After 重试；503 服务/数据库暂不可用。所有响应 no-store。

## 本地测试

```bash
python3 -m venv .venv
.venv/bin/pip install --require-hashes -r server/requirements-dev.txt
PYTHONPATH=server .venv/bin/pytest server/tests scripts/test_configure.py -q
```

SQLite 用于快速测试，部署只用 PostgreSQL。PostgreSQL 测试必须指向**专用可销毁、名称以 _test 结尾**的数据库，因为每项测试会重建 public schema：设置 TEST_DATABASE_URL 后运行同一测试命令。CI 覆盖真实 PG 并发抢最后一个邀请码、同幂等键并发提交。

本机无 Docker 时不能声称已验证容器；`Server` CI 的 deployment-test 会真实构建镜像、启动 Compose、做两账号 API smoke、重启持久化、备份恢复至新库、再次部署，并校验生产 Caddy 配置。它不验证用户 ECS 的安全组、DNS、备案或真实证书签发。

## 暂未实现

Android 联网、微信小程序、真实模型生成、私聊 HTTP、图片/附件、评论/点赞/关注、全文搜索、Web 审核后台、自动内容审核、短信/微信登录、支付、多实例高可用。不要为这些尚未接入的能力购买额外云资源。
