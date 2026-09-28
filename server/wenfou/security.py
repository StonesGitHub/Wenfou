import hashlib
import secrets
import time
from uuid import uuid4
from argon2 import PasswordHasher
from argon2.exceptions import VerificationError, InvalidHashError
from fastapi import HTTPException
from sqlalchemy import delete
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.dialects.sqlite import insert as sqlite_insert
from .models import RateBucket, SessionToken

password_hasher = PasswordHasher(time_cost=2, memory_cost=19456, parallelism=1)
DUMMY_HASH = password_hasher.hash(secrets.token_urlsafe(32))


def now():
    return int(time.time() * 1000)


def uid():
    return uuid4().hex


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def verify_password(hashed, password):
    try:
        return password_hasher.verify(hashed, password)
    except (VerificationError, InvalidHashError):
        return False


def issue_session(db, user, hours):
    token = secrets.token_urlsafe(32)
    expires = now() + hours * 3600000
    db.add(SessionToken(token_hash=digest(token), user_id=user.id, expires_at=expires))
    return {"access_token": token, "token_type": "bearer", "expires_at": expires}


def rate_limit(factory, key, limit, seconds):
    """Atomic fixed-window counter shared by workers; separate transaction survives 401s."""
    window = int(time.time()) // seconds * seconds
    with factory.begin() as db:
        insert = pg_insert if db.bind.dialect.name == "postgresql" else sqlite_insert
        stmt = insert(RateBucket).values(key=digest(key), window=window, count=1, expires_at=window + seconds)
        count = db.execute(stmt.on_conflict_do_update(
            index_elements=[RateBucket.key, RateBucket.window],
            set_={"count": RateBucket.count + 1},
        ).returning(RateBucket.count)).scalar_one()
    if count > limit:
        raise HTTPException(429, "请求过于频繁，请稍后再试", headers={"Retry-After": str(max(1, window + seconds - int(time.time())))})


def clean_expired(db):
    db.execute(delete(SessionToken).where(SessionToken.expires_at < now()))
    db.execute(delete(RateBucket).where(RateBucket.expires_at < int(time.time()) - 86400))
