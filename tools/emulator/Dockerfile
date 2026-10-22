FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY pyproject.toml README.md ./
COPY src ./src

RUN python -m pip install --no-cache-dir . \
    && groupadd --system senso \
    && useradd --system --gid senso --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin senso

USER senso:senso

ENTRYPOINT ["python", "-m", "senso_emulator"]
CMD ["normal", "--once"]
