import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import func, select, text
from wenfou.api import create_app
from wenfou.config import Settings
from wenfou.db import make_engine, sessions
from wenfou.models import Audit, Invite, Post, SessionToken, User
from wenfou.security import digest, now, password_hasher, uid

PASSWORD = "correct-horse-battery-123"


@pytest.fixture
def setup(tmp_path, monkeypatch):
    url = os.environ.get("TEST_DATABASE_URL", f"sqlite:///{tmp_path}/test.db")
    if url.startswith("postgresql"):
        from sqlalchemy.engine import make_url
        assert make_url(url).database.endswith("_test"), "Integration tests require a disposable *_test database"
        engine = make_engine(url)
        with engine.begin() as conn:
            conn.execute(text("DROP SCHEMA public CASCADE"))
            conn.execute(text("CREATE SCHEMA public"))
        engine.dispose()
    monkeypatch.setenv("WENFOU_DATABASE_URL", url)
    from wenfou.config import settings
    settings.cache_clear()
    cfg = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    command.upgrade(cfg, "head")
    command.upgrade(cfg, "head")  # Migrations are repeatable, not application-start side effects.
    config = Settings(database_url=url, environment="test", rate_limit_enabled=False)
    app = create_app(config)
    factory = app.state.factory
    with factory.begin() as db:
        db.add(User(id="a" * 32, username="administrator", display_name="审核员", role="admin", active=True,
                    password_hash=password_hasher.hash(PASSWORD), created_at=now()))
    with TestClient(app) as client:
        yield client, factory, config
    settings.cache_clear()


def invite(factory, uses=10):
    code = uid()
    with factory.begin() as db:
        db.add(Invite(code_hash=digest(code), remaining=uses, expires_at=now() + 600000))
    return code


def register(client, factory, username="alice", code=None):
    response = client.post("/v1/auth/register", json={"username": username, "display_name": username,
                            "password": PASSWORD, "invite_code": code or invite(factory)})
    assert response.status_code == 201, response.text
    return {"Authorization": "Bearer " + response.json()["access_token"]}


def admin(client):
    r = client.post("/v1/auth/login", json={"username": "administrator", "password": PASSWORD})
    assert r.status_code == 200, r.text
    return {"Authorization": "Bearer " + r.json()["access_token"]}


def payload(**changes):
    return {"question": "如何开始一个新习惯？", "answer": "每天先花两分钟。", "note": "想试试",
            "source_platform": "DeepSeek", "source_url": "https://chat.deepseek.com/share/example123?tracking=1",
            "confirm_public": True, **changes}


def submit(client, headers, key=None, **changes):
    return client.post("/v1/posts", headers={**headers, "Idempotency-Key": key or uid()}, json=payload(**changes))


def published(client, factory):
    owner = register(client, factory)
    pid = submit(client, owner).json()["id"]
    reviewer = admin(client)
    r = client.post(f"/v1/admin/posts/{pid}/review", headers=reviewer, json={"decision": "approve", "reason": "审核通过"})
    assert r.status_code == 200, r.text
    return pid, owner, reviewer


def test_auth_invitation_password_logout(setup):
    client, factory, _ = setup
    assert client.get("/v1/me").status_code == 401
    code = invite(factory, 1)
    owner = register(client, factory, code=code)
    r = client.post("/v1/auth/register", json={"username": "bob", "display_name": "Bob", "password": PASSWORD, "invite_code": code})
    assert r.status_code == 403
    with factory() as db:
        stored = db.scalar(select(User).where(User.username == "alice"))
        assert stored.password_hash.startswith("$argon2id$") and PASSWORD not in stored.password_hash
        assert db.scalar(select(SessionToken)).token_hash != owner["Authorization"].split()[1]
    r = client.post("/v1/me/password", headers=owner, json={"current_password": PASSWORD, "new_password": "a-new-password-12345"})
    assert r.status_code == 204
    assert client.get("/v1/me", headers=owner).status_code == 401
    assert client.post("/v1/auth/login", json={"username": "alice", "password": PASSWORD}).status_code == 401
    token = client.post("/v1/auth/login", json={"username": "alice", "password": "a-new-password-12345"}).json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}
    assert client.post("/v1/auth/logout", headers=headers).status_code == 204
    assert client.get("/v1/me", headers=headers).status_code == 401


def test_username_conflict_does_not_consume_invite(setup):
    c, f, _ = setup
    register(c, f)
    code = invite(f, 1)
    r = c.post("/v1/auth/register", json={"username": "Alice", "display_name": "A", "password": PASSWORD, "invite_code": code})
    assert r.status_code == 409
    register(c, f, "bob", code)


def test_pending_private_review_and_provenance(setup):
    c, f, _ = setup
    owner, other = register(c, f), register(c, f, "bob")
    pid = submit(c, owner).json()["id"]
    assert c.get("/v1/feed").json()["items"] == []
    assert c.get(f"/v1/posts/{pid}").status_code == 404
    assert c.get(f"/v1/me/posts/{pid}", headers=other).status_code == 404
    assert c.delete(f"/v1/posts/{pid}", headers=other).status_code == 404
    assert c.get("/v1/admin/posts", headers=other).status_code == 403
    assert c.post(f"/v1/admin/posts/{pid}/review", headers=other, json={"decision": "approve", "reason": "oops"}).status_code == 403
    reviewer = admin(c)
    assert len(c.get("/v1/admin/posts", headers=reviewer).json()["items"]) == 1
    assert c.post(f"/v1/admin/posts/{pid}/review", headers=reviewer, json={"decision": "approve", "reason": "核对完成"}).status_code == 200
    data = c.get(f"/v1/posts/{pid}").json()
    assert data["source_url"] == "https://chat.deepseek.com/share/example123"
    assert data["origin"] == "external_import" and data["source_verified"] is False
    assert not {"password_hash", "username", "request_key", "review_reason", "payload_hash"} & data.keys()
    assert "username" not in data["author"]
    assert c.post(f"/v1/admin/posts/{pid}/review", headers=reviewer, json={"decision": "approve", "reason": "重试"}).status_code == 409
    with f() as db:
        assert db.scalar(select(func.count()).select_from(Audit)) == 1


def test_idempotency_and_payload_validation(setup):
    c, f, _ = setup
    owner = register(c, f)
    key = uid()
    first = submit(c, owner, key)
    assert submit(c, owner, key).json() == first.json()
    assert submit(c, owner, key, answer="不同回答").status_code == 409
    assert submit(c, owner, status="published").status_code == 422
    assert submit(c, owner, confirm_public=False).status_code == 422
    assert submit(c, owner, answer=" " * 10).status_code == 422
    assert submit(c, owner, source_url="https://chat.deepseek.com/c/private").status_code == 422
    assert submit(c, owner, source_url="https://chat.deepseek.com.evil.example/share/x").status_code == 422
    assert submit(c, owner, source_url="http://127.0.0.1/share/x").status_code == 422
    assert submit(c, owner, answer="x" * 100001).status_code == 422
    r = c.post("/v1/auth/login", json={"username": "bad", "password": "secret"})
    assert r.status_code == 422 and "secret" not in r.text
    assert c.post("/v1/posts", headers=owner, content=b"x" * 524289).status_code == 413
    assert c.get("/v1/feed?cursor=invalid").status_code == 422
    assert c.get("/v1/feed?limit=10000").status_code == 422


def test_favorites_reports_removal_and_withdrawal(setup):
    c, f, _ = setup
    pid, owner, reviewer = published(c, f)
    other = register(c, f, "bob")
    for _ in range(2):
        assert c.put(f"/v1/posts/{pid}/favorite", headers=other).status_code == 204
    assert len(c.get("/v1/me/favorites", headers=other).json()["items"]) == 1
    report = c.post(f"/v1/posts/{pid}/reports", headers=other, json={"reason": "需核实"}).json()
    assert c.post(f"/v1/posts/{pid}/reports", headers=other, json={"reason": "重复"}).json() == report
    assert c.post(f"/v1/admin/reports/{report['id']}/resolve", headers=other, json={"reason": "跳过"}).status_code == 403
    assert c.post(f"/v1/admin/posts/{pid}/review", headers=reviewer, json={"decision": "remove", "reason": "举报属实"}).status_code == 200
    assert c.get(f"/v1/posts/{pid}").status_code == 404
    assert c.get("/v1/me/favorites", headers=other).json()["items"] == []
    assert c.get("/v1/feed").json()["items"] == []
    assert c.post(f"/v1/admin/reports/{report['id']}/resolve", headers=reviewer, json={"reason": "已下架"}).status_code == 200
    assert c.get("/v1/admin/reports", headers=reviewer).json()["items"] == []
    pending = submit(c, owner).json()["id"]
    assert c.delete(f"/v1/posts/{pending}", headers=owner).status_code == 204
    assert c.post(f"/v1/admin/posts/{pending}/review", headers=reviewer, json={"decision": "approve", "reason": "已撤回"}).status_code == 409


def test_expiry_and_account_deletion(setup):
    c, f, _ = setup
    pid, owner, _ = published(c, f)
    r = c.request("DELETE", "/v1/me", headers=owner, json={"password": PASSWORD, "confirm": True})
    assert r.status_code == 204
    assert c.get(f"/v1/posts/{pid}").status_code == 404
    assert c.get("/v1/me", headers=owner).status_code == 401
    with f() as db:
        post = db.get(Post, pid)
        assert post.answer == "[已删除]" and post.source_url == ""
    other = register(c, f, "bob")
    with f.begin() as db:
        db.get(SessionToken, digest(other["Authorization"].split()[1])).expires_at = now() - 1
    assert c.get("/v1/me", headers=other).status_code == 401


def test_keyset_pagination_and_restart_persistence(setup):
    c, f, config = setup
    owner, reviewer = register(c, f), admin(c)
    ids = []
    for _ in range(3):
        pid = submit(c, owner, answer="x" * 700).json()["id"]
        c.post(f"/v1/admin/posts/{pid}/review", headers=reviewer, json={"decision": "approve", "reason": "通过"})
        ids.append(pid)
    first = c.get("/v1/feed?limit=2").json()
    assert len(first["items"]) == 2 and len(first["items"][0]["answer_preview"]) == 600
    second = c.get("/v1/feed", params={"limit": 2, "cursor": first["next_cursor"]}).json()
    assert {x["id"] for x in first["items"] + second["items"]} == set(ids)
    assert second["next_cursor"] is None
    with TestClient(create_app(config)) as restarted:
        assert restarted.get("/health/ready").status_code == 200
        assert len(restarted.get("/v1/feed").json()["items"]) == 3
        assert restarted.get("/v1/me", headers=owner).status_code == 200


def test_rate_limit_persists_across_failed_logins_and_workers(setup):
    _, _, config = setup
    limited = config.model_copy(update={"rate_limit_enabled": True})
    with TestClient(create_app(limited)) as c:
        for _ in range(10):
            assert c.post("/v1/auth/login", json={"username": "nobody", "password": PASSWORD}).status_code == 401
    with TestClient(create_app(limited)) as c:
        r = c.post("/v1/auth/login", json={"username": "nobody", "password": PASSWORD})
        assert r.status_code == 429 and int(r.headers["Retry-After"]) > 0


def test_concurrent_last_invite_and_idempotency_postgres(setup):
    c, f, config = setup
    if not config.database_url.startswith("postgresql"):
        pytest.skip("Row-lock concurrency verified against PostgreSQL in CI")
    code = invite(f, 1)
    def signup(name):
        return c.post("/v1/auth/register", json={"username": name, "display_name": name, "password": PASSWORD, "invite_code": code})
    with ThreadPoolExecutor(2) as pool:
        results = list(pool.map(signup, ["alice", "bob"]))
    assert sorted(r.status_code for r in results) == [201, 403]
    token = next(r.json()["access_token"] for r in results if r.status_code == 201)
    headers, key = {"Authorization": "Bearer " + token}, uid()
    with ThreadPoolExecutor(4) as pool:
        results = list(pool.map(lambda _: submit(c, headers, key), range(4)))
    assert all(r.status_code == 202 for r in results), [r.text for r in results]
    assert len({r.json()["id"] for r in results}) == 1


def test_production_configuration_fails_closed():
    with pytest.raises(ValueError):
        Settings(environment="production", database_url="sqlite:///x", public_url="https://api.example.com")
    with pytest.raises(ValueError):
        Settings(environment="production", database_url="postgresql+psycopg://x", public_url="http://example.com")
