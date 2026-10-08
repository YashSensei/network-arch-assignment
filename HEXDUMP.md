# Annotated BinHTTP exchange

Captured from this repository's Java server using `python tests/capture_exchange.py --write`.
The probe compiles the server, starts it against `www` on an OS-selected port, and opens
**one TCP connection**. Both captured frames have Flags=0, so the connection stays open.
Timestamps, the selected port and the file's modification time vary between captures.
Field definitions are in [SPEC.md](SPEC.md).

## Complete request (61 bytes)

```text
0000  00 00 00 35 01 00 00 01 01 00 0a 2f 68 65 6c 6c  |...5......./hell|
0010  6f 2e 74 78 74 03 01 00 0f 6c 6f 63 61 6c 68 6f  |o.txt....localho|
0020  73 74 3a 35 33 36 39 34 02 00 0c 73 70 65 63 2d  |st:53694...spec-|
0030  70 72 6f 62 65 2f 31 03 00 03 2a 2f 2a           |probe/1...*/*|
```

| Offset (hex) | Bytes | Field | Decoded value |
| --- | --- | --- | --- |
| 0000-0003 | `00 00 00 35` | Payload length | 53 |
| 0004 | `01` | Frame type | 1 (REQUEST) |
| 0005 | `00` | Flags | 0: keep connection open |
| 0006-0007 | `00 01` | Request ID | 1 |
| 0008 | `01` | Method | 1 (GET) |
| 0009-000a | `00 0a` | Path length | 10 |
| 000b-0014 | `2f 68 65 6c 6c 6f 2e 74 78 74` | Path | /hello.txt |
| 0015 | `03` | Header count | 3 |
| 0016 | `01` | Name ID | 1: host |
| 0017-0018 | `00 0f` | host value length | 15 |
| 0019-0027 | `6c 6f 63 61 6c 68 6f 73 74 3a 35 33 36 39 34` | host value | `localhost:53694` |
| 0028 | `02` | Name ID | 2: user-agent |
| 0029-002a | `00 0c` | user-agent value length | 12 |
| 002b-0036 | `73 70 65 63 2d 70 72 6f 62 65 2f 31` | user-agent value | `spec-probe/1` |
| 0037 | `03` | Name ID | 3: accept |
| 0038-0039 | `00 03` | accept value length | 3 |
| 003a-003c | `2a 2f 2a` | accept value | `*/*` |

## Complete response (153 bytes)

```text
0000  00 00 00 91 02 00 00 01 00 c8 07 04 00 0a 62 73  |..............bs|
0010  65 72 76 65 2f 31 2e 31 05 00 1d 54 68 75 2c 20  |erve/1.1...Thu, |
0020  30 38 20 4f 63 74 20 32 30 32 36 20 31 33 3a 32  |08 Oct 2026 13:2|
0030  30 3a 34 32 20 47 4d 54 06 00 0a 74 65 78 74 2f  |0:42 GMT...text/|
0040  70 6c 61 69 6e 07 00 02 31 34 08 00 1d 54 68 75  |plain...14...Thu|
0050  2c 20 30 38 20 4f 63 74 20 32 30 32 36 20 31 33  |, 08 Oct 2026 13|
0060  3a 31 31 3a 33 39 20 47 4d 54 09 00 11 57 2f 22  |:11:39 GMT...W/"|
0070  65 2d 31 61 31 31 62 61 34 31 32 37 65 22 0a 00  |e-1a11ba4127e"..|
0080  0a 6d 61 78 2d 61 67 65 3d 36 30 68 65 6c 6c 6f  |.max-age=60hello|
0090  2c 20 77 6f 72 6c 64 0d 0a                       |, world..|
```

| Offset (hex) | Bytes | Field | Decoded value |
| --- | --- | --- | --- |
| 0000-0003 | `00 00 00 91` | Payload length | 145 |
| 0004 | `02` | Frame type | 2 (RESPONSE) |
| 0005 | `00` | Flags | 0: keep connection open |
| 0006-0007 | `00 01` | Request ID | 1 |
| 0008-0009 | `00 c8` | Status | 200 |
| 000a | `07` | Header count | 7 |
| 000b | `04` | Name ID | 4: server |
| 000c-000d | `00 0a` | server value length | 10 |
| 000e-0017 | `62 73 65 72 76 65 2f 31 2e 31` | server value | `bserve/1.1` |
| 0018 | `05` | Name ID | 5: date |
| 0019-001a | `00 1d` | date value length | 29 |
| 001b-0037 | `54 68 75 2c 20 30 38 20 4f 63 74 20 32 30 32 36 20 31 33 3a 32 30 3a 34 32 20 47 4d 54` | date value | `Thu, 08 Oct 2026 13:20:42 GMT` |
| 0038 | `06` | Name ID | 6: content-type |
| 0039-003a | `00 0a` | content-type value length | 10 |
| 003b-0044 | `74 65 78 74 2f 70 6c 61 69 6e` | content-type value | `text/plain` |
| 0045 | `07` | Name ID | 7: content-length |
| 0046-0047 | `00 02` | content-length value length | 2 |
| 0048-0049 | `31 34` | content-length value | `14` |
| 004a | `08` | Name ID | 8: last-modified |
| 004b-004c | `00 1d` | last-modified value length | 29 |
| 004d-0069 | `54 68 75 2c 20 30 38 20 4f 63 74 20 32 30 32 36 20 31 33 3a 31 31 3a 33 39 20 47 4d 54` | last-modified value | `Thu, 08 Oct 2026 13:11:39 GMT` |
| 006a | `09` | Name ID | 9: etag |
| 006b-006c | `00 11` | etag value length | 17 |
| 006d-007d | `57 2f 22 65 2d 31 61 31 31 62 61 34 31 32 37 65 22` | etag value | `W/"e-1a11ba4127e"` |
| 007e | `0a` | Name ID | 10: cache-control |
| 007f-0080 | `00 0a` | cache-control value length | 10 |
| 0081-008a | `6d 61 78 2d 61 67 65 3d 36 30` | cache-control value | `max-age=60` |
| 008b-0098 | `68 65 6c 6c 6f 2c 20 77 6f 72 6c 64 0d 0a` | Body | 'hello, world\r\n' |

The response payload has **145 bytes**: 131 bytes of status,
header count and encoded headers, followed by **14 body bytes**.
This matches `content-length: 14`. The body is exactly `www/hello.txt`.
Each of the ten header names in this exchange uses its one-byte static ID.

## Connection reuse verified

After this captured pair, the same socket sent a second GET with ID=2 and CLOSE=1.
It received status 200, ID=2, CLOSE=1 and the same body, followed by EOF.
**One TCP connection, two successful exchanges; closure only when requested.**

The separate test suite also inserts unknown frames before valid requests and checks
that they are skipped without a reply or loss of frame boundaries.
