"""
AES-256-GCM encryption/decryption for McClaude E2E encryption.

Key is derived from SHA-256(token) - the same token used for auth.
Format: raw IV[12] + ciphertext + tag[16]

Compatible with the Java implementation in the MC plugin.
"""

from __future__ import annotations

import hashlib
import os

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

IV_LENGTH = 12  # bytes


def derive_key(token: str) -> bytes:
    """Derive a 256-bit AES key from the raw token."""
    return hashlib.sha256(token.encode("utf-8")).digest()


def encrypt_raw(token: str, plaintext: bytes) -> bytes:
    """Encrypt bytes and return raw IV + ciphertext + tag."""
    key = derive_key(token)
    iv = os.urandom(IV_LENGTH)
    cipher = AESGCM(key)
    encrypted = cipher.encrypt(iv, plaintext, None)
    return iv + encrypted


def decrypt_raw(token: str, data: bytes) -> bytes:
    """Decrypt raw IV + ciphertext + tag and return plaintext bytes."""
    key = derive_key(token)
    iv = data[:IV_LENGTH]
    encrypted = data[IV_LENGTH:]
    cipher = AESGCM(key)
    return cipher.decrypt(iv, encrypted, None)
