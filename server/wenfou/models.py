from sqlalchemy import BigInteger, Boolean, CheckConstraint, ForeignKey, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column
from .db import Base


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    username: Mapped[str] = mapped_column(String(40), unique=True)
    display_name: Mapped[str] = mapped_column(String(40))
    password_hash: Mapped[str] = mapped_column(Text)
    role: Mapped[str] = mapped_column(String(16), default="user")
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[int] = mapped_column(BigInteger)
    __table_args__ = (CheckConstraint("role IN ('user','admin')", name="user_role"),)


class SessionToken(Base):
    __tablename__ = "sessions"
    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    expires_at: Mapped[int] = mapped_column(BigInteger, index=True)


class Invite(Base):
    __tablename__ = "invites"
    code_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    remaining: Mapped[int] = mapped_column(Integer)
    expires_at: Mapped[int] = mapped_column(BigInteger)
    __table_args__ = (CheckConstraint("remaining >= 0", name="invite_remaining"),)


class Post(Base):
    __tablename__ = "community_posts"
    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    author_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    status: Mapped[str] = mapped_column(String(16), default="pending")
    question: Mapped[str] = mapped_column(Text)
    answer: Mapped[str] = mapped_column(Text)
    note: Mapped[str] = mapped_column(Text)
    source_platform: Mapped[str] = mapped_column(String(20))
    source_url: Mapped[str] = mapped_column(Text)
    created_at: Mapped[int] = mapped_column(BigInteger)
    published_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    review_reason: Mapped[str] = mapped_column(Text, default="")
    request_key: Mapped[str] = mapped_column(String(80))
    payload_hash: Mapped[str] = mapped_column(String(64))
    __table_args__ = (
        CheckConstraint("status IN ('pending','published','rejected','withdrawn','removed')", name="post_status"),
        UniqueConstraint("author_id", "request_key", name="post_author_request"),
        Index("ix_posts_feed", "status", "published_at", "id"),
        Index("ix_posts_owner", "author_id", "created_at", "id"),
    )


class Favorite(Base):
    __tablename__ = "favorites"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), primary_key=True)
    post_id: Mapped[str] = mapped_column(ForeignKey("community_posts.id"), primary_key=True)
    created_at: Mapped[int] = mapped_column(BigInteger)


class Report(Base):
    __tablename__ = "reports"
    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    post_id: Mapped[str] = mapped_column(ForeignKey("community_posts.id"))
    reason: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(16), default="open")
    created_at: Mapped[int] = mapped_column(BigInteger)
    resolution: Mapped[str] = mapped_column(Text, default="")
    __table_args__ = (
        UniqueConstraint("user_id", "post_id", name="one_report_per_user_post"),
        CheckConstraint("status IN ('open','resolved')", name="report_status"),
        Index("ix_reports_queue", "status", "created_at"),
    )


class Audit(Base):
    __tablename__ = "audit_events"
    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    actor_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    action: Mapped[str] = mapped_column(String(32))
    target_id: Mapped[str] = mapped_column(String(32))
    reason: Mapped[str] = mapped_column(Text)
    created_at: Mapped[int] = mapped_column(BigInteger, index=True)


class RateBucket(Base):
    __tablename__ = "rate_buckets"
    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    window: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    count: Mapped[int] = mapped_column(Integer)
    expires_at: Mapped[int] = mapped_column(BigInteger, index=True)
