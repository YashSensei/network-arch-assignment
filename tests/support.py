"""Independent wire helpers from SPEC.md; no Java implementation is imported."""

import contextlib
from pathlib import Path
import queue
import re
import socket
import struct
import subprocess
import tempfile
import threading

REPO = Path(__file__).resolve().parents[1]
NAMES = (None, "host", "user-agent", "accept", "server", "date", "content-type",
         "content-length", "last-modified", "etag", "cache-control")


def frame(payload, request_id=1, flags=0, kind=1):
    return struct.pack("!IBBH", len(payload), kind, flags, request_id) + payload


def header(name, value):
    value = value.encode("utf-8") if isinstance(value, str) else value
    if isinstance(name, int):
        prefix = bytes([name])
    else:
        name = name.encode("ascii")
        prefix = bytes([0, len(name)]) + name
    return prefix + struct.pack("!H", len(value)) + value


def request(path="/hello.txt", headers=(), method=1):
    path = path.encode("utf-8") if isinstance(path, str) else path
    return bytes([method]) + struct.pack("!H", len(path)) + path + bytes([len(headers)]) + b"".join(headers)


def read_exact(sock, count):
    data = bytearray()
    while len(data) < count:
        chunk = sock.recv(min(count - len(data), 65536))
        if not chunk:
            raise AssertionError(f"EOF after {len(data)} of {count} expected bytes")
        data.extend(chunk)
    return bytes(data)


def response(sock):
    wire_header = read_exact(sock, 8)
    length, kind, flags, request_id = struct.unpack("!IBBH", wire_header)
    assert kind == 2, f"expected RESPONSE, got {kind}"
    assert flags in (0, 1), f"reserved response flags: {flags}"
    payload = read_exact(sock, length)
    status, count = struct.unpack_from("!HB", payload)
    pos = 3
    headers = {}
    for _ in range(count):
        name_id = payload[pos]
        pos += 1
        if name_id == 0:
            n = payload[pos]
            pos += 1
            assert n > 0
            name = payload[pos:pos + n].decode("ascii")
            pos += n
        else:
            name = NAMES[name_id] if name_id < len(NAMES) else None
        n, = struct.unpack_from("!H", payload, pos)
        pos += 2
        assert pos + n <= len(payload), "header value exceeds payload"
        value = payload[pos:pos + n].decode("utf-8")
        pos += n
        if name is not None:
            assert name not in headers, f"duplicate response header: {name}"
            headers[name] = value
    body = payload[pos:]
    assert headers.get("content-length") == str(len(body)), "incorrect content-length"
    return status, flags, request_id, headers, body, wire_header + payload


class Server:
    def __init__(self, port, process, logs, events):
        self.port, self.process, self.logs, self.events = port, process, logs, events

    def connect(self):
        return socket.create_connection(("127.0.0.1", self.port), timeout=10)

    def next_log(self):
        line = self.events.get(timeout=10)
        assert line is not None, "server exited before writing its next log entry"
        return line.rstrip("\r\n")


@contextlib.contextmanager
def running_server(root):
    """Compile with Java 17 checks; run on an OS-selected port with a 32 MiB heap."""
    with tempfile.TemporaryDirectory(prefix="binhttp-build-") as build:
        subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-Xlint:all",
                        "-Werror", "-d", build, str(REPO / "server/BServe.java")],
                       check=True, capture_output=True, text=True, timeout=30)
        process = subprocess.Popen(
            ["java", "-Xmx32m", "-Dfile.encoding=UTF-8", "-cp", build, "BServe", str(root), "0"],
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, encoding="utf-8")
        lines = queue.Queue()
        logs = []

        def collect():
            for line in process.stderr:
                logs.append(line.rstrip())
                lines.put(line)
            lines.put(None)

        reader = threading.Thread(target=collect, daemon=True)
        reader.start()
        try:
            line = lines.get(timeout=20)
            match = re.search(r" on port (\d+)$", line or "")
            if not match:
                raise AssertionError(f"server did not start: {line}")
            yield Server(int(match[1]), process, logs, lines)
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            reader.join(timeout=5)
            process.stderr.close()
