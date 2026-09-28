import re
from urllib.parse import urlsplit, urlunsplit
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

Platform = Literal["DeepSeek", "ChatGPT", "豆包", "Kimi", "其他"]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Credentials(StrictModel):
    username: str = Field(min_length=3, max_length=32, pattern=r"^[a-zA-Z0-9_]+$")
    password: str = Field(min_length=12, max_length=128)

    @field_validator("username")
    @classmethod
    def normalize_username(cls, value):
        return value.lower()


class Registration(Credentials):
    display_name: str = Field(min_length=1, max_length=40)
    invite_code: str = Field(min_length=16, max_length=128)

    @field_validator("display_name")
    @classmethod
    def nonempty_name(cls, value):
        if not value.strip():
            raise ValueError("昵称不能为空")
        return value.strip()


class PasswordChange(StrictModel):
    current_password: str = Field(min_length=1, max_length=128)
    new_password: str = Field(min_length=12, max_length=128)


class DeleteAccount(StrictModel):
    password: str = Field(min_length=1, max_length=128)
    confirm: Literal[True]


def canonical_source(value, platform):
    if not value:
        return ""
    u = urlsplit(value)
    hosts = {
        "DeepSeek": {"chat.deepseek.com", "deepseek.com"},
        "ChatGPT": {"chatgpt.com", "www.chatgpt.com", "chat.openai.com"},
        "豆包": {"doubao.com", "www.doubao.com", "v.doubao.com"},
        "Kimi": {"kimi.com", "www.kimi.com", "kimi.ai", "www.kimi.ai", "kimi.moonshot.cn"},
    }
    if u.scheme != "https" or u.hostname not in hosts.get(platform, set()) or u.username or u.password or u.port not in (None, 443):
        raise ValueError("来源必须为对应平台的公开 HTTPS 分享链接")
    path = u.path.rstrip("/")
    pattern = r"/share/[A-Za-z0-9_-]+"
    if platform == "豆包":
        pattern = r"/[A-Za-z0-9_-]+" if u.hostname == "v.doubao.com" else r"/(?:thread|share|s)/[A-Za-z0-9_-]+"
    if not re.fullmatch(pattern, path):
        raise ValueError("来源必须为公开分享地址，不能填写私人会话链接")
    return urlunsplit(("https", u.hostname, path, "", ""))


class PostInput(StrictModel):
    question: str = Field(min_length=1, max_length=10000)
    answer: str = Field(min_length=1, max_length=100000)
    note: str = Field(default="", max_length=2000)
    source_platform: Platform
    source_url: str = Field(default="", max_length=2048)
    confirm_public: Literal[True]

    @field_validator("question", "answer")
    @classmethod
    def nonempty(cls, value):
        if not value.strip():
            raise ValueError("内容不能为空")
        return value.strip()

    @model_validator(mode="after")
    def source(self):
        self.source_url = canonical_source(self.source_url, self.source_platform)
        self.note = self.note.strip()
        return self


class ReviewInput(StrictModel):
    decision: Literal["approve", "reject", "remove"]
    reason: str = Field(min_length=1, max_length=1000)

    @field_validator("reason")
    @classmethod
    def reason_required(cls, value):
        if not value.strip():
            raise ValueError("必须填写处理理由")
        return value.strip()


class ReportInput(StrictModel):
    reason: str = Field(min_length=1, max_length=1000)

    @field_validator("reason")
    @classmethod
    def nonempty(cls, value):
        if not value.strip():
            raise ValueError("举报理由不能为空")
        return value.strip()
