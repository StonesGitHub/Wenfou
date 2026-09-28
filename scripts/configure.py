#!/usr/bin/env python3
"""Create or validate a deployment env file without shell evaluation or secret output."""
import argparse
import ipaddress
import os
from pathlib import Path
import re
import secrets
import stat

ROOT = Path(__file__).resolve().parents[1]
KEYS = {"WENFOU_MODE", "WENFOU_ENVIRONMENT", "WENFOU_PUBLIC_URL", "WENFOU_LOCAL_PORT", "WENFOU_DOMAIN", "ACME_EMAIL", "POSTGRES_DB", "POSTGRES_PASSWORD", "APP_DB_PASSWORD", "WENFOU_REGISTRATION_ENABLED"}


def load(path):
    values = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        if not sep or key not in KEYS or key in values:
            raise ValueError("Unknown, repeated or malformed configuration key")
        values[key] = value
    if values.keys() != KEYS:
        raise ValueError("Missing required configuration keys")
    if stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise ValueError("Environment file must be mode 600 (chmod 600)")
    if values["WENFOU_MODE"] not in {"local", "production"}:
        raise ValueError("Mode must be local or production")
    if any(not re.fullmatch(r"[a-f0-9]{64}", values[key]) for key in ("POSTGRES_PASSWORD", "APP_DB_PASSWORD")):
        raise ValueError("Database password must be 64 hex characters; use configure.py")
    if not re.fullmatch(r"wenfou(?:_restore_\d{8}_\d{6})?", values["POSTGRES_DB"]):
        raise ValueError("Invalid database name")
    if values["WENFOU_REGISTRATION_ENABLED"] not in {"true", "false"}:
        raise ValueError("Registration setting must be true or false")
    if not values["WENFOU_LOCAL_PORT"].isdigit() or not 1024 <= int(values["WENFOU_LOCAL_PORT"]) <= 65535:
        raise ValueError("Local port must be 1024..65535")
    if values["WENFOU_MODE"] == "production":
        domain = values["WENFOU_DOMAIN"]
        if not re.fullmatch(r"(?=.{4,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}", domain):
            raise ValueError("Use a real lowercase DNS domain, no scheme or path")
        try:
            ipaddress.ip_address(domain)
        except ValueError:
            pass
        else:
            raise ValueError("A DNS domain is required")
        if domain.endswith((".invalid", ".example", ".localhost", ".local")) or domain in {"example.com", "example.org", "example.net"} or ".example." in domain:
            raise ValueError("Replace the example domain with your own domain")
        if not re.fullmatch(r"[A-Za-z0-9_.+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}", values["ACME_EMAIL"]):
            raise ValueError("Certificate contact email is required")
        if values["WENFOU_PUBLIC_URL"] != "https://" + domain or values["WENFOU_ENVIRONMENT"] != "production":
            raise ValueError("Production mode requires matching HTTPS URL and environment")
    elif values["WENFOU_ENVIRONMENT"] != "development" or values["WENFOU_PUBLIC_URL"] != "http://localhost:" + values["WENFOU_LOCAL_PORT"]:
        raise ValueError("Local mode must use a localhost URL and development environment")
    return values


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--file", type=Path, default=ROOT / "deploy/.env")
    p.add_argument("--mode", choices=["local", "production"], default="local")
    p.add_argument("--domain", default="")
    p.add_argument("--email", default="")
    p.add_argument("--local-port", type=int, default=8000)
    p.add_argument("--check", action="store_true")
    p.add_argument("--get", choices=["WENFOU_MODE", "WENFOU_LOCAL_PORT", "WENFOU_PUBLIC_URL"])
    args = p.parse_args()
    if args.check or args.get:
        values = load(args.file)
        print(values[args.get] if args.get else "Configuration valid (secrets hidden)")
        return
    values = dict(WENFOU_MODE=args.mode, WENFOU_ENVIRONMENT="production" if args.mode == "production" else "development",
                  WENFOU_PUBLIC_URL="https://" + args.domain if args.mode == "production" else f"http://localhost:{args.local_port}",
                  WENFOU_LOCAL_PORT=str(args.local_port), WENFOU_DOMAIN=args.domain, ACME_EMAIL=args.email,
                  POSTGRES_DB="wenfou", POSTGRES_PASSWORD=secrets.token_hex(32), APP_DB_PASSWORD=secrets.token_hex(32), WENFOU_REGISTRATION_ENABLED="true")
    args.file.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation prevents accidentally rotating the password of an existing DB volume.
    fd = os.open(args.file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w") as output:
            output.write("".join(f"{k}={v}\n" for k, v in values.items()))
        load(args.file)
    except Exception:
        args.file.unlink()
        raise
    print(f"Created {args.file}; mode 600. No credentials printed.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as exc:
        raise SystemExit(str(exc))
