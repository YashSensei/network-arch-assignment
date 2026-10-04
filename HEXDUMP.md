# Annotated hexdump

One complete exchange, captured with:

    ./bserve ./www 9000
    ./bcurl -v localhost:9000/hello.txt

`>` lines are bytes the client sent and `<` lines are bytes it received. Offsets are in hex.
Section numbers refer to [SPEC.md](SPEC.md).

## Raw capture

```
> REQUEST frame: length=49 type=0x01 flags=0x01 id=1 (57 bytes total)
> 00000000  00 00 00 31 01 01 00 01 01 00 0a 2f 68 65 6c 6c  ...1......./hell
> 00000010  6f 2e 74 78 74 03 01 00 0e 6c 6f 63 61 6c 68 6f  o.txt....localho
> 00000020  73 74 3a 39 30 30 30 02 00 09 62 63 75 72 6c 2f  st:9000...bcurl/
> 00000030  31 2e 30 03 00 03 2a 2f 2a                       1.0...*/*
< RESPONSE frame: length=142 type=0x02 flags=0x01 id=1 (150 bytes total)
< 00000000  00 00 00 8e 02 01 00 01 00 c8 07 04 00 0a 62 73  ..............bs
< 00000010  65 72 76 65 2f 31 2e 30 05 00 1d 53 75 6e 2c 20  erve/1.0...Sun, 
< 00000020  30 34 20 4f 63 74 20 32 30 32 36 20 31 34 3a 32  04 Oct 2026 14:2
< 00000030  37 3a 31 35 20 47 4d 54 06 00 0a 74 65 78 74 2f  7:15 GMT...text/
< 00000040  70 6c 61 69 6e 07 00 02 31 33 08 00 1d 53 75 6e  plain...13...Sun
< 00000050  2c 20 30 34 20 4f 63 74 20 32 30 32 36 20 31 34  , 04 Oct 2026 14
< 00000060  3a 31 33 3a 33 37 20 47 4d 54 09 00 0f 22 64 2d  :13:37 GMT..."d-
< 00000070  31 61 31 30 37 34 33 35 64 36 30 22 0a 00 0a 6d  1a107435d60"...m
< 00000080  61 78 2d 61 67 65 3d 36 30 68 65 6c 6c 6f 2c 20  ax-age=60hello, 
< 00000090  77 6f 72 6c 64 0a                                world.
```

## Request (57 bytes = 8 header + 49 payload)

| Offset | Bytes | Field | Value | Meaning |
|---|---|---|---|---|
| 00–03 | `00 00 00 31` | Length (§2) | 49 | 49 payload bytes follow the 8-byte header |
| 04 | `01` | Type | 0x01 | REQUEST |
| 05 | `01` | Flags | 0x01 | CLOSE: this is the client's only request, so it asks the server to close afterwards |
| 06–07 | `00 01` | Request ID | 1 | The client's first request; the response must carry the same ID |
| 08 | `01` | Method (§3.1) | 0x01 | GET |
| 09–0A | `00 0a` | Path length | 10 | The next 10 bytes are the path |
| 0B–14 | `2f 68 65 6c 6c 6f 2e 74 78 74` | Path | `/hello.txt` | File requested, UTF-8 |
| 15 | `03` | Header count | 3 | Three header entries follow |
| 16 | `01` | Name ID (§6) | 1 | Static table entry `host` |
| 17–18 | `00 0e` | Value length | 14 | |
| 19–26 | `6c 6f 63 61 6c 68 6f 73 74 3a 39 30 30 30` | Value | `localhost:9000` | Host and port as typed |
| 27 | `02` | Name ID | 2 | `user-agent` |
| 28–29 | `00 09` | Value length | 9 | |
| 2A–32 | `62 63 75 72 6c 2f 31 2e 30` | Value | `bcurl/1.0` | Client name and version |
| 33 | `03` | Name ID | 3 | `accept` |
| 34–35 | `00 03` | Value length | 3 | |
| 36–38 | `2a 2f 2a` | Value | `*/*` | Any content type is fine |

The payload ends right after the last header, as §3.1 requires for GET (there is no body).

## Response (150 bytes = 8 header + 142 payload)

| Offset | Bytes | Field | Value | Meaning |
|---|---|---|---|---|
| 00–03 | `00 00 00 8e` | Length (§2) | 142 | 142 payload bytes follow |
| 04 | `02` | Type | 0x02 | RESPONSE |
| 05 | `01` | Flags | 0x01 | CLOSE echoed (§4): the server closes after this frame |
| 06–07 | `00 01` | Request ID | 1 | Copied from the request, which pairs the two (§1) |
| 08–09 | `00 c8` | Status (§3.2) | 200 | OK |
| 0A | `07` | Header count | 7 | Seven header entries follow |
| 0B | `04` | Name ID (§6) | 4 | `server` |
| 0C–0D | `00 0a` | Value length | 10 | |
| 0E–17 | `62 73 65 72 76 65 2f 31 2e 30` | Value | `bserve/1.0` | Server name and version |
| 18 | `05` | Name ID | 5 | `date` |
| 19–1A | `00 1d` | Value length | 29 | |
| 1B–37 | `53 75 6e 2c 20 30 34 20 4f 63 74 20 32 30 32 36 20 31 34 3a 32 37 3a 31 35 20 47 4d 54` | Value | `Sun, 04 Oct 2026 14:27:15 GMT` | When the response was sent (IMF-fixdate) |
| 38 | `06` | Name ID | 6 | `content-type` |
| 39–3A | `00 0a` | Value length | 10 | |
| 3B–44 | `74 65 78 74 2f 70 6c 61 69 6e` | Value | `text/plain` | Taken from the `.txt` extension |
| 45 | `07` | Name ID | 7 | `content-length` |
| 46–47 | `00 02` | Value length | 2 | |
| 48–49 | `31 33` | Value | `13` | Body length as decimal text; must match the real body (§6) |
| 4A | `08` | Name ID | 8 | `last-modified` |
| 4B–4C | `00 1d` | Value length | 29 | |
| 4D–69 | `53 75 6e 2c 20 30 34 20 4f 63 74 20 32 30 32 36 20 31 34 3a 31 33 3a 33 37 20 47 4d 54` | Value | `Sun, 04 Oct 2026 14:13:37 GMT` | The file's modification time |
| 6A | `09` | Name ID | 9 | `etag` |
| 6B–6C | `00 0f` | Value length | 15 | |
| 6D–7B | `22 64 2d 31 61 31 30 37 34 33 35 64 36 30 22` | Value | `"d-1a107435d60"` | File size (0xd = 13) and mtime in ms, both in hex |
| 7C | `0a` | Name ID | 10 | `cache-control` |
| 7D–7E | `00 0a` | Value length | 10 | |
| 7F–88 | `6d 61 78 2d 61 67 65 3d 36 30` | Value | `max-age=60` | The response may be cached for 60 s |
| 89–95 | `68 65 6c 6c 6f 2c 20 77 6f 72 6c 64 0a` | Body | `hello, world\n` | The file's bytes: everything left in the payload (§3.2) |

**Body length check:** 142 payload bytes − 2 (status) − 1 (count) − 126 (headers) = 13 bytes,
which matches `content-length: 13`.

**Header compression:** the 10 header names in this exchange take 10 bytes, one Name ID each.
As text (`host`, `user-agent`, … `cache-control`) the same names would take 86 bytes.
