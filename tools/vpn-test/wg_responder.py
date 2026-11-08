#!/usr/bin/env python3
"""
Minimal WireGuard *responder* for end-to-end testing of Half-Browser's VPN.

This is a test harness, not a product component. It implements just enough of
the WireGuard protocol to let the official wireguard-go client (inside the app)
complete a handshake and send data, so the test can prove that

  * the app's tunnel really comes up,
  * the handshake is accepted by a real WireGuard peer, and
  * packets are actually carried (a UDP echo inside the tunnel).

Protocol pieces implemented (see WireGuard whitepaper, §5):
  1. Handshake initiation  (type 1) -> response (type 2)
  2. Transport data        (type 4) -> echo back with the same keypair

Crypto: X25519 (cryptography), ChaCha20-Poly1305, BLAKE2s, HMAC-BLAKE2s,
HKDF-BLAKE2s. All from the `cryptography` package.

It listens on UDP and prints one JSON line per event so the test driver can
assert on handshakes and echoes.
"""
import base64
import hashlib
import hmac
import json
import os
import socket
import struct
import sys
import threading
import time

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives import serialization

CONSTRUCTION = b"Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s"
IDENTIFIER = b"WireGuard v1 zx2c4 Jason@zx2c4.com"
LABEL_MAC1 = b"mac1----"
LABEL_COOKIE = b"cookie--"

ZERO32 = b"\x00" * 32


def h(data: bytes) -> bytes:
    return hashlib.blake2s(data, digest_size=32).digest()


def mac(key: bytes, data: bytes) -> bytes:
    return hashlib.blake2s(data, key=key, digest_size=16).digest()


def hmac_blake2s(key: bytes, data: bytes) -> bytes:
    return hmac.new(key, data, hashlib.blake2s).digest()


def kdf(key: bytes, input_: bytes, n: int) -> list:
    """HKDF-BLAKE2s as specified by WireGuard (three outputs)."""
    t0 = hmac_blake2s(key, input_)
    out = [hmac_blake2s(t0, b"\x01")]
    for i in range(1, n):
        out.append(hmac_blake2s(t0, out[i - 1] + bytes([i + 1])))
    return out


class Handshake:
    def __init__(self):
        self.c = h(CONSTRUCTION)
        self.h = h(self.c + IDENTIFIER)
        self.ck = self.c
        self.s = None           # responder static private key
        self.e = None           # responder ephemeral private key
        self.remote_static = None
        self.k = None
        self.is_initiator = False


def x25519_priv(raw: bytes):
    return X25519PrivateKey.from_private_bytes(raw)


def x25519_pub(key) -> bytes:
    return key.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )


def dh(priv_raw: bytes, pub_raw: bytes) -> bytes:
    return x25519_priv(priv_raw).exchange(X25519PublicKey.from_public_bytes(pub_raw))


def aead_encrypt(key: bytes, counter: int, plaintext: bytes, ad: bytes) -> bytes:
    nonce = b"\x00" * 4 + struct.pack("<Q", counter)
    return ChaCha20Poly1305(key).encrypt(nonce, plaintext, ad)


def aead_decrypt(key: bytes, counter: int, ciphertext: bytes, ad: bytes) -> bytes:
    nonce = b"\x00" * 4 + struct.pack("<Q", counter)
    return ChaCha20Poly1305(key).decrypt(nonce, ciphertext, ad)


class Responder:
    def __init__(self, private_key_b64: str, port: int):
        self.priv = base64.b64decode(private_key_b64)
        self.pub = x25519_pub(x25519_priv(self.priv))
        self.port = port
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("0.0.0.0", port))
        self.sock.settimeout(0.5)
        self.mac1_key = h(LABEL_MAC1 + self.pub)
        self.peer = None
        self.send_counter = 0
        self.recv_counter = 0
        self.stats = {"initiations": 0, "bad_mac1": 0, "responses": 0,
                      "data_in": 0, "data_out": 0, "bad_data": 0,
                      "handshake_errors": []}
        self.stop = False

    def log(self, **kw):
        kw["t"] = round(time.time(), 3)
        print(json.dumps(kw), flush=True)

    def handle_initiation(self, msg: bytes, addr):
        # type(1)=1, sender(4), ephemeral(32), encrypted_static(48),
        # encrypted_timestamp(28), mac1(16), mac2(16)
        if len(msg) != 148:
            self.stats["handshake_errors"].append(f"bad length {len(msg)}")
            return
        mac1 = msg[116:132]
        expected = mac(self.mac1_key, msg[:116])
        if not hmac.compare_digest(mac1, expected):
            self.stats["bad_mac1"] += 1
            self.log(event="bad_mac1")
            return
        self.stats["initiations"] += 1

        hs = Handshake()
        hs.s = self.priv
        hs.is_initiator = False
        sender_index = struct.unpack("<I", msg[4:8])[0]
        e_i = msg[8:40]
        hs.h = h(hs.h + e_i)
        hs.ck = h(hs.ck + e_i)

        # decrypt static
        (hs.ck, k) = kdf(hs.ck, dh(hs.s, e_i), 2)
        try:
            s_i = aead_decrypt(k, 0, msg[40:88], hs.h)
        except Exception as e:  # noqa: BLE001
            self.stats["handshake_errors"].append(f"static decrypt: {e}")
            return
        hs.remote_static = s_i
        hs.h = h(hs.h + msg[40:88])
        (hs.ck, _) = kdf(hs.ck, dh(hs.s, s_i), 2)

        # decrypt timestamp
        (hs.ck, k) = kdf(hs.ck, dh(hs.s, e_i), 2)
        try:
            aead_decrypt(k, 0, msg[88:116], hs.h)
        except Exception as e:  # noqa: BLE001
            self.stats["handshake_errors"].append(f"timestamp decrypt: {e}")
            return
        hs.h = h(hs.h + msg[88:116])

        # ---- build response -------------------------------------------------
        e_r_priv = os.urandom(32)
        e_r_pub = x25519_pub(x25519_priv(e_r_priv))
        hs.h = h(hs.h + e_r_pub)
        hs.ck = h(hs.ck + e_r_pub)
        (hs.ck, _) = kdf(hs.ck, dh(e_r_priv, e_i), 2)
        (hs.ck, _) = kdf(hs.ck, dh(e_r_priv, s_i), 2)
        (hs.ck, tau, k) = kdf(hs.ck, ZERO32, 3)  # psk = 0

        empty = aead_encrypt(k, 0, b"", hs.h)
        hs.h = h(hs.h + empty)

        my_index = int.from_bytes(os.urandom(4), "little")
        resp = bytearray()
        resp += struct.pack("<B", 2)
        resp += struct.pack("<I", my_index)
        resp += struct.pack("<I", sender_index)
        resp += e_r_pub
        resp += empty
        mac1 = mac(self.mac1_key, bytes(resp))
        resp += mac1
        resp += ZERO32[:16]  # mac2 (no cookie)

        # transport keys for the responder
        (hs.ck, t_recv, t_send) = kdf(hs.ck, b"", 3)
        hs.t_recv = t_recv
        hs.t_send = t_send
        self.peer = hs
        self.send_counter = 0
        self.recv_counter = 0
        self.stats["responses"] += 1
        self.sock.sendto(bytes(resp), addr)
        self.log(event="handshake_ok", peer=base64.b64encode(s_i).decode())

    def handle_data(self, msg: bytes, addr):
        # type(1)=4, receiver(4), counter(8), encrypted(16+len), mac1(16)
        if len(msg) < 32:
            return
        peer = self.peer
        if peer is None:
            self.stats["bad_data"] += 1
            return
        counter = struct.unpack("<Q", msg[5:13])[0]
        ct = msg[13:-16]
        try:
            plain = aead_decrypt(peer.t_recv, counter, ct, b"")
        except Exception:  # noqa: BLE001
            self.stats["bad_data"] += 1
            return
        self.stats["data_in"] += 1
        self.recv_counter = counter + 1
        self.log(event="data_in", counter=counter, length=len(plain),
                 ip_version=(plain[0] >> 4) if plain else None)

        # Echo the inner IP packet straight back (a stub "server").
        out = aead_encrypt(peer.t_send, self.send_counter, plain, b"")
        self.send_counter += 1
        pkt = bytearray()
        pkt += struct.pack("<B", 4)
        pkt += struct.pack("<I", 0)  # receiver index (client ignores for echo)
        pkt += struct.pack("<Q", self.send_counter - 1)
        pkt += out
        pkt += mac(self.mac1_key, bytes(pkt))
        self.sock.sendto(bytes(pkt), addr)
        self.stats["data_out"] += 1
        self.log(event="data_out", length=len(plain))

    def run(self):
        self.log(event="listening", port=self.port,
                 pub=base64.b64encode(self.pub).decode())
        last_report = time.time()
        while not self.stop:
            try:
                msg, addr = self.sock.recvfrom(4096)
            except socket.timeout:
                if time.time() - last_report > 2:
                    self.log(event="stats", **self.stats)
                    last_report = time.time()
                continue
            if not msg:
                continue
            t = msg[0]
            if t == 1:
                self.handle_initiation(msg, addr)
            elif t == 4:
                self.handle_data(msg, addr)
            elif t == 3:
                self.log(event="cookie_reply")
            else:
                self.log(event="unknown_type", type=t, length=len(msg))


def main():
    key = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("WG_PRIV", "")
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 51820
    if not key:
        print("usage: wg_responder.py <base64-private-key> [port]", file=sys.stderr)
        return 2
    Responder(key, port).run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
