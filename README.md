# network-arch-assignment

**Track 1 - the server.** A small Java socket server for BinHTTP/1, a binary HTTP-like file protocol. It serves files beneath a document root and handles repeated requests on the same TCP connection. No framework or external runtime libraries.

Adapted from [parthdagia05/binhttp](https://github.com/parthdagia05/binhttp), starting at commit `c016c7a`. The original Git history is preserved. This submission develops the server track; the original client is not included. Python scripts are test probes, not a second project track.

## Assignment hand-in

1. **Specification:** [SPEC.pdf](SPEC.pdf), exactly two pages; editable source in [SPEC.md](SPEC.md).
2. **Program:** [server/BServe.java](server/BServe.java), with `bserve` and `bserve.cmd` launchers.
3. **Annotated exchange:** [HEXDUMP.md](HEXDUMP.md), captured from this server and annotated by field.

The calculator in the earlier HTTP/1.1 assignment demonstrates why persistent connections need exact message boundaries. This project applies that principle to binary frames. It implements the selected binary file-server track; calculator routes and a client application are outside this submission.

## Run

Install **JDK 17 or newer** and put `java` on PATH. No build step is needed for the launchers.

Linux / macOS / Git Bash:

```sh
./bserve ./www 9000
```

Windows PowerShell:

```powershell
.\bserve.cmd .\www 9000
```

Or, on any platform:

```sh
java -Dfile.encoding=UTF-8 server/BServe.java ./www 9000
```

The root must exist and be a directory. Port `0` chooses an available port and logs it. Stop the server with Ctrl+C. Diagnostics include a connection number and request ID on stderr, so repeated exchanges can be traced to one socket.

This is a custom binary protocol. A browser or ordinary HTTP curl cannot speak it. A partner's client should implement [SPEC.md](SPEC.md); for a complete local exchange, run the capture probe below.

## Verify

Tests need **Python 3.9+** and the JDK (`java` and `javac`); all test dependencies are in the standard library.

```sh
python tests/test_server.py
python tests/capture_exchange.py
```

The suite compiles with Java 17 compatibility and warnings treated as errors. It starts a server on an available port and checks it using independently encoded Python socket frames. It covers seven exchanges on one connection, six pipelined requests, fragmented input, exact binary and empty file bodies, 400/403/404 recovery, extension frames and headers, CLOSE, truncated input, request limits, path containment, and concurrent connections. A 40 MiB file is verified by SHA-256 while the Java server runs with a 32 MiB heap. Symlink tests skip when the OS does not permit creating links.

The capture probe starts a temporary server against `www`, prints one real exchange and its hexdump, then sends a second request over the same socket to verify persistence. To regenerate the annotated hand-in:

```sh
python tests/capture_exchange.py --write
```

GitHub Actions runs the suite on Linux and Windows. Local Windows verification: 15 tests passed, 1 symlink test skipped because this machine does not grant symlink creation.

## Changes from the starter

- Validate literal header names, UTF-8 values, duplicate names and GET content-length; keep the next frame readable after 400.
- Stream response bodies through an 8 KiB buffer, with the frame length covering headers plus body.
- Return appropriate file errors before starting a response and close cleanly on a failure during streaming.
- Define portable path rules, retain traversal checks, and check symlink containment before file type.
- Provide Windows launch support, independent server tests, a two-page spec and a reproducible capture.

The server keeps the starter's eight-byte frame layout, static header table and required unknown-type skip rule. Scope stays small: GET files, ordered responses, no multiplexing, TLS, dynamic compression or additional methods. One thread handles each connection. The document root is assumed to be controlled by the operator and stable while serving; this is a course project rather than a hardened public file host.

## Rebuild the printable spec (optional)

Only document generation needs ReportLab:

```sh
python -m pip install reportlab
python tools/render_spec.py
```

This rebuilds `SPEC.pdf` from `SPEC.md`. Runtime and protocol tests do not need ReportLab.
