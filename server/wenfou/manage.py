"""Offline administration. Run inside API container; secrets never go in argv."""
import argparse
import getpass
import secrets
from sqlalchemy import delete, select
from .config import settings
from .db import make_engine, sessions
from .models import Audit, Invite, SessionToken, User
from .schemas import Credentials
from .security import clean_expired, digest, now, password_hasher, uid


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="command", required=True)
    admin = sub.add_parser("create-admin")
    admin.add_argument("--username", required=True)
    invite = sub.add_parser("invite")
    invite.add_argument("--uses", type=int, default=1)
    invite.add_argument("--days", type=int, default=7)
    reset = sub.add_parser("reset-password")
    reset.add_argument("--username", required=True)
    sub.add_parser("cleanup")
    args = p.parse_args()
    engine = make_engine(settings().database_url)
    with sessions(engine).begin() as db:
        if args.command == "invite":
            if not 1 <= args.uses <= 100 or not 1 <= args.days <= 30:
                p.error("uses must be 1..100 and days 1..30")
            code = secrets.token_urlsafe(24)
            db.add(Invite(code_hash=digest(code), remaining=args.uses, expires_at=now() + args.days * 86400000))
            db.flush()
            # Print only once, after transaction successfully committed below.
        elif args.command in {"create-admin", "reset-password"}:
            password = getpass.getpass("Password (12-128 characters): ")
            if password != getpass.getpass("Repeat password: "):
                p.error("Passwords do not match")
            creds = Credentials(username=args.username, password=password)
            user = db.scalar(select(User).where(User.username == creds.username).with_for_update())
            if args.command == "create-admin":
                if user:
                    p.error("Username already exists; this command does not promote existing users")
                user = User(id=uid(), username=creds.username, display_name="问否管理员", role="admin", active=True, created_at=now(), password_hash=password_hasher.hash(password))
                db.add(user)
                db.flush()
            else:
                if not user or not user.active:
                    p.error("Active user not found")
                user.password_hash = password_hasher.hash(password)
                db.execute(delete(SessionToken).where(SessionToken.user_id == user.id))
            db.add(Audit(id=uid(), actor_id=user.id, action="cli_" + args.command.replace("-", "_"), target_id=user.id, reason="服务器维护命令", created_at=now()))
        else:
            clean_expired(db)
    engine.dispose()
    if args.command == "invite":
        print(code)
    else:
        print("Done")


if __name__ == "__main__":
    main()
