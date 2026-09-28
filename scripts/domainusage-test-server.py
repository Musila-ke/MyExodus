#!/usr/bin/env python3
"""Domain-usage metering (§12.2) test server.

Host-side TLS/TCP/HTTP endpoints for the instrumented matrix, all stdlib:

  * 443 / 8443  TLS, SNI-routed (one shared self-signed cert; the tests only care that
                the ClientHello carries the hostname).
  * 80          plain HTTP: /  /burst  /stream (1 KB every 2 s)  /sink (throughput sink).
  * 8080        plain TCP, same HTTP endpoints as port 80 (DNS-attribution leg). The banner
                alone was never enough: a 23-byte reply is below MIN_ACTIVITY_BYTES, so an
                I-3/I-13 leg on this port accrued nothing at all (device-caught 2026-09-21).
  * 9443        the same handler bound to the ULA address only — the IPv6-only test port.

Every accepted connection logs one line to --log, and TLS listeners log a second line with
the accept→first-application-byte delta, which is what PF-2's p95 is computed from:

    accept port=443 addr=<ipv4|ipv6> t=<epoch_ms>
    hello  port=443 dt_ms=<int> sni=<0|1> t=<epoch_ms>

The sni= field records only whether an SNI was present (never the name — S-5 applies to the
app's logs, and the harness log stays name-free for the same reason).

Usage: domainusage-test-server.py --log /tmp/du-server.log [--bind-all]
"""
from __future__ import annotations

import argparse
import os
import socket
import socketserver
import ssl
import subprocess
import sys
import threading
import time

CERT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache")
CERT = os.path.join(CERT_DIR, "du-test-server.pem")

_log_lock = threading.Lock()
_log_path = ""
_sni_seen = threading.local()


def log(line: str) -> None:
    with _log_lock:
        with open(_log_path, "a") as f:
            f.write(line + "\n")
        print(line, flush=True)


def epoch_ms() -> int:
    return int(time.time() * 1000)


def host_addresses() -> tuple[list[str], list[str]]:
    """The host's IPv4 and IPv6 addresses the devices can dial (en0 + loopback)."""
    v4, v6 = ["127.0.0.1"], []
    out = subprocess.run(["ifconfig", "en0"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("inet "):
            v4.append(line.split()[1])
        elif line.startswith("inet6 ") and "fe80" not in line:
            v6.append(line.split()[1].split("%")[0])
    return v4, v6


def ensure_cert(v4: list[str], v6: list[str]) -> None:
    if os.path.exists(CERT):
        return
    os.makedirs(CERT_DIR, exist_ok=True)
    sans = ["DNS:*.nip.io", "DNS:*.sslip.io", "IP:127.0.0.1", "IP:10.0.2.2"]
    sans += [f"IP:{a}" for a in v4] + [f"IP:{a}" for a in v6]
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
         "-keyout", CERT, "-out", CERT, "-days", "365", "-nodes",
         "-subj", "/CN=domainusage-test-server",
         "-addext", "subjectAltName=" + ",".join(sans)],
        check=True, capture_output=True,
    )
    log(f"cert generated {CERT}")


def tls_context() -> ssl.SSLContext:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(CERT)
    ctx.set_alpn_protocols(["http/1.1"])

    def on_sni(_sock, name, _ctx_in):
        _sni_seen.present = name is not None
        _sni_seen.ms = epoch_ms()
        # Logged here, not after the handshake: a synthesized ClientHello from the device
        # (nc + pre-built bytes) never completes the handshake, and the ClientHello arrival
        # is exactly what PF-2 measures.
        base = getattr(_sni_seen, "accept_ms", None)
        if base:
            log(f"hello port={_sni_seen.port} dt_ms={_sni_seen.ms - base} "
                f"sni={1 if name is not None else 0} t={_sni_seen.ms}")

    ctx.sni_callback = on_sni
    return ctx


class Handler(socketserver.BaseRequestHandler):
    """One connection: log accept, then serve the protocol for this port."""

    def handle(self) -> None:
        port = self.server.server_address[1]
        accept_ms = epoch_ms()
        family = "v6" if ":" in str(self.client_address[0]) else "v4"
        log(f"accept port={port} addr={family} t={accept_ms}")
        try:
            if port in (443, 8443, 9443):
                tls = self._wrap_tls(accept_ms, port)
                if tls is None:
                    return
                log(f"handshake port={port} ok t={epoch_ms()}")
                self._http(tls)
            elif port in (80, 8080):
                self._http(self.request)
            else:
                self.request.sendall(b"domainusage-test-server\n")
                self.request.recv(4096)
        except (ssl.SSLError, ConnectionError, OSError):
            pass

    def _wrap_tls(self, accept_ms: int, port: int) -> ssl.SSLSocket | None:
        _sni_seen.present = False
        _sni_seen.ms = 0
        _sni_seen.accept_ms = accept_ms
        _sni_seen.port = port
        try:
            return self.server.tls_ctx.wrap_socket(self.request, server_side=True)
        except (ssl.SSLError, ConnectionError, OSError):
            return None

    def _http(self, sock) -> None:
        data = b""
        while b"\r\n\r\n" not in data and len(data) < 8192:
            chunk = sock.recv(4096)
            if not chunk:
                return
            data += chunk
        path = data.split(b" ", 2)[1] if b" " in data else b"/"
        if path == b"/stream":
            self._stream(sock)
        elif path == b"/sink":
            self._sink(sock)
        elif path == b"/burst":
            body = b"x" * (256 * 1024)
            sock.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(body) + body)
        else:
            body = b"domainusage-test-server ok\n"
            sock.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(body) + body)

    def _stream(self, sock) -> None:
        """1 KB every 2 s (I-7): the long-lived metered connection."""
        sock.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                     b"Transfer-Encoding: chunked\r\n\r\n")
        try:
            while True:
                sock.sendall(b"%x\r\n%s\r\n" % (1024, b"s" * 1024))
                time.sleep(2.0)
        except (ConnectionError, OSError):
            return

    def _sink(self, sock) -> None:
        """Throughput sink (PF-1): read and discard as fast as the client can send."""
        sock.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\n")
        total = 0
        try:
            while True:
                chunk = sock.recv(1 << 16)
                if not chunk:
                    break
                total += len(chunk)
        except (ConnectionError, OSError):
            pass
        log(f"sink bytes={total}")


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    tls_ctx: ssl.SSLContext

    def handle_error(self, request, client_address):
        pass


class ServerV6(Server):
    address_family = socket.AF_INET6

    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
        super().server_bind()


def serve(host: str, port: int, ctx: ssl.SSLContext | None) -> Server:
    cls = ServerV6 if ":" in host else Server
    srv = cls((host, port), Handler)
    srv.tls_ctx = ctx if ctx else None
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    log(f"listening {host}:{port} {'tls' if ctx else 'plain'}")
    return srv


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default="/tmp/domainusage-test-server.log")
    ap.add_argument("--v6-host", default=None, help="ULA address for the IPv6-only test port")
    args = ap.parse_args()

    global _log_path
    _log_path = args.log
    open(_log_path, "w").close()

    v4, v6 = host_addresses()
    ensure_cert(v4, v6)
    ctx = tls_context()

    # Wildcard binds only: macOS denies a privileged-port bind to a specific local address
    # (EACCES) but allows it on the wildcard, and the wildcard covers every address anyway.
    serve("0.0.0.0", 443, ctx)
    serve("0.0.0.0", 8443, ctx)
    serve("0.0.0.0", 80, None)
    serve("0.0.0.0", 8080, None)
    serve("::", 443, ctx)
    serve("::", 8080, None)
    serve("::", 9443, ctx)  # the IPv6-only test port (no v4 listener on 9443)

    log(f"host v4={v4} v6={v6}")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())
