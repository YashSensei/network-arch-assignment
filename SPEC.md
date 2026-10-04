# BinHTTP/1 — A Minimal Binary HTTP-like Protocol

Status: draft 1. The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.
All multi-byte integers are **unsigned, big-endian** (network byte order). All strings are
**UTF-8 bytes with no terminator**; their length always comes from a prefix field.

## 1. Connection model

- Transport is a single TCP connection. The client sends requests and the server sends responses.
  Either side MAY close the connection after any complete frame.
- Each request is exactly one `REQUEST` frame, and each response is exactly one `RESPONSE` frame.
  Bodies are never split across frames.
- The server processes requests **one at a time, in arrival order**, and sends each response in
  that same order. A client MAY send the next request before the previous response arrives
  (pipelining), but responses never arrive out of order.
- The **Request ID** pairs a request with its response. The server MUST copy the ID of each
  request into its response. The client MUST check that the ID matches the oldest request
  still waiting for a response. If it does not match, that is a protocol error and the client
  closes the connection.

## 2. Frame header (8 bytes, fixed)

Every frame starts with this header, followed by exactly `Length` bytes of payload.

| Offset | Size | Field      | Meaning                                                    |
|-------:|-----:|------------|------------------------------------------------------------|
| 0      | 4    | Length     | Payload length in bytes, not counting this 8-byte header   |
| 4      | 1    | Type       | Frame type (§3)                                            |
| 5      | 1    | Flags      | Bit flags (§4)                                             |
| 6      | 2    | Request ID | Pairs a request with its response (§1); 0 is reserved      |

**Why these widths:**
- **Length = 32 bits.** We never split a body across frames, so one frame has to carry a whole
  file. 24 bits would limit files to 16 MiB; 32 bits allows up to 4 GiB. One extra byte per
  frame costs almost nothing.
- **Type = 8 bits.** We define 2 types, which leaves 254 for extensions. Because of the skip
  rule (§5), new types can be added without breaking old receivers.
- **Flags = 8 bits.** We use 1 bit and keep 7 for later. A byte is the smallest unit we can
  address, so it costs the same as 1 bit.
- **Request ID = 16 bits.** IDs only have to tell apart the requests that are in flight at the
  same moment on one ordered connection, and 65,535 is far more than that. The ID wraps from
  65535 back to 1, so it never runs out.
- Total header size is 8 bytes. Every field sits on its natural alignment boundary, so each one
  is a single read.

**Comparison with HTTP/2's header (24/8/8/31 + 1 reserved bit = 9 bytes, RFC 9113 §4.1).**
HTTP/2 *multiplexes* many streams over one connection, and that one fact explains its widths.
- **24-bit length:** large bodies are split into many small DATA frames (default limit 16 KiB,
  ceiling 16 MiB) so frames from different streams can interleave. One big frame would block
  every other stream. Since frames are kept small, 24 bits is enough, and it saves a byte on
  every frame.
- **8-bit type and 8-bit flags:** this is the same reasoning as ours. HTTP/2 defines 10 types,
  and receivers MUST ignore unknown types. Flags are booleans whose meaning depends on the type
  (END_STREAM, END_HEADERS, PADDED, PRIORITY).
- **31-bit stream ID:** stream IDs are **never reused** on a connection, and a long-lived
  connection can open billions of streams, so the ID space must be large. Clients use odd IDs
  and servers use even ones. The top bit is reserved; this came from SPDY, and it also keeps
  the value within a signed 32-bit integer.

We do not multiplex and our IDs can wrap, so we use the opposite trade-off: a larger length
field and a smaller ID field.

## 3. Frame types

| Type      | Name     | Sent by | Payload                    |
|-----------|----------|---------|----------------------------|
| 0x01      | REQUEST  | client  | §3.1                       |
| 0x02      | RESPONSE | server  | §3.2                       |
| 0x00, 0x03–0xFF | (unassigned) | — | Opaque; MUST be skipped (§5) |

The server acts only on REQUEST frames. The client acts only on RESPONSE frames. Each side
handles every other type as unknown (§5).

### 3.1 REQUEST payload

| Size     | Field        | Meaning                                                 |
|---------:|--------------|---------------------------------------------------------|
| 1        | Method       | 0x01 = GET. No other method is defined.                 |
| 2        | Path length  | N = number of path bytes                                |
| N        | Path         | UTF-8, MUST start with `/`, e.g. `/index.html`          |
| 1        | Header count | H = number of header entries (0–255)                    |
| variable | Headers      | H entries, each in the format of §6                     |

The payload MUST end exactly after the last header, because GET requests have no body.

### 3.2 RESPONSE payload

| Size       | Field        | Meaning                                                  |
|-----------:|--------------|----------------------------------------------------------|
| 2          | Status       | Numeric status code (§7)                                 |
| 1          | Header count | H = number of header entries (0–255)                     |
| variable   | Headers      | H entries, each in the format of §6                      |
| the rest   | Body         | All remaining payload bytes (may be 0 bytes)             |

The body has no length field of its own: body length = frame `Length` minus the bytes consumed
by Status, Header count and Headers. This avoids storing the same length twice.

## 4. Flags

| Bit (mask) | Name  | Meaning                                                              |
|------------|-------|----------------------------------------------------------------------|
| 0 (0x01)   | CLOSE | The sender will close the connection after this exchange.            |
| 1–7        | —     | Reserved. Senders MUST set them to 0; receivers MUST ignore them.    |

If a REQUEST has CLOSE set, the server sends its response with CLOSE set and then closes the
connection. If a RESPONSE has CLOSE set, the client MUST NOT send more requests on that connection.
*Rationale:* this replaces HTTP/1.1's `Connection: close` header with a single bit. Ignoring
reserved bits lets future versions give them meanings without breaking old receivers.

## 5. Extensibility rule: unknown frame types

If a receiver gets a frame whose Type it does not act on (§3), it MUST read exactly `Length`
payload bytes, discard them, and carry on with the next frame header. It MUST NOT send a reply
and MUST NOT close the connection. This works because the 8-byte header has the same layout
for every type, so the receiver always knows where the next frame begins.

## 6. Header encoding (simplified HPACK)

Each header entry looks like this:

| Size | Field         | Present when | Meaning                                         |
|-----:|---------------|--------------|-------------------------------------------------|
| 1    | Name ID       | always       | 0 = literal name follows; 1–10 = table below    |
| 1    | Name length   | ID = 0       | K = number of name bytes (1–255)                |
| K    | Name          | ID = 0       | Lowercase ASCII, e.g. `x-debug`                 |
| 2    | Value length  | always       | V = number of value bytes (0–65535)             |
| V    | Value         | always       | UTF-8 text                                      |

**Static name table.** These are the 10 headers our implementations actually send:

| ID | Name           | Sent by | Example value                     |
|---:|----------------|---------|-----------------------------------|
| 1  | host           | client  | `localhost:9000`                  |
| 2  | user-agent     | client  | `bcurl/1.0`                       |
| 3  | accept         | client  | `*/*`                             |
| 4  | server         | server  | `bserve/1.0`                      |
| 5  | date           | server  | `Sun, 04 Oct 2026 19:40:00 GMT`   |
| 6  | content-type   | server  | `text/html`                       |
| 7  | content-length | server  | `1234` (decimal ASCII; equals body length) |
| 8  | last-modified  | server  | `Sat, 03 Oct 2026 10:00:00 GMT`   |
| 9  | etag           | server  | `"4d2-19a8f3c1e00"` (size-mtime in hex) |
| 10 | cache-control  | server  | `max-age=60`                      |

IDs 11–255 are reserved. A receiver that sees one MUST still read the 2-byte Value length and
skip the value, then ignore that entry. This lets the table grow later without breaking old
receivers. Dates use the HTTP IMF-fixdate format.
*Rationale:* a common header costs 1 byte for its name instead of 5–15 bytes of text. Values
stay as text so they are easy to read in a hexdump and work the same way as HTTP's.
Real HPACK adds a dynamic table and Huffman coding, which we leave out to keep it simple.

## 7. Status codes and server behaviour

| Code | When                                                                          |
|------|-------------------------------------------------------------------------------|
| 200  | The file exists and its bytes are the body                                    |
| 400  | Malformed REQUEST payload (list below)                                        |
| 403  | The path resolves outside the document root (traversal attempt)              |
| 404  | No regular file exists at the resolved path                                   |
| 500  | Server-side failure, e.g. a read error or a file of 4 GiB or more              |

**Malformed (400)** means any of the following: the payload ends before a field is complete;
bytes remain after the last header; Method ≠ 0x01; Path is empty, does not start with `/`,
contains a NUL byte or is not valid UTF-8; a literal Name length is 0; Request ID = 0.
The frame header is still intact in all of these cases, so the server replies 400 with the same
Request ID and **keeps the connection open**.

**Oversized:** if a REQUEST's `Length` is over 65,536, the server replies 400 with CLOSE set
and closes the connection without reading the payload.

**Truncated:** if the connection reaches EOF in the middle of a frame, the receiver closes the
connection without replying.

**Path mapping.** If the path ends in `/`, append `index.html` to it. Strip the leading `/`
and resolve the rest against the document root. Then canonicalize the result, resolving `.`,
`..` and symlinks. If the canonical path is not inside the canonical root, reply 403.
Non-200 responses SHOULD carry a short `text/plain` body that explains the error.

## 8. Example: `GET /index.html`, Request ID 1

```
00 00 00 20  01  00  00 01       header: Length=32, Type=REQUEST, Flags=0, ID=1
01                               Method = GET
00 0B  2F 69 6E 64 65 78 2E 68 74 6D 6C        Path (11) = "/index.html"
01                               Header count = 1
01  00 0E  6C 6F 63 61 6C 68 6F 73 74 3A 39 30 30 30   host = "localhost:9000"
```
