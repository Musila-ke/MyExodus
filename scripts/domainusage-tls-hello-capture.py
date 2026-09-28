#!/usr/bin/env python3
"""One-shot ClientHello capture: accepts TCP connections, saves the first TLS record
(a ClientHello) per connection, and reports its shape. No TLS is performed — Chrome
sends the ClientHello before any certificate check, so the bytes arrive regardless.

Usage: tls-hello-capture.py <port> <out-dir> [seconds]
"""
import socket
import sys
import threading
import time
from pathlib import Path


def ext_types(hello: bytes) -> set[int]:
    """Extension type ids from a ClientHello record, best-effort."""
    # 5B record header; 4B handshake header; 2+32 legacy_version+random; session_id; ciphers; compression
    try:
        p = 5 + 4 + 2 + 32
        sid = hello[p]
        p += 1 + sid
        cs = int.from_bytes(hello[p:p + 2], "big")
        p += 2 + cs
        comp = hello[p]
        p += 1 + comp
        total = int.from_bytes(hello[p:p + 2], "big")
        p += 2
        end = p + total
        out = set()
        while p + 4 <= end and p + 4 <= len(hello):
            t = int.from_bytes(hello[p:p + 2], "big")
            ln = int.from_bytes(hello[p + 2:p + 4], "big")
            out.add(t)
            p += 4 + ln
        return out
    except Exception:
        return set()


def handle(conn: socket.socket, addr, out_dir: Path, index: int) -> None:
    try:
        conn.settimeout(3.0)
        buf = b""
        while len(buf) < 16384:
            chunk = conn.recv(16384 - len(buf))
            if not chunk:
                break
            buf += chunk
            if len(buf) >= 6:
                need = 5 + int.from_bytes(buf[3:5], "big")
                if len(buf) >= need:
                    break
        if buf[:1] != b"\x16" or buf[1:2] != b"\x03":
            print(f"[{index}] from {addr[0]} not-TLS {len(buf)}B", flush=True)
            return
        need = 5 + int.from_bytes(buf[3:5], "big")
        hello = buf[:need]
        exts = ext_types(hello)
        ech = 0xFE0D in exts
        pq = 0x11EC in exts or b"\x11\xec" in hello[:2000]
        path = out_dir / f"hello-{index:02d}.bin"
        path.write_bytes(hello)
        print(
            f"[{index}] from {addr[0]} {len(hello)}B saved {path.name} "
            f"extensions={len(exts)} ech={ech} pq_keyshare={pq} exts={sorted(hex(t) for t in exts)}",
            flush=True,
        )
    except Exception as e:  # noqa: BLE001
        print(f"[{index}] from {addr[0]} err={type(e).__name__}", flush=True)
    finally:
        try:
            conn.close()
        except Exception:
            pass


def main() -> None:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9445
    out_dir = Path(sys.argv[2] if len(sys.argv) > 2 else "/tmp/hello-capture")
    seconds = int(sys.argv[3]) if len(sys.argv) > 3 else 300
    out_dir.mkdir(parents=True, exist_ok=True)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port))
    srv.listen(64)
    srv.settimeout(1.0)
    print(f"listening on 0.0.0.0:{port}, out={out_dir}, {seconds}s", flush=True)
    deadline = time.time() + seconds
    index = 0
    while time.time() < deadline:
        try:
            conn, addr = srv.accept()
        except socket.timeout:
            continue
        index += 1
        threading.Thread(target=handle, args=(conn, addr, out_dir, index), daemon=True).start()
    srv.close()
    print("done", flush=True)


if __name__ == "__main__":
    main()
