"""A small marking demo: seven exchanges over one real TCP connection."""

import argparse
import socket

from support import REPO, frame, header, request, response, running_server


def demonstrate(host, port):
    cases = [
        ("/index.html", 200),
        ("/hello.txt", 200),
        ("/missing-demo-file", 404),
        ("relative-path", 400),
        ("/../outside-demo-file", 403),
        ("/hello.txt", 200),
    ]
    frames = []
    for request_id, (path, _) in enumerate(cases, 1):
        headers = [header("x-demo", "literal header"), header(3, "*/*")]
        frames.append(frame(request(path, headers), request_id))
        if request_id == 2:
            # A future frame type contains bytes that look like another request.
            # Its CLOSE flag and embedded ID 99 must both be ignored.
            frames.append(frame(frame(request(), 99), kind=127, flags=1))

    with socket.create_connection((host, port), timeout=10) as sock:
        print("Sending six pipelined requests plus an unknown frame in one batch.")
        sock.sendall(b"".join(frames))
        print("ID  STATUS  PATH")
        hello = None
        for request_id, (path, status) in enumerate(cases, 1):
            result = response(sock)
            assert result[:3] == (status, 0, request_id), f"unexpected response: {result[:3]}"
            if path == "/hello.txt":
                if hello is None:
                    hello = result[4]
                else:
                    assert result[4] == hello, "file body changed across exchanges"
            print(f"{request_id:<3} {status:<7} {path}")

        # This request is sent only AFTER reading all six responses. Its success
        # proves the connection survived both the errors and the unknown frame.
        sock.sendall(frame(request("/hello.txt"), request_id=7, flags=1))
        last = response(sock)
        assert last[:3] == (200, 1, 7) and last[4] == hello
        assert sock.recv(1) == b"", "server did not close after acknowledging CLOSE"
        print("7   200     /hello.txt (follow-up with CLOSE)")
    print("PASS: one TCP connection, seven ordered responses.")
    print("PASS: unknown type skipped; 400/403/404 did not break the connection.")
    print("PASS: response lengths verified; CLOSE acknowledged, then EOF.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="running server host")
    parser.add_argument("--port", type=int, default=9000, help="running server port (default: 9000)")
    parser.add_argument("--self-test", action="store_true", help="start and stop a temporary server against www")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    try:
        if args.self_test:
            with running_server(REPO / "www") as server:
                demonstrate("127.0.0.1", server.port)
        else:
            demonstrate(args.host, args.port)
    except (OSError, AssertionError) as exc:
        parser.exit(1, f"Demo failed: {exc}\n")


if __name__ == "__main__":
    main()
