from sqlalchemy import create_engine, event
from sqlalchemy.orm import DeclarativeBase, sessionmaker


class Base(DeclarativeBase):
    pass


def make_engine(url):
    kwargs = {"pool_pre_ping": True}
    if url.startswith("sqlite"):
        kwargs["connect_args"] = {"check_same_thread": False, "timeout": 15}
    else:
        kwargs.update(pool_size=5, max_overflow=5, pool_timeout=10)
    engine = create_engine(url, **kwargs)
    if url.startswith("sqlite"):
        @event.listens_for(engine, "connect")
        def sqlite_options(conn, _):
            conn.execute("PRAGMA foreign_keys=ON")
    return engine


def sessions(engine):
    return sessionmaker(engine, expire_on_commit=False)
