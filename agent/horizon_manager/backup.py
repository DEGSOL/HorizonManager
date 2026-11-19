import base64
import binascii
import json
import os

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

MAGIC = b"HORIZON-MANAGER-BACKUP\x01"


def cipher(password, salt):
    key = Scrypt(salt=salt, length=32, n=2**15, r=8, p=1).derive(password.encode())
    return AESGCM(key)


def pack(payload, password):
    salt, nonce = os.urandom(16), os.urandom(12)
    encrypted = cipher(password, salt).encrypt(nonce, json.dumps(payload, ensure_ascii=False).encode(), MAGIC)
    return base64.b64encode(MAGIC + salt + nonce + encrypted).decode()


def unpack(encoded, password):
    try:
        data = base64.b64decode(encoded, validate=True)
        if not data.startswith(MAGIC) or len(data) < len(MAGIC) + 44:
            raise ValueError
        payload = data[len(MAGIC):]
        return json.loads(cipher(password, payload[:16]).decrypt(payload[16:28], payload[28:], MAGIC))
    except (binascii.Error, InvalidTag, ValueError, UnicodeDecodeError):
        raise ValueError("BACKUP_INVALID") from None
