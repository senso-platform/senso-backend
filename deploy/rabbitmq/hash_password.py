#!/usr/bin/env python3
"""Хэш пароля для deploy/rabbitmq/definitions.json (rabbit_password_hashing_sha256).

    python deploy/rabbitmq/hash_password.py my-password
"""
import base64
import hashlib
import os
import sys

if len(sys.argv) != 2:
    sys.exit("usage: hash_password.py <password>")
salt = os.urandom(4)
digest = hashlib.sha256(salt + sys.argv[1].encode("utf-8")).digest()
print(base64.b64encode(salt + digest).decode())
