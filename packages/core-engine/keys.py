from __future__ import annotations

import os
from pathlib import Path
from base64 import b64decode

from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import serialization, hashes


KEY_DIR = Path(__file__).resolve().parent
PRIVATE_KEY_PATH = KEY_DIR / "private_key.pem"
PUBLIC_KEY_PATH = KEY_DIR / "public_key.pem"


def _ensure_keys() -> None:
    KEY_DIR.mkdir(parents=True, exist_ok=True)

    if not PRIVATE_KEY_PATH.exists() or not PUBLIC_KEY_PATH.exists():
        private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

        priv_pem = private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        )

        pub_pem = private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )

        PRIVATE_KEY_PATH.write_bytes(priv_pem)
        PUBLIC_KEY_PATH.write_bytes(pub_pem)

        try:
            os.chmod(PRIVATE_KEY_PATH, 0o600)
        except Exception:
            # Best-effort; non-critical on Windows
            pass


def get_public_key_pem() -> str:
    _ensure_keys()
    return PUBLIC_KEY_PATH.read_text()


def decrypt_base64(encrypted_b64: str) -> str:
    _ensure_keys()
    priv = serialization.load_pem_private_key(PRIVATE_KEY_PATH.read_bytes(), password=None)
    encrypted = b64decode(encrypted_b64)
    decrypted = priv.decrypt(
        encrypted,
        padding.OAEP(mgf=padding.MGF1(algorithm=hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
    )
    return decrypted.decode("utf-8").strip()  # Strip trailing whitespace from decrypted key
