"""Run: python tests/test_server.py. Requires JDK 17+ and Python 3.9+."""

import contextlib
import hashlib
from pathlib import Path
import socket
import struct
import tempfile
import unittest

from support import frame, header, request, response, running_server


class ServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cleanup = contextlib.ExitStack()
        cls.addClassCleanup(cls.cleanup.close)
        base = Path(cls.cleanup.enter_context(tempfile.TemporaryDirectory(prefix="binhttp-tests-")))
        cls.root = base / "root"
        cls.root.mkdir()
        (cls.root / "hello.txt").write_bytes(b"hello, world\n")
        (cls.root / "index.html").write_bytes(b"<h1>home</h1>\n")
        (cls.root / "sub").mkdir()
        (cls.root / "sub/index.html").write_bytes(b"sub page\n")
        (cls.root / "empty.txt").touch()
        (cls.root / "binary.bin").write_bytes(bytes(range(256)) * 128)
        (cls.root / "caf\u00e9.txt").write_bytes(b"utf8 filename\n")
        (cls.root / "%68ello.txt").write_bytes(b"literal percent\n")
        (base / "secret.txt").write_bytes(b"OUTSIDE ROOT")
        cls.symlinks = False
        try:
            (cls.root / "escape").symlink_to(base / "secret.txt")
            (cls.root / "outside-dir").symlink_to(base, target_is_directory=True)
            (cls.root / "inside").symlink_to(cls.root / "hello.txt")
            cls.symlinks = True
        except OSError:
            pass
        cls.server = cls.cleanup.enter_context(running_server(cls.root))

    def check_response(self, sock, status=200, request_id=1, flags=0, body=None):
        result = response(sock)
        self.assertEqual(result[:3], (status, flags, request_id))
        if body is not None:
            self.assertEqual(result[4], body)
        return result

    def test_six_requests_on_one_connection_then_seventh(self):
        with self.server.connect() as sock:
            cases = [("/hello.txt", 200), ("/", 200), ("/missing", 404),
                     ("/../secret.txt", 403), ("relative", 400), ("/binary.bin", 200)]
            for i, (path, status) in enumerate(cases, 1):
                sock.sendall(frame(request(path), i))
                self.check_response(sock, status, i)
            # A successful seventh exchange proves the same socket is still usable.
            sock.sendall(frame(request(), 7, flags=1))
            self.check_response(sock, request_id=7, flags=1, body=b"hello, world\n")
            self.assertEqual(sock.recv(1), b"")

    def test_pipelined_responses_remain_in_order(self):
        with self.server.connect() as sock:
            paths = ["/", "/missing", "relative", "/hello.txt", "/empty.txt", "/sub/"]
            sock.sendall(b"".join(frame(request(p), i + 1) for i, p in enumerate(paths)))
            for i, status in enumerate([200, 404, 400, 200, 200, 200], 1):
                self.check_response(sock, status, i)
            sock.sendall(frame(request(), 7, 1))
            self.check_response(sock, request_id=7, flags=1)

    def test_fragmented_frame_and_next_frame_boundary(self):
        with self.server.connect() as sock:
            wire = frame(request(headers=[header(1, "localhost"), header("x-note", "ok")]))
            for byte in wire:
                sock.sendall(bytes([byte]))
            self.check_response(sock, body=b"hello, world\n")
            sock.sendall(frame(request("/empty.txt"), 2, 1))
            self.check_response(sock, request_id=2, flags=1, body=b"")

    def test_unknown_frames_are_skipped_without_reply_or_close(self):
        with self.server.connect() as sock:
            # Include a fake REQUEST inside an opaque frame; CLOSE on unknown is ignored.
            unknown = frame(frame(request(), 55), 0, 255, kind=127)
            unknown += frame(b"", 0, 1, kind=0) + frame(b"\x00\xc8\x00", kind=2)
            unknown += frame(b"x" * 100_000, kind=255)
            sock.sendall(unknown + frame(request(), 9))
            self.check_response(sock, request_id=9, body=b"hello, world\n")
            sock.sendall(frame(request(), 10, 1))
            self.check_response(sock, request_id=10, flags=1)
            self.assertEqual(sock.recv(1), b"")

    def test_header_extensions_and_utf8(self):
        with self.server.connect() as sock:
            headers = [header(1, "localhost"), header(2, "spec-probe/1"), header(3, "*/*"),
                       header("x-note", "caf\u00e9"), header(11, b"\xff\xfe"), header(7, "00")]
            sock.sendall(frame(request("/caf\u00e9.txt", headers)))
            result = self.check_response(sock, body=b"utf8 filename\n")
            self.assertEqual(set(result[3]), {"server", "date", "content-type", "content-length",
                                            "last-modified", "etag", "cache-control"})
            self.assertTrue(result[3]["etag"].startswith('W/"'))

    def test_malformed_requests_recover_on_same_connection(self):
        malformed = [
            b"", b"\x01", b"\x01\xff\xff/x", request(method=2), request(""), request("relative"),
            request(b"/bad\x00"), request(b"/\xff"), request() + b"extra",
            request(headers=[b"\x00\x00\x00\x00"]), request(headers=[b"\x01\xff\xffx"]),
            request(headers=[header("X-Test", "bad")]), request(headers=[header("x bad", "bad")]),
            request(headers=[header(1, b"\xff")]), request(headers=[header(7, "1")]),
            request(headers=[header("content-length", "-1")]), request(headers=[header(7, "")]),
            request(headers=[header(7, "0"), header("content-length", "1")]),
            request("/C:/secret"), request("/..\\secret.txt"), request("//host/share"),
        ]
        with self.server.connect() as sock:
            for i, payload in enumerate(malformed, 1):
                with self.subTest(case=i):
                    sock.sendall(frame(payload, i))
                    self.check_response(sock, 400, i)
            sock.sendall(frame(request(), 100, 1))
            self.check_response(sock, request_id=100, flags=1, body=b"hello, world\n")

    def test_request_id_zero_and_wrap(self):
        with self.server.connect() as sock:
            for request_id, status in [(0, 400), (65535, 200), (1, 200)]:
                sock.sendall(frame(request(), request_id))
                self.check_response(sock, status, request_id)

    def test_request_size_limit_and_unsigned_length(self):
        payload = request(headers=[header("x-pad", b"x" * 65513)])
        self.assertEqual(len(payload), 65536)
        with self.server.connect() as sock:
            sock.sendall(frame(payload))
            self.check_response(sock)
        for length in (65537, 0xFFFFFFFF):
            with self.subTest(length=length), self.server.connect() as sock:
                sock.sendall(struct.pack("!IBBH", length, 1, 0, 42))
                self.check_response(sock, 400, 42, flags=1)
                self.assertEqual(sock.recv(1), b"")

    def test_close_flag_on_success_and_error(self):
        for path, status in [("/hello.txt", 200), ("/missing", 404), ("relative", 400)]:
            with self.subTest(path=path), self.server.connect() as sock:
                sock.sendall(frame(request(path), flags=1))
                self.check_response(sock, status, flags=1)
                self.assertEqual(sock.recv(1), b"")

    def test_reserved_flags_are_ignored(self):
        with self.server.connect() as sock:
            sock.sendall(frame(request(), flags=254))
            self.check_response(sock)
            sock.sendall(frame(request(), 2, flags=255))
            self.check_response(sock, request_id=2, flags=1)

    def test_truncated_frames_close_without_response(self):
        wires = [frame(request())[:4], frame(request())[:-1], frame(b"unknown", kind=99)[:-1]]
        for wire in wires:
            with self.subTest(wire=wire), self.server.connect() as sock:
                sock.sendall(wire)
                sock.shutdown(socket.SHUT_WR)
                self.assertEqual(sock.recv(1), b"")
        with self.server.connect() as sock:
            sock.sendall(frame(request(), flags=1))
            self.check_response(sock, flags=1)

    def test_path_mapping_and_raw_file_bytes(self):
        cases = [("/", b"<h1>home</h1>\n", "text/html"),
                 ("/sub/", b"sub page\n", "text/html"),
                 ("/sub/../hello.txt", b"hello, world\n", "text/plain"),
                 ("/empty.txt", b"", "text/plain"),
                 ("/%68ello.txt", b"literal percent\n", "text/plain"),
                 ("/binary.bin", bytes(range(256)) * 128, "application/octet-stream")]
        with self.server.connect() as sock:
            for i, (path, body, mime) in enumerate(cases, 1):
                sock.sendall(frame(request(path), i))
                result = self.check_response(sock, request_id=i, body=body)
                self.assertEqual(result[3]["content-type"], mime)
            for i, path in enumerate(["/sub", "/hello.txt/child", "/missing"], 10):
                sock.sendall(frame(request(path), i))
                self.check_response(sock, 404, i)

    def test_traversal_is_rejected_and_connection_survives(self):
        with self.server.connect() as sock:
            for i, path in enumerate(["/../secret.txt", "/sub/../../secret.txt", "/./../secret.txt"], 1):
                sock.sendall(frame(request(path), i))
                result = self.check_response(sock, 403, i)
                self.assertNotIn(b"OUTSIDE ROOT", result[4])
            sock.sendall(frame(request(), 4, 1))
            self.check_response(sock, request_id=4, flags=1)

    def test_symlinks_stay_inside_root(self):
        if not self.symlinks:
            self.skipTest("OS does not permit creating symlinks")
        with self.server.connect() as sock:
            for i, path in enumerate(["/escape", "/outside-dir", "/outside-dir/secret.txt"], 1):
                sock.sendall(frame(request(path), i))
                self.check_response(sock, 403, i)
            sock.sendall(frame(request("/inside"), 4, 1))
            self.check_response(sock, request_id=4, flags=1, body=b"hello, world\n")

    def test_file_larger_than_java_heap_streams_correctly(self):
        path = self.root / "large.bin"
        block = bytes(range(256)) * 4096
        expected = hashlib.sha256()
        try:
            with path.open("wb") as file:
                for _ in range(40):  # 40 MiB file, server has a 32 MiB Java heap.
                    file.write(block)
                    expected.update(block)
            with self.server.connect() as sock:
                sock.sendall(frame(request("/large.bin")))
                result = self.check_response(sock)
                self.assertEqual(len(result[4]), 40 * 1024 * 1024)
                self.assertEqual(hashlib.sha256(result[4]).digest(), expected.digest())
                sock.sendall(frame(request(), 2, 1))
                self.check_response(sock, request_id=2, flags=1)
        finally:
            path.unlink(missing_ok=True)

    def test_idle_connection_does_not_block_another(self):
        with self.server.connect(), self.server.connect() as second:
            second.sendall(frame(request(), flags=1))
            self.check_response(second, flags=1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
