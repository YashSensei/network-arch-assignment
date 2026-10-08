"""Capture one real exchange and verify reuse; --write regenerates HEXDUMP.md."""

import argparse
import struct

from support import NAMES, REPO, frame, header, request, response, running_server


def hexdump(wire):
    lines = []
    for offset in range(0, len(wire), 16):
        chunk = wire[offset:offset + 16]
        chars = "".join(chr(b) if 32 <= b <= 126 else "." for b in chunk)
        lines.append(f"{offset:04x}  {chunk.hex(' '):47}  |{chars}|")
    return "\n".join(lines)


def annotate(wire, is_request):
    """Walk every field and assert that the annotation covers the entire frame."""
    rows = ["| Offset (hex) | Bytes | Field | Decoded value |",
            "| --- | --- | --- | --- |"]
    pos = 0

    def take(n, label, value):
        nonlocal pos
        data = wire[pos:pos + n]
        assert len(data) == n
        span = f"{pos:04x}" if n == 1 else f"{pos:04x}-{pos + n - 1:04x}"
        rows.append(f"| {span} | `{data.hex(' ')}` | {label} | {value} |")
        pos += n

    length, kind, flags, request_id = struct.unpack_from("!IBBH", wire)
    take(4, "Payload length", str(length))
    take(1, "Frame type", f"{kind} ({'REQUEST' if is_request else 'RESPONSE'})")
    take(1, "Flags", f"{flags}: keep connection open")
    take(2, "Request ID", str(request_id))
    if is_request:
        take(1, "Method", "1 (GET)")
        n, = struct.unpack_from("!H", wire, pos)
        take(2, "Path length", str(n))
        take(n, "Path", wire[pos:pos + n].decode("utf-8"))
    else:
        status, = struct.unpack_from("!H", wire, pos)
        take(2, "Status", str(status))
    count = wire[pos]
    take(1, "Header count", str(count))
    for _ in range(count):
        name_id = wire[pos]
        assert 1 <= name_id <= 10  # This particular captured exchange uses indexed names.
        name = NAMES[name_id]
        take(1, "Name ID", f"{name_id}: {name}")
        n, = struct.unpack_from("!H", wire, pos)
        take(2, f"{name} value length", str(n))
        take(n, f"{name} value", f"`{wire[pos:pos + n].decode('utf-8')}`")
    metadata_length = pos - 8
    if not is_request:
        take(len(wire) - pos, "Body", repr(wire[pos:].decode("utf-8")))
    assert pos == len(wire) == 8 + length
    return "\n".join(rows), metadata_length


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="replace HEXDUMP.md with this capture")
    args = parser.parse_args()
    with running_server(REPO / "www") as server, server.connect() as sock:
        wire = frame(request(headers=[header(1, f"localhost:{server.port}"),
                                      header(2, "spec-probe/1"), header(3, "*/*")]))
        sock.sendall(wire)
        first = response(sock)
        assert first[:3] == (200, 0, 1)
        assert first[4] == (REPO / "www/hello.txt").read_bytes()
        # No second connection: prove the first response left the socket usable.
        sock.sendall(frame(request(), request_id=2, flags=1))
        second = response(sock)
        assert second[:3] == (200, 1, 2) and second[4] == first[4]
        assert sock.recv(1) == b""

    req_table, _ = annotate(wire, True)
    res_table, metadata_length = annotate(first[5], False)
    document = f"""# Annotated BinHTTP exchange

Captured from this repository's Java server using `python tests/capture_exchange.py --write`.
The probe compiles the server, starts it against `www` on an OS-selected port, and opens
**one TCP connection**. Both captured frames have Flags=0, so the connection stays open.
Timestamps, the selected port and the file's modification time vary between captures.
Field definitions are in [SPEC.md](SPEC.md).

## Complete request ({len(wire)} bytes)

```text
{hexdump(wire)}
```

{req_table}

## Complete response ({len(first[5])} bytes)

```text
{hexdump(first[5])}
```

{res_table}

The response payload has **{len(first[5]) - 8} bytes**: {metadata_length} bytes of status,
header count and encoded headers, followed by **{len(first[4])} body bytes**.
This matches `content-length: {len(first[4])}`. The body is exactly `www/hello.txt`.
Each of the ten header names in this exchange uses its one-byte static ID.

## Connection reuse verified

After this captured pair, the same socket sent a second GET with ID=2 and CLOSE=1.
It received status 200, ID=2, CLOSE=1 and the same body, followed by EOF.
**One TCP connection, two successful exchanges; closure only when requested.**

The separate test suite also inserts unknown frames before valid requests and checks
that they are skipped without a reply or loss of frame boundaries.
"""
    if args.write:
        with (REPO / "HEXDUMP.md").open("w", encoding="utf-8", newline="\n") as output:
            output.write(document)
        print("Wrote HEXDUMP.md from a live exchange.")
    else:
        print(document)
    print("Verified: one TCP connection, two responses, CLOSE honoured.")


if __name__ == "__main__":
    main()
