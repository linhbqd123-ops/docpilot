"""
Encryption/Decryption utilities for secure key storage.

For a desktop app running offline on user's local machine:
- Uses Fernet (AES-128 with HMAC) for symmetric encryption
- Derives master key from machine hwid + fixed salt (user-machine specific)
- Keys are encrypted before saving to .env
- No encryption key stored on disk (only algorithm parameters in env)
"""

import os
import hashlib
import base64
from typing import Optional
from cryptography.fernet import Fernet


# Fixed salt for key derivation (can be user-configurable)
ENCRYPTION_SALT = b"docpilot_keys_v1"


def _get_machine_id() -> str:
    """Get machine-specific identifier for key derivation."""
    try:
        import uuid
        machine_id = str(uuid.getnode())  # MAC address (or fallback)
    except Exception:
        machine_id = "fallback"
    
    return machine_id


def _derive_master_key() -> bytes:
    """
    Derive a machine-specific master key.
    
    This is derived on-demand, not stored on disk.
    Combines: machine_id + salt + hash
    """
    machine_id = _get_machine_id()
    
    # PBKDF2-like derivation: machine_id + salt hashed multiple times
    key_material = hashlib.pbkdf2_hmac(
        "sha256",
        machine_id.encode(),
        ENCRYPTION_SALT,
        iterations=100_000,
        dklen=32,  # 256 bits for AES
    )
    
    # Convert to Fernet-compatible key (base64-encoded)
    return base64.urlsafe_b64encode(key_material)


def encrypt_key(plaintext_key: str) -> str:
    """
    Encrypt an API key.
    
    Args:
        plaintext_key: The raw API key (e.g., "sk-abc123...")
        
    Returns:
        Base64-encoded encrypted key (ready to save in .env)
    """
    master_key = _derive_master_key()
    cipher = Fernet(master_key)
    
    # Encrypt the key
    encrypted = cipher.encrypt(plaintext_key.encode())
    
    # Encode for safe storage in .env files
    return f"encrypted:{base64.b64encode(encrypted).decode()}"


def decrypt_key(encrypted_key: str) -> Optional[str]:
    """
    Decrypt an API key.
    
    Args:
        encrypted_key: The encrypted key from .env (format: "encrypted:...")
        
    Returns:
        The decrypted plaintext key, or None if decryption fails
    """
    if not encrypted_key.startswith("encrypted:"):
        # Not encrypted, return as-is (for backward compatibility)
        return encrypted_key.strip() if encrypted_key else None
    
    try:
        master_key = _derive_master_key()
        cipher = Fernet(master_key)
        
        # Extract the encrypted data (remove "encrypted:" prefix)
        encrypted_data = encrypted_key[len("encrypted:"):]
        
        # Decode from base64
        encrypted_bytes = base64.b64decode(encrypted_data)
        
        # Decrypt
        decrypted = cipher.decrypt(encrypted_bytes)
        
        return decrypted.decode().strip()
    except Exception as e:
        print(f"❌ Decryption failed: {e}")
        return None


def mask_key(key: str, show_chars: int = 4) -> str:
    """
    Mask a key for display (show only first and last N chars).
    
    Args:
        key: The full API key
        show_chars: Number of characters to show at start/end
        
    Returns:
        Masked key (e.g., "sk-****...****")
    """
    if not key or len(key) <= show_chars * 2:
        return "•" * min(len(key), 8)
    
    start = key[:show_chars]
    end = key[-show_chars:]
    
    return f"{start}****...{end}"
