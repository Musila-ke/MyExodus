#!/usr/bin/env python3
"""Build/parse a single raw DNS probe for the §12.2 device matrix.

The device shell has no DNS client that can be pointed at an arbitrary server (`nslookup`
is absent, `nc` speaks UDP but does not build messages), and the probes must originate *on*
the device: the advertised resolver (192.0.2.53 / fd00:14:14::53) only exists inside the
TUN, so a host-side query could never reach the app's handler.

  build <qname> <qtype> <out.b64>   one query, base64 for `base64 -d | nc -u -w 3 <srv> 53`
  parse <in.b64>                    rcode + answers of the captured reply

Only the fields the matrix asserts on are printed (rcode, answer types/rdatas).
"""
from __future__ import annotations

import base64
import os
import struct
import sys

QTYPES = {"A": 1, "AAAA": 28, "HTTPS": 65, "SVCB": 64, "TXT": 16, "CNAME": 5}


def build(qname: str, qtype: str) -> bytes:
    header = struct.pack("!HHHHHH", int.from_bytes(os.urandom(2), "big"), 0x0100, 1, 0, 0, 0)
    q = b"".join(bytes([len(part)]) + part.encode("ascii") for part in qname.rstrip(".").split(".")) + b"\x00"
    return header + q + struct.pack("!HH", QTYPES[qtype.upper()], 1)


def read_name(data: bytes, offset: int) -> tuple[str, int]:
    labels, jumped, end = [], False, offset
    while True:
        length = data[offset]
        if length & 0xC0 == 0xC0:
            pointer = struct.unpack_from("!H", data, offset)[0] & 0x3FFF
            if not jumped:
                end = offset + 2
            offset, jumped = pointer, True
            continue
        if length == 0:
            return ".".join(labels), (end if jumped else offset + 1)
        labels.append(data[offset + 1:offset + 1 + length].decode("ascii", "replace"))
        offset += 1 + length


def parse(data: bytes) -> int:
    tid, flags, qd, an, ns, ar = struct.unpack_from("!HHHHHH", data, 0)
    rcode = flags & 0xF
    print(f"id={tid} rcode={rcode} qd={qd} an={an} ns={ns} ar={ar}")
    offset = 12
    for _ in range(qd):
        _, offset = read_name(data, offset)
        offset += 4
    for _ in range(an):
        name, offset = read_name(data, offset)
        rtype, rclass, ttl, rdlen = struct.unpack_from("!HHIH", data, offset)
        rdata = data[offset + 10:offset + 10 + rdlen]
        value = _rdata(rtype, rdata)
        print(f"an name={name} type={rtype} ttl={ttl} rdata={value}")
        offset += 10 + rdlen
    return 0


def _rdata(rtype: int, rdata: bytes) -> str:
    if rtype == 1 and len(rdata) == 4:
        return ".".join(str(b) for b in rdata)
    if rtype == 28 and len(rdata) == 16:
        return ":".join(f"{rdata[i]:02x}{rdata[i + 1]:02x}" for i in range(0, 16, 2))
    if rtype in (5, 2):
        return f"<{len(rdata)}B-name>"
    return rdata.hex()


def main() -> int:
    mode = sys.argv[1]
    if mode == "build":
        qname, qtype, out = sys.argv[2], sys.argv[3], sys.argv[4]
        with open(out, "wb") as f:
            f.write(base64.b64encode(build(qname, qtype)))
        print(f"{out}: {qname} {qtype} {len(build(qname, qtype))}B")
    elif mode == "parse":
        with open(sys.argv[2], "rb") as f:
            return parse(base64.b64decode(f.read()))
    else:
        print(__doc__, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
