#!/usr/bin/env python3
"""Two-account API acceptance; passwords and tokens remain in memory, not argv/logs."""
import argparse
import getpass
import json
import os
import secrets
import sys
from urllib import request, error
from urllib.parse import urlsplit


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", required=True)
    p.add_argument("--admin", default="administrator")
    args = p.parse_args()
    base = args.base_url.rstrip("/")
    parsed = urlsplit(base)
    if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost"}):
        p.error("Use HTTPS, or HTTP only over a local connection / SSH tunnel")
    admin_password = os.environ.get("WENFOU_SMOKE_ADMIN_PASSWORD") or getpass.getpass("Admin password: ")
    invite_code = os.environ.get("WENFOU_SMOKE_INVITE") or getpass.getpass("Invite code (at least two uses): ")

    def call(method, path, data=None, token=None, status=200, key=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = key
        req = request.Request(base + path, data=json.dumps(data).encode() if data is not None else None, headers=headers, method=method)
        try:
            res = request.urlopen(req, timeout=20)
        except error.HTTPError as exc:
            res = exc
        with res:
            if res.status != status:
                raise RuntimeError(f"{method} {path}: expected {status}, got {res.status}")
            raw = res.read()
            return json.loads(raw) if raw else None

    call("GET", "/health/ready")
    admin_token = call("POST", "/v1/auth/login", {"username": args.admin, "password": admin_password})["access_token"]
    people = []
    post_id = None
    try:
        for _ in range(2):
            name = "smoke_" + secrets.token_hex(6)
            password = secrets.token_urlsafe(24)
            result = call("POST", "/v1/auth/register", {"username": name, "display_name": "部署验收账号", "password": password, "invite_code": invite_code}, status=201)
            people.append((result["access_token"], password))
        alice, bob = people[0][0], people[1][0]
        data = {"question": "[部署验收] 问否能否跨账号分享？", "answer": "这是一条部署验收测试内容，将在测试结束后撤回。", "note": "自动验收样例，非模型回答", "source_platform": "其他", "source_url": "", "confirm_public": True}
        key = secrets.token_hex(16)
        post_id = call("POST", "/v1/posts", data, alice, 202, key)["id"]
        assert call("POST", "/v1/posts", data, alice, 202, key)["id"] == post_id
        call("GET", f"/v1/posts/{post_id}", status=404)
        call("POST", f"/v1/admin/posts/{post_id}/review", {"decision": "approve", "reason": "部署验收内容"}, admin_token)
        assert call("GET", f"/v1/posts/{post_id}")["answer"] == data["answer"]
        call("PUT", f"/v1/posts/{post_id}/favorite", token=bob, status=204)
        assert any(item["id"] == post_id for item in call("GET", "/v1/me/favorites", token=bob)["items"])
        call("DELETE", f"/v1/posts/{post_id}", token=alice, status=204)
        call("GET", f"/v1/posts/{post_id}", status=404)
        assert call("GET", "/v1/me/favorites", token=bob)["items"] == []
        print("PASS: registration, review, cross-account reading, favorites, idempotency and withdrawal")
    finally:
        # Remove the actual test content/accounts; no cleanup of unrelated user data.
        failures = []
        for token, password in people:
            try:
                call("DELETE", "/v1/me", {"password": password, "confirm": True}, token, 204)
            except Exception:
                failures.append("test account cleanup failed")
        try:
            call("POST", "/v1/auth/logout", token=admin_token, status=204)
        except Exception:
            failures.append("admin session cleanup failed")
        if failures:
            raise RuntimeError("; ".join(failures))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
