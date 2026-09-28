from alembic import context
from wenfou.config import settings
from wenfou.db import Base, make_engine
from wenfou import models  # noqa: F401

if context.is_offline_mode():
    context.configure(url=settings().database_url, target_metadata=Base.metadata, literal_binds=True)
    with context.begin_transaction():
        context.run_migrations()
else:
    engine = make_engine(settings().database_url)
    with engine.connect() as conn:
        context.configure(connection=conn, target_metadata=Base.metadata, compare_type=True)
        with context.begin_transaction():
            context.run_migrations()
    engine.dispose()
