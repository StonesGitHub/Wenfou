#!/usr/bin/env python3
"""Human operator CLI for authenticated review. Never approves automatically."""
import argparse
import getpass
import json
from urllib import request, error
from urllib.parse import urlsplit


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--base-url', required=True)
    p.add_argument('--admin', default='administrator')
    sub = p.add_subparsers(dest='action', required=True)
    listing = sub.add_parser('list')
    listing.add_argument('--status', choices=['pending', 'published', 'rejected', 'removed', 'withdrawn'], default='pending')
    listing.add_argument('--offset', type=int, default=0)
    review = sub.add_parser('review')
    review.add_argument('post_id')
    review.add_argument('decision', choices=['approve', 'reject', 'remove'])
    review.add_argument('--reason', required=True)
    sub.add_parser('reports')
    resolve = sub.add_parser('resolve')
    resolve.add_argument('report_id')
    resolve.add_argument('--reason', required=True)
    args = p.parse_args()
    base = args.base_url.rstrip('/')
    parsed = urlsplit(base)
    if parsed.scheme != 'https' and not (parsed.scheme == 'http' and parsed.hostname in {'127.0.0.1', 'localhost'}):
        p.error('Use HTTPS, or localhost through an SSH tunnel')
    token = None

    def call(method, path, data=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        req = request.Request(base + path, data=json.dumps(data).encode() if data else None, headers=headers, method=method)
        try:
            with request.urlopen(req, timeout=20) as response:
                body = response.read()
                return json.loads(body) if body else None
        except error.HTTPError as exc:
            raise SystemExit(f'HTTP {exc.code}: {exc.read().decode()}')

    password = getpass.getpass('Admin password: ')
    token = call('POST', '/v1/auth/login', {'username': args.admin, 'password': password})['access_token']
    try:
        if args.action == 'list':
            result = call('GET', f'/v1/admin/posts?status={args.status}&offset={args.offset}')
        elif args.action == 'review':
            result = call('POST', f'/v1/admin/posts/{args.post_id}/review', {'decision': args.decision, 'reason': args.reason})
        elif args.action == 'reports':
            result = call('GET', '/v1/admin/reports')
        else:
            result = call('POST', f'/v1/admin/reports/{args.report_id}/resolve', {'reason': args.reason})
        print(json.dumps(result, ensure_ascii=False, indent=2))
    finally:
        call('POST', '/v1/auth/logout')


if __name__ == '__main__':
    main()
