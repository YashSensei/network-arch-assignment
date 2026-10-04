import java.io.*;
import java.net.*;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * bserve: a BinHTTP/1 server.
 *
 * Implements SPEC.md. Section numbers in comments (like §2) point to that file.
 * Usage: bserve <root-dir> <port>
 */
public class BServe {

    // §3 frame types
    static final int TYPE_REQUEST = 0x01;
    static final int TYPE_RESPONSE = 0x02;

    // §4 flags
    static final int FLAG_CLOSE = 0x01;

    // §3.1 methods
    static final int METHOD_GET = 0x01;

    // §7 a REQUEST payload larger than this gets 400 + CLOSE
    static final long MAX_REQUEST_PAYLOAD = 65_536;

    // Java arrays top out near 2 GiB, which is below the protocol's 4 GiB limit,
    // so bigger files get a 500 (a "server-side failure" under §7).
    static final long MAX_BODY = Integer.MAX_VALUE - 64 * 1024;

    // §6 static header table. The array index is the Name ID (0 means "literal name").
    static final String[] HEADER_NAMES = {
        null, "host", "user-agent", "accept", "server", "date",
        "content-type", "content-length", "last-modified", "etag", "cache-control"
    };
    static final int H_SERVER = 4, H_DATE = 5, H_CONTENT_TYPE = 6, H_CONTENT_LENGTH = 7,
                     H_LAST_MODIFIED = 8, H_ETAG = 9, H_CACHE_CONTROL = 10;

    // §6 dates use IMF-fixdate, e.g. "Sun, 04 Oct 2026 19:40:00 GMT"
    static final DateTimeFormatter HTTP_DATE =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                         .withZone(ZoneOffset.UTC);

    /** One header entry to send: a static-table ID plus its text value. */
    record Header(int id, String value) {}

    /** A successfully parsed REQUEST payload. */
    record Request(String path, Map<String, String> headers) {}

    /** Thrown when a REQUEST payload breaks one of the §7 "malformed" rules. */
    static class MalformedRequest extends Exception {
        MalformedRequest(String msg) { super(msg); }
    }

    static Path root;  // canonical document root, used for the traversal checks
    static final AtomicInteger connCounter = new AtomicInteger();

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: bserve <root-dir> <port>");
            System.exit(2);
        }
        try {
            root = Paths.get(args[0]).toRealPath();
        } catch (NoSuchFileException e) {
            System.err.println("bserve: root directory not found: " + args[0]);
            System.exit(2);
        }
        if (!Files.isDirectory(root)) {
            System.err.println("bserve: not a directory: " + root);
            System.exit(2);
        }
        int port = Integer.parseInt(args[1]);

        try (ServerSocket server = new ServerSocket(port)) {
            log(0, "serving " + root + " on port " + port);
            // One thread per connection. Inside a connection, requests are
            // handled strictly one after another (§1).
            while (true) {
                Socket sock = server.accept();
                int conn = connCounter.incrementAndGet();
                new Thread(() -> handleConnection(sock, conn)).start();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Connection loop: read a frame, handle it, repeat.
    // ---------------------------------------------------------------------

    static void handleConnection(Socket sock, int conn) {
        log(conn, "connected from " + sock.getRemoteSocketAddress());
        try (sock;
             DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()))) {

            while (true) {
                // §2: read the fixed 8-byte frame header.
                // Hitting EOF before its first byte means the client closed cleanly.
                int first = in.read();
                if (first == -1) {
                    log(conn, "client closed connection");
                    return;
                }
                byte[] header = new byte[8];
                header[0] = (byte) first;
                in.readFully(header, 1, 7);  // EOF here means a truncated frame (caught below)

                ByteBuffer h = ByteBuffer.wrap(header);   // ByteBuffer is big-endian by default
                long length = h.getInt() & 0xFFFFFFFFL;  // u32: mask so it can't go negative
                int type    = h.get() & 0xFF;            // u8
                int flags   = h.get() & 0xFF;            // u8
                int id      = h.getShort() & 0xFFFF;     // u16

                // §5: any type the server doesn't act on is skipped using its length.
                // No reply is sent and the connection stays open.
                if (type != TYPE_REQUEST) {
                    in.skipNBytes(length);
                    log(conn, String.format("skipped frame type 0x%02X (%d payload bytes)", type, length));
                    continue;
                }

                // §7 oversized: reply 400 with CLOSE, then close without reading the payload.
                if (length > MAX_REQUEST_PAYLOAD) {
                    log(conn, "id=" + id + " request frame too large (" + length + " bytes) -> 400, closing");
                    sendError(out, id, FLAG_CLOSE, 400, "request frame too large");
                    return;
                }

                byte[] payload = new byte[(int) length];
                in.readFully(payload);

                // §4: if the request has CLOSE set, the response has it too, then we close.
                boolean close = (flags & FLAG_CLOSE) != 0;
                int respFlags = close ? FLAG_CLOSE : 0;

                try {
                    Request req = parseRequest(payload, id);
                    serveFile(out, conn, id, respFlags, req.path());
                } catch (MalformedRequest e) {
                    // §7: the frame header was fine, so reply 400 and keep the connection open.
                    log(conn, "id=" + id + " malformed: " + e.getMessage() + " -> 400");
                    sendError(out, id, respFlags, 400, "malformed request: " + e.getMessage());
                }

                if (close) {
                    log(conn, "CLOSE flag set, closing connection");
                    return;
                }
            }
        } catch (EOFException e) {
            log(conn, "connection ended in the middle of a frame, closing");  // §7 truncated
        } catch (IOException e) {
            log(conn, "I/O error: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // §3.1 REQUEST payload parsing
    // ---------------------------------------------------------------------

    static Request parseRequest(byte[] payload, int id) throws MalformedRequest {
        if (id == 0) throw new MalformedRequest("request ID 0 is reserved");

        ByteBuffer buf = ByteBuffer.wrap(payload);
        try {
            // Method (u8)
            int method = buf.get() & 0xFF;
            if (method != METHOD_GET) {
                throw new MalformedRequest(String.format("unknown method 0x%02X", method));
            }

            // Path length (u16) + Path (UTF-8)
            int pathLen = buf.getShort() & 0xFFFF;
            String path = strictUtf8(readBytes(buf, pathLen));
            if (path.isEmpty()) throw new MalformedRequest("empty path");
            if (!path.startsWith("/")) throw new MalformedRequest("path must start with /");
            if (path.indexOf('\0') >= 0) throw new MalformedRequest("path contains NUL byte");

            // Header count (u8) + header entries (§6)
            int count = buf.get() & 0xFF;
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                int nameId = buf.get() & 0xFF;
                String name;
                if (nameId == 0) {
                    // Literal name: u8 length + bytes
                    int nameLen = buf.get() & 0xFF;
                    if (nameLen == 0) throw new MalformedRequest("literal header name length is 0");
                    name = new String(readBytes(buf, nameLen), StandardCharsets.UTF_8);
                } else if (nameId < HEADER_NAMES.length) {
                    name = HEADER_NAMES[nameId];
                } else {
                    name = null;  // reserved ID 11-255: read its value below, then drop the entry
                }
                int valueLen = buf.getShort() & 0xFFFF;
                String value = new String(readBytes(buf, valueLen), StandardCharsets.UTF_8);
                if (name != null) headers.put(name, value);
            }

            // GET has no body, so the payload must end right after the last header.
            if (buf.hasRemaining()) {
                throw new MalformedRequest(buf.remaining() + " extra bytes after headers");
            }
            return new Request(path, headers);

        } catch (BufferUnderflowException e) {
            // Any get() past the end of the payload ends up here.
            throw new MalformedRequest("payload ended before a field was complete");
        }
    }

    static byte[] readBytes(ByteBuffer buf, int n) {
        byte[] b = new byte[n];
        buf.get(b);  // throws BufferUnderflowException if fewer than n bytes are left
        return b;
    }

    /** Decodes UTF-8, rejecting invalid byte sequences instead of replacing them. */
    static String strictUtf8(byte[] bytes) throws MalformedRequest {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new MalformedRequest("path is not valid UTF-8");
        }
    }

    // ---------------------------------------------------------------------
    // §7 path mapping and file serving
    // ---------------------------------------------------------------------

    static void serveFile(DataOutputStream out, int conn, int id, int flags, String path) throws IOException {
        String p = path.endsWith("/") ? path + "index.html" : path;

        // Strip the leading "/" and resolve against the root. normalize() folds away
        // "." and "..", so "/../secret" turns into a path outside root.
        Path target;
        try {
            target = root.resolve(p.substring(1)).normalize();
        } catch (InvalidPathException e) {
            respondError(out, conn, id, flags, 404, path, "not found");
            return;
        }

        // Check 1: after normalizing, is the path still inside root?
        if (!target.startsWith(root)) {
            respondError(out, conn, id, flags, 403, path, "forbidden: path escapes document root");
            return;
        }
        if (!Files.isRegularFile(target)) {
            respondError(out, conn, id, flags, 404, path, "not found");
            return;
        }
        // Check 2: follow symlinks. A link inside root could point outside it.
        Path real = target.toRealPath();
        if (!real.startsWith(root)) {
            respondError(out, conn, id, flags, 403, path, "forbidden: path escapes document root");
            return;
        }

        long size = Files.size(real);
        if (size > MAX_BODY) {
            respondError(out, conn, id, flags, 500, path, "file too large");
            return;
        }
        byte[] body;
        try {
            body = Files.readAllBytes(real);
        } catch (IOException e) {
            respondError(out, conn, id, flags, 500, path, "read error");
            return;
        }

        long mtime = Files.getLastModifiedTime(real).toMillis();
        List<Header> headers = List.of(
            new Header(H_SERVER, "bserve/1.0"),
            new Header(H_DATE, HTTP_DATE.format(java.time.Instant.now())),
            new Header(H_CONTENT_TYPE, contentType(real.getFileName().toString())),
            new Header(H_CONTENT_LENGTH, Integer.toString(body.length)),
            new Header(H_LAST_MODIFIED, HTTP_DATE.format(java.time.Instant.ofEpochMilli(mtime))),
            new Header(H_ETAG, "\"" + Long.toHexString(body.length) + "-" + Long.toHexString(mtime) + "\""),
            new Header(H_CACHE_CONTROL, "max-age=60")
        );
        sendResponse(out, id, flags, 200, headers, body);
        log(conn, "id=" + id + " GET " + path + " -> 200 (" + body.length + " bytes)");
    }

    static void respondError(DataOutputStream out, int conn, int id, int flags,
                             int status, String path, String message) throws IOException {
        sendError(out, id, flags, status, message);
        log(conn, "id=" + id + " GET " + path + " -> " + status);
    }

    static String contentType(String filename) {
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";
        return switch (ext) {
            case "html", "htm" -> "text/html";
            case "txt"         -> "text/plain";
            case "css"         -> "text/css";
            case "js"          -> "text/javascript";
            case "json"        -> "application/json";
            case "png"         -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif"         -> "image/gif";
            case "svg"         -> "image/svg+xml";
            default            -> "application/octet-stream";
        };
    }

    // ---------------------------------------------------------------------
    // §3.2 RESPONSE frame writing
    // ---------------------------------------------------------------------

    /** Sends an error response with a short text/plain body (§7). */
    static void sendError(DataOutputStream out, int id, int flags, int status, String message) throws IOException {
        byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
        List<Header> headers = List.of(
            new Header(H_SERVER, "bserve/1.0"),
            new Header(H_DATE, HTTP_DATE.format(java.time.Instant.now())),
            new Header(H_CONTENT_TYPE, "text/plain"),
            new Header(H_CONTENT_LENGTH, Integer.toString(body.length))
        );
        sendResponse(out, id, flags, status, headers, body);
    }

    /**
     * Writes one RESPONSE frame:
     *   frame header (8) | status u16 | header count u8 | header entries | body
     * The server only sends static-table names, so it never writes literal names.
     */
    static void sendResponse(DataOutputStream out, int id, int flags, int status,
                             List<Header> headers, byte[] body) throws IOException {
        // Build everything before the body first, so we know the total payload length.
        ByteArrayOutputStream prefixBytes = new ByteArrayOutputStream();
        DataOutputStream prefix = new DataOutputStream(prefixBytes);
        prefix.writeShort(status);           // Status (u16)
        prefix.writeByte(headers.size());    // Header count (u8)
        for (Header hd : headers) {
            byte[] value = hd.value().getBytes(StandardCharsets.UTF_8);
            prefix.writeByte(hd.id());       // Name ID (u8)
            prefix.writeShort(value.length); // Value length (u16)
            prefix.write(value);             // Value
        }

        long length = (long) prefixBytes.size() + body.length;

        // §2 frame header. DataOutputStream writes big-endian.
        out.writeInt((int) length);          // Length (u32): the low 32 bits are exactly the unsigned value
        out.writeByte(TYPE_RESPONSE);        // Type (u8)
        out.writeByte(flags);                // Flags (u8)
        out.writeShort(id);                  // Request ID (u16), copied from the request (§1)

        prefixBytes.writeTo(out);
        out.write(body);
        out.flush();
    }

    static void log(int conn, String msg) {
        System.err.println("[bserve] " + (conn == 0 ? "" : "conn#" + conn + " ") + msg);
    }
}
