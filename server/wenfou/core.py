"""Private conversation and public snapshot domain boundary.

An API adapter must authenticate a caller and pass the resulting internal user_id.
"""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import uuid4


class NotFound(Exception):
    """Resource is absent or the caller has no permission to see it."""


class InvalidAction(Exception):
    """The requested state transition or excerpt is invalid."""


def _id() -> str:
    return uuid4().hex


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass(frozen=True)
class Message:
    id: str
    role: str
    text: str


@dataclass(frozen=True)
class PublicPost:
    id: str
    author_id: str
    question: str
    answer: str
    note: str
    source: str
    created_at: str


class WenfouStore:
    def __init__(self, db: sqlite3.Connection):
        self.db = db
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys = ON")

    def initialize(self) -> None:
        with self.db:
            self.db.executescript(
                """
                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY,
                    conversation_id TEXT NOT NULL REFERENCES conversations(id),
                    role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
                    text TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS posts (
                    id TEXT PRIMARY KEY,
                    author_id TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN
                        ('pending', 'published', 'rejected', 'withdrawn')),
                    question TEXT NOT NULL,
                    answer TEXT NOT NULL,
                    note TEXT NOT NULL,
                    source TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    published_at TEXT
                );
                CREATE TABLE IF NOT EXISTS forks (
                    conversation_id TEXT PRIMARY KEY REFERENCES conversations(id),
                    post_id TEXT NOT NULL REFERENCES posts(id)
                );
                CREATE INDEX IF NOT EXISTS idx_messages_conversation
                    ON messages(conversation_id, created_at);
                CREATE INDEX IF NOT EXISTS idx_posts_public
                    ON posts(status, published_at);
                """
            )

    def create_conversation(self, user_id: str) -> str:
        if not user_id:
            raise InvalidAction("Authenticated user required")
        cid = _id()
        with self.db:
            self.db.execute(
                "INSERT INTO conversations VALUES (?, ?, ?)", (cid, user_id, _now())
            )
        return cid

    def _own_conversation(self, user_id: str, conversation_id: str) -> None:
        row = self.db.execute(
            "SELECT 1 FROM conversations WHERE id = ? AND owner_id = ?",
            (conversation_id, user_id),
        ).fetchone()
        if row is None:
            raise NotFound("Conversation not found")

    def append_message(
        self, user_id: str, conversation_id: str, role: str, text: str
    ) -> str:
        self._own_conversation(user_id, conversation_id)
        if role not in {"user", "assistant"} or not text.strip():
            raise InvalidAction("Invalid message")
        mid = _id()
        with self.db:
            self.db.execute(
                "INSERT INTO messages VALUES (?, ?, ?, ?, ?)",
                (mid, conversation_id, role, text, _now()),
            )
        return mid

    def messages(self, user_id: str, conversation_id: str) -> list[Message]:
        self._own_conversation(user_id, conversation_id)
        rows = self.db.execute(
            "SELECT id, role, text FROM messages WHERE conversation_id = ? "
            "ORDER BY created_at, rowid",
            (conversation_id,),
        ).fetchall()
        return [Message(**dict(row)) for row in rows]

    def ask(self, user_id: str, conversation_id: str, question: str) -> tuple[str, str]:
        """A deterministic local stand-in; replace through the model adapter."""
        if not question.strip():
            raise InvalidAction("Question required")
        self._own_conversation(user_id, conversation_id)
        qid = self.append_message(user_id, conversation_id, "user", question)
        answer = f"这是一个示例回答：{question.strip()}。请接入真实模型后再用于公开服务。"
        aid = self.append_message(user_id, conversation_id, "assistant", answer)
        return qid, aid

    def submit_post(
        self,
        user_id: str,
        conversation_id: str,
        question_message_id: str,
        answer_message_id: str,
        question_excerpt: str,
        answer_excerpt: str,
        note: str,
    ) -> str:
        """Submit an immutable excerpt snapshot for review, not public yet."""
        self._own_conversation(user_id, conversation_id)
        rows = self.db.execute(
            "SELECT id, role, text FROM messages "
            "WHERE conversation_id = ? AND id IN (?, ?)",
            (conversation_id, question_message_id, answer_message_id),
        ).fetchall()
        by_id = {row["id"]: row for row in rows}
        question = by_id.get(question_message_id)
        answer = by_id.get(answer_message_id)
        next_message = self.db.execute(
            "SELECT id FROM messages WHERE conversation_id = ? AND rowid > "
            "(SELECT rowid FROM messages WHERE id = ?) "
            "ORDER BY rowid LIMIT 1",
            (conversation_id, question_message_id),
        ).fetchone()
        if (
            not question
            or not answer
            or question["role"] != "user"
            or answer["role"] != "assistant"
            or next_message is None
            or next_message["id"] != answer_message_id
            or not question_excerpt.strip()
            or not answer_excerpt.strip()
            or question_excerpt not in question["text"]
            or answer_excerpt not in answer["text"]
        ):
            raise InvalidAction("Excerpts must come from this private exchange")
        pid = _id()
        with self.db:
            self.db.execute(
                "INSERT INTO posts (id, author_id, status, question, answer, note, "
                "source, created_at) VALUES (?, ?, 'pending', ?, ?, ?, 'platform_demo', ?)",
                (pid, user_id, question_excerpt, answer_excerpt, note.strip(), _now()),
            )
        return pid

    def review_post(self, post_id: str, approve: bool) -> None:
        """Privileged review operation: expose only through an authenticated reviewer."""
        status = "published" if approve else "rejected"
        with self.db:
            cursor = self.db.execute(
                "UPDATE posts SET status = ?, published_at = ? "
                "WHERE id = ? AND status = 'pending'",
                (status, _now() if approve else None, post_id),
            )
        if cursor.rowcount != 1:
            raise InvalidAction("Post is not pending")

    def withdraw_post(self, user_id: str, post_id: str) -> None:
        with self.db:
            cursor = self.db.execute(
                "UPDATE posts SET status = 'withdrawn' "
                "WHERE id = ? AND author_id = ? AND status = 'published'",
                (post_id, user_id),
            )
        if cursor.rowcount != 1:
            raise NotFound("Published post not found")

    def public_post(self, post_id: str) -> PublicPost:
        row = self.db.execute(
            "SELECT id, author_id, question, answer, note, source, created_at "
            "FROM posts WHERE id = ? AND status = 'published'",
            (post_id,),
        ).fetchone()
        if row is None:
            raise NotFound("Published post not found")
        return PublicPost(**dict(row))

    def feed(self, limit: int = 20) -> list[PublicPost]:
        if not 1 <= limit <= 50:
            raise InvalidAction("Limit must be 1..50")
        rows = self.db.execute(
            "SELECT id, author_id, question, answer, note, source, created_at "
            "FROM posts WHERE status = 'published' "
            "ORDER BY published_at DESC, id DESC LIMIT ?",
            (limit,),
        ).fetchall()
        return [PublicPost(**dict(row)) for row in rows]

    def fork(self, user_id: str, post_id: str) -> str:
        post = self.public_post(post_id)
        # Only public snapshot fields are copied.
        cid = self.create_conversation(user_id)
        with self.db:
            self.db.execute("INSERT INTO forks VALUES (?, ?)", (cid, post_id))
        self.append_message(user_id, cid, "user", post.question)
        self.append_message(user_id, cid, "assistant", post.answer)
        return cid
