"""Import-first community API. No model generation or remote URL fetching."""
import base64
import json
import logging
from contextlib import asynccontextmanager
from typing import Literal
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import and_, delete, or_, select, text, update
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session
from .config import Settings, settings
from .db import make_engine, sessions
from .models import Audit, Favorite, Invite, Post, Report, SessionToken, User
from .schemas import Credentials, DeleteAccount, PasswordChange, PostInput, Registration, ReportInput, ReviewInput
from .security import DUMMY_HASH, digest, issue_session, now, password_hasher, rate_limit, uid, verify_password

SCHEMA_VERSION = "0001"
logger = logging.getLogger("wenfou")
bearer = HTTPBearer(auto_error=False)


class BodyLimit:
    def __init__(self, app, max_bytes=524288):
        self.app, self.max_bytes = app, max_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            body.extend(message.get("body", b""))
            if len(body) > self.max_bytes:
                response = JSONResponse({"detail": "请求体超过 512 KiB"}, status_code=413)
                return await response(scope, receive, send)
            if not message.get("more_body", False):
                break
        delivered = False
        async def replay():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()
        await self.app(scope, replay, send)


def user_json(user):
    return {"id": user.id, "username": user.username, "display_name": user.display_name, "role": user.role}


def post_json(post, author, *, private=False, summary=False):
    result = {
        "id": post.id, "author": {"id": author.id, "display_name": author.display_name},
        "question": post.question, "note": post.note, "source_platform": post.source_platform,
        "source_url": post.source_url, "origin": "external_import", "ai_generated": True,
        "source_verified": False, "user_confirmed": True,
        "created_at": post.created_at, "published_at": post.published_at,
    }
    result["answer_preview" if summary else "answer"] = post.answer[:600] if summary else post.answer
    if summary:
        result["answer_length"] = len(post.answer)
    if private:
        result.update(status=post.status, review_reason=post.review_reason)
    return result


def cursor_encode(stamp, ident):
    return base64.urlsafe_b64encode(f"{stamp}:{ident}".encode()).decode().rstrip("=")


def cursor_decode(value):
    try:
        stamp, ident = base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True).decode().split(":")
        if len(ident) != 32 or any(c not in "0123456789abcdef" for c in ident) or not stamp.isdigit():
            raise ValueError()
        return int(stamp), ident
    except (ValueError, UnicodeError):
        raise HTTPException(422, "分页游标无效")


def before(column, ident_column, cursor):
    stamp, ident = cursor_decode(cursor)
    return or_(column < stamp, and_(column == stamp, ident_column < ident))


def create_app(config: Settings | None = None):
    config = config or settings()
    engine = make_engine(config.database_url)
    factory = sessions(engine)

    @asynccontextmanager
    async def lifespan(app):
        with engine.connect() as conn:
            version = conn.execute(text("SELECT version_num FROM alembic_version")).scalar_one()
            if version != SCHEMA_VERSION:
                raise RuntimeError("Schema mismatch: run alembic upgrade head before startup")
        yield
        engine.dispose()

    app = FastAPI(title="问否 API", version="0.4.0", lifespan=lifespan,
                  docs_url="/docs" if config.docs_enabled else None, redoc_url=None,
                  openapi_url="/openapi.json" if config.docs_enabled else None)
    app.state.factory, app.state.engine, app.state.config = factory, engine, config
    app.add_middleware(BodyLimit)

    @app.middleware("http")
    async def headers(request, call_next):
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Request-ID"] = uid()
        return response

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        # Do not echo passwords, invite codes or imported private text in error bodies.
        return JSONResponse({"detail": [{"loc": e["loc"], "msg": e["msg"], "type": e["type"]} for e in exc.errors()]}, status_code=422)

    @app.exception_handler(SQLAlchemyError)
    async def database_error(request, exc):
        logger.error("database operation failed: %s", type(exc).__name__)
        return JSONResponse({"detail": "服务暂时不可用，请稍后重试"}, status_code=503)

    def db_session():
        with factory.begin() as db:
            yield db

    def throttle(request: Request, scope="api", limit=300, seconds=60):
        if config.rate_limit_enabled:
            ip = request.client.host if request.client else "unknown"
            rate_limit(factory, f"{scope}:{ip}", limit, seconds)

    def guard(request: Request):
        throttle(request)

    def current_user(db: Session = Depends(db_session), token: HTTPAuthorizationCredentials | None = Depends(bearer)):
        if token is None or token.scheme.lower() != "bearer" or len(token.credentials) > 128:
            raise HTTPException(401, "请登录", headers={"WWW-Authenticate": "Bearer"})
        session = db.get(SessionToken, digest(token.credentials))
        if session is None or session.expires_at <= now():
            raise HTTPException(401, "登录已失效", headers={"WWW-Authenticate": "Bearer"})
        user = db.scalar(select(User).where(User.id == session.user_id).with_for_update())
        if user is None or not user.active:
            raise HTTPException(401, "账号不可用")
        return user

    def admin(user: User = Depends(current_user)):
        if user.role != "admin":
            raise HTTPException(403, "需要管理员权限")
        return user

    def audit(db, actor, action, target, reason):
        db.add(Audit(id=uid(), actor_id=actor.id, action=action, target_id=target, reason=reason, created_at=now()))

    def public_post(db, post_id, lock=False):
        stmt = select(Post).where(Post.id == post_id, Post.status == "published")
        post = db.scalar(stmt.with_for_update() if lock else stmt)
        if post is None:
            raise HTTPException(404, "帖子不存在或已不可见")
        return post

    @app.get("/health/live")
    def live():
        return {"status": "ok", "release": config.release}

    @app.get("/health/ready")
    def ready(db: Session = Depends(db_session)):
        version = db.execute(text("SELECT version_num FROM alembic_version")).scalar_one()
        if version != SCHEMA_VERSION:
            raise HTTPException(503, "数据库版本不匹配")
        return {"status": "ok", "schema": version, "release": config.release}

    @app.post("/v1/auth/register", status_code=201, dependencies=[Depends(guard)])
    def register(body: Registration, request: Request, db: Session = Depends(db_session)):
        if not config.registration_enabled:
            raise HTTPException(403, "暂未开放注册")
        throttle(request, "register", 5, 3600)
        invitation = db.execute(update(Invite).where(Invite.code_hash == digest(body.invite_code), Invite.remaining > 0,
                                                    Invite.expires_at > now()).values(remaining=Invite.remaining - 1))
        if invitation.rowcount != 1:
            raise HTTPException(403, "邀请码无效或已用完")
        user = User(id=uid(), username=body.username, display_name=body.display_name,
                    password_hash=password_hasher.hash(body.password), role="user", active=True, created_at=now())
        db.add(user)
        try:
            db.flush()
        except IntegrityError:
            raise HTTPException(409, "用户名已被使用")
        return {**issue_session(db, user, config.session_hours), "user": user_json(user)}

    @app.post("/v1/auth/login", dependencies=[Depends(guard)])
    def login(body: Credentials, request: Request, db: Session = Depends(db_session)):
        throttle(request, "login", 20, 300)
        if config.rate_limit_enabled:
            rate_limit(factory, f"login-name:{body.username}", 10, 300)
        user = db.scalar(select(User).where(User.username == body.username).with_for_update())
        valid = verify_password(user.password_hash if user else DUMMY_HASH, body.password)
        if not user or not valid or not user.active:
            raise HTTPException(401, "用户名或密码错误")
        # Bound session growth while allowing two-device use; newest 9 existing sessions survive.
        hashes = list(db.scalars(select(SessionToken.token_hash).where(SessionToken.user_id == user.id)
                                 .order_by(SessionToken.expires_at.desc(), SessionToken.token_hash).offset(9)))
        if hashes:
            db.execute(delete(SessionToken).where(SessionToken.token_hash.in_(hashes)))
        return {**issue_session(db, user, config.session_hours), "user": user_json(user)}

    @app.post("/v1/auth/logout", status_code=204, dependencies=[Depends(guard)])
    def logout(user: User = Depends(current_user), token=Depends(bearer), db: Session = Depends(db_session)):
        db.execute(delete(SessionToken).where(SessionToken.token_hash == digest(token.credentials)))

    @app.get("/v1/me", dependencies=[Depends(guard)])
    def me(user: User = Depends(current_user)):
        return user_json(user)

    @app.post("/v1/me/password", status_code=204, dependencies=[Depends(guard)])
    def password(body: PasswordChange, user: User = Depends(current_user), db: Session = Depends(db_session)):
        if not verify_password(user.password_hash, body.current_password):
            raise HTTPException(401, "原密码错误")
        user.password_hash = password_hasher.hash(body.new_password)
        db.execute(delete(SessionToken).where(SessionToken.user_id == user.id))
        audit(db, user, "change_password", user.id, "用户修改密码，所有会话失效")

    @app.delete("/v1/me", status_code=204, dependencies=[Depends(guard)])
    def delete_account(body: DeleteAccount, user: User = Depends(current_user), db: Session = Depends(db_session)):
        if user.role == "admin":
            raise HTTPException(409, "管理员账号需先交接管理权限")
        if not verify_password(user.password_hash, body.password):
            raise HTTPException(401, "密码错误")
        db.execute(delete(SessionToken).where(SessionToken.user_id == user.id))
        db.execute(delete(Favorite).where(Favorite.user_id == user.id))
        db.execute(update(Post).where(Post.author_id == user.id).values(status="withdrawn", question="[已删除]", answer="[已删除]", note="", source_url="", review_reason=""))
        db.execute(update(Report).where(Report.user_id == user.id).values(reason="[举报者已注销]"))
        user.active = False
        user.username = "deleted_" + user.id
        user.display_name = "已注销用户"
        user.password_hash = "!disabled"
        audit(db, user, "delete_account", user.id, "账号注销与公开内容清除；备份按保留周期过期")

    @app.post("/v1/posts", status_code=202, dependencies=[Depends(guard)])
    def submit(body: PostInput, request: Request,
               request_key: str = Header(alias="Idempotency-Key", min_length=16, max_length=80, pattern=r"^[A-Za-z0-9_-]+$"),
               user: User = Depends(current_user), db: Session = Depends(db_session)):
        payload_hash = digest(json.dumps(body.model_dump(), sort_keys=True, ensure_ascii=False))
        previous = db.scalar(select(Post).where(Post.author_id == user.id, Post.request_key == request_key))
        if previous:
            if previous.payload_hash != payload_hash:
                raise HTTPException(409, "幂等键已用于不同内容")
            return {"id": previous.id, "status": previous.status}
        throttle(request, "publish", 10, 60)
        post = Post(id=uid(), author_id=user.id, question=body.question, answer=body.answer, note=body.note,
                    source_platform=body.source_platform, source_url=body.source_url, status="pending", created_at=now(),
                    request_key=request_key, payload_hash=payload_hash)
        db.add(post)
        db.flush()
        return {"id": post.id, "status": "pending"}

    @app.get("/v1/feed", dependencies=[Depends(guard)])
    def feed(limit: int = Query(20, ge=1, le=50), cursor: str | None = Query(None, max_length=96), db: Session = Depends(db_session)):
        stmt = select(Post, User).join(User, Post.author_id == User.id).where(Post.status == "published")
        if cursor:
            stmt = stmt.where(before(Post.published_at, Post.id, cursor))
        rows = db.execute(stmt.order_by(Post.published_at.desc(), Post.id.desc()).limit(limit + 1)).all()
        page = rows[:limit]
        return {"items": [post_json(p, u, summary=True) for p, u in page],
                "next_cursor": cursor_encode(page[-1][0].published_at, page[-1][0].id) if len(rows) > limit else None}

    @app.get("/v1/posts/{post_id}", dependencies=[Depends(guard)])
    def detail(post_id: str, db: Session = Depends(db_session)):
        post = public_post(db, post_id)
        return post_json(post, db.get(User, post.author_id))

    @app.get("/v1/me/posts", dependencies=[Depends(guard)])
    def mine(limit: int = Query(20, ge=1, le=50), cursor: str | None = Query(None, max_length=96),
             user: User = Depends(current_user), db: Session = Depends(db_session)):
        stmt = select(Post).where(Post.author_id == user.id)
        if cursor:
            stmt = stmt.where(before(Post.created_at, Post.id, cursor))
        rows = list(db.scalars(stmt.order_by(Post.created_at.desc(), Post.id.desc()).limit(limit + 1)))
        page = rows[:limit]
        return {"items": [post_json(p, user, private=True, summary=True) for p in page],
                "next_cursor": cursor_encode(page[-1].created_at, page[-1].id) if len(rows) > limit else None}

    @app.get("/v1/me/posts/{post_id}", dependencies=[Depends(guard)])
    def own_detail(post_id: str, user: User = Depends(current_user), db: Session = Depends(db_session)):
        post = db.scalar(select(Post).where(Post.id == post_id, Post.author_id == user.id))
        if post is None:
            raise HTTPException(404, "帖子不存在")
        return post_json(post, user, private=True)

    @app.delete("/v1/posts/{post_id}", status_code=204, dependencies=[Depends(guard)])
    def withdraw(post_id: str, user: User = Depends(current_user), db: Session = Depends(db_session)):
        post = db.scalar(select(Post).where(Post.id == post_id, Post.author_id == user.id).with_for_update())
        if post is None:
            raise HTTPException(404, "帖子不存在")
        post.status = "withdrawn"
        audit(db, user, "withdraw", post.id, "作者撤回")

    @app.put("/v1/posts/{post_id}/favorite", status_code=204, dependencies=[Depends(guard)])
    def favorite(post_id: str, user: User = Depends(current_user), db: Session = Depends(db_session)):
        public_post(db, post_id, lock=True)
        if db.get(Favorite, (user.id, post_id)) is None:
            db.add(Favorite(user_id=user.id, post_id=post_id, created_at=now()))

    @app.delete("/v1/posts/{post_id}/favorite", status_code=204, dependencies=[Depends(guard)])
    def unfavorite(post_id: str, user: User = Depends(current_user), db: Session = Depends(db_session)):
        db.execute(delete(Favorite).where(Favorite.user_id == user.id, Favorite.post_id == post_id))

    @app.get("/v1/me/favorites", dependencies=[Depends(guard)])
    def favorites(limit: int = Query(20, ge=1, le=50), cursor: str | None = Query(None, max_length=96),
                  user: User = Depends(current_user), db: Session = Depends(db_session)):
        stmt = select(Post, User, Favorite.created_at).join(Favorite, Favorite.post_id == Post.id).join(User, User.id == Post.author_id).where(Favorite.user_id == user.id, Post.status == "published")
        if cursor:
            stmt = stmt.where(before(Favorite.created_at, Post.id, cursor))
        rows = db.execute(stmt.order_by(Favorite.created_at.desc(), Post.id.desc()).limit(limit + 1)).all()
        page = rows[:limit]
        return {"items": [post_json(p, a, summary=True) for p, a, _ in page],
                "next_cursor": cursor_encode(page[-1][2], page[-1][0].id) if len(rows) > limit else None}

    @app.post("/v1/posts/{post_id}/reports", status_code=201, dependencies=[Depends(guard)])
    def report(post_id: str, body: ReportInput, user: User = Depends(current_user), db: Session = Depends(db_session)):
        public_post(db, post_id, lock=True)
        previous = db.scalar(select(Report).where(Report.user_id == user.id, Report.post_id == post_id))
        if previous:
            return {"id": previous.id, "status": previous.status}
        report = Report(id=uid(), user_id=user.id, post_id=post_id, reason=body.reason, status="open", created_at=now())
        db.add(report)
        return {"id": report.id, "status": "open"}

    @app.get("/v1/admin/posts", dependencies=[Depends(guard)])
    def queue(status: Literal["pending", "published", "rejected", "removed", "withdrawn"] = "pending",
              limit: int = Query(20, ge=1, le=50), offset: int = Query(0, ge=0, le=10000),
              actor: User = Depends(admin), db: Session = Depends(db_session)):
        rows = db.execute(select(Post, User).join(User, Post.author_id == User.id).where(Post.status == status)
                          .order_by(Post.created_at, Post.id).offset(offset).limit(limit)).all()
        return {"items": [post_json(p, u, private=True) for p, u in rows]}

    @app.post("/v1/admin/posts/{post_id}/review", dependencies=[Depends(guard)])
    def review(post_id: str, body: ReviewInput, actor: User = Depends(admin), db: Session = Depends(db_session)):
        post = db.scalar(select(Post).where(Post.id == post_id).with_for_update())
        if post is None:
            raise HTTPException(404, "帖子不存在")
        expected = "published" if body.decision == "remove" else "pending"
        if post.status != expected:
            raise HTTPException(409, "帖子状态已变化，请刷新后处理")
        post.status = {"approve": "published", "reject": "rejected", "remove": "removed"}[body.decision]
        post.review_reason = body.reason
        if body.decision == "approve":
            post.published_at = now()
        audit(db, actor, body.decision, post.id, body.reason)
        return {"id": post.id, "status": post.status}

    @app.get("/v1/admin/reports", dependencies=[Depends(guard)])
    def reports(limit: int = Query(20, ge=1, le=50), offset: int = Query(0, ge=0, le=10000),
                actor: User = Depends(admin), db: Session = Depends(db_session)):
        rows = db.scalars(select(Report).where(Report.status == "open").order_by(Report.created_at, Report.id).offset(offset).limit(limit))
        return {"items": [{"id": r.id, "post_id": r.post_id, "reason": r.reason, "created_at": r.created_at} for r in rows]}

    @app.post("/v1/admin/reports/{report_id}/resolve", dependencies=[Depends(guard)])
    def resolve(report_id: str, body: ReportInput, actor: User = Depends(admin), db: Session = Depends(db_session)):
        report = db.scalar(select(Report).where(Report.id == report_id).with_for_update())
        if report is None:
            raise HTTPException(404, "举报不存在")
        if report.status != "open":
            raise HTTPException(409, "举报已处理")
        report.status, report.resolution = "resolved", body.reason
        audit(db, actor, "resolve_report", report.id, body.reason)
        return {"id": report.id, "status": report.status}

    return app
