#!/usr/bin/env python3
"""Emit a base64 TLS ClientHello for one hostname (the device-side traffic primitive).

The device shell has `nc` but no TLS client, so the harness sends real ClientHello bytes
produced here. The blob is the ClientHello followed by padding: the relay credits activity in
>= 512-byte chunks (MIN_ACTIVITY_BYTES), so one `base64 -d | nc` write must cross that.

Usage: domainusage-hello-gen.py <hostname> <out.b64> [padding_bytes]
"""
import base64
import ssl
import sys


def client_hello(hostname: str) -> bytes:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ctx.set_alpn_protocols(["http/1.1"])
    inbio, outbio = ssl.MemoryBIO(), ssl.MemoryBIO()
    sslobj = ctx.wrap_bio(inbio, outbio, server_hostname=hostname)
    try:
        sslobj.do_handshake()
    except ssl.SSLWantReadError:
        pass
    return outbio.read()


def main() -> int:
    hostname, out = sys.argv[1], sys.argv[2]
    padding = int(sys.argv[3]) if len(sys.argv) > 3 else 2048
    hello = client_hello(hostname)
    blob = hello + b"P" * padding
    with open(out, "wb") as f:
        f.write(base64.b64encode(blob))
    print(f"{out}: hello={len(hello)} total={len(blob)} host={hostname}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
