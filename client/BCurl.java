import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * bcurl: a command-line client for BinHTTP/1 (see SPEC.md).
 *
 * Usage: java BCurl.java [-v] host:port/path [host:port/path ...]
 *
 * All URLs must share one host:port. They are sent one at a time as REQUEST frames over a
 * single TCP connection. Each response body is written to stdout. With -v, every frame is
 * hexdumped to stderr.
 *
 * Exit codes: 0 = all responses 2xx/3xx, 1 = some response 4xx/5xx,
 *             2 = usage error, connection failure or protocol error.
 */
public class BCurl {

    // §2: the frame header is always 8 bytes.
    static final int HEADER_SIZE = 8;

    // §3: frame types.
    static final int TYPE_REQUEST = 0x01;
    static final int TYPE_RESPONSE = 0x02;

    // §4: flag bits.
    static final int FLAG_CLOSE = 0x01;

    // §3.1: the only method.
    static final int METHOD_GET = 0x01;

    // §6: static header name table. Index = Name ID; index 0 means "literal name follows".
    static final String[] STATIC_NAMES = {
        null, "host", "user-agent", "accept", "server", "date",
        "content-type", "content-length", "last-modified", "etag", "cache-control"
    };
    static final int ID_HOST = 1;
    static final int ID_USER_AGENT = 2;
    static final int ID_ACCEPT = 3;

    // §7: the server rejects REQUEST payloads longer than this.
    static final int MAX_REQUEST_PAYLOAD = 65536;

    // Exit codes.
    static final int EXIT_OK = 0;
    static final int EXIT_HTTP_ERROR = 1;
    static final int EXIT_FATAL = 2;

    /** Thrown for usage errors (bad command line). */
    static class UsageException extends Exception {
        UsageException(String message) { super(message); }
    }

    /** Thrown when the server breaks the protocol (bad frame, wrong ID, early close...). */
    static class ProtocolException extends Exception {
        ProtocolException(String message) { super(message); }
    }

    /** One parsed URL: "host:port/path". */
    record Target(String host, int port, String hostPort, String path) { }

    /** One frame as it travels on the wire (§2). */
    record Frame(int type, int flags, int id, byte[] payload) { }

    /** One decoded header entry (§6). */
    record Header(String name, String value) { }

    /** A decoded RESPONSE payload (§3.2). */
    record Response(int status, List<Header> headers, byte[] body) { }

    // Set by the -v option.
    static boolean verbose = false;

    public static void main(String[] args) {
        int exitCode;
        try {
            List<Target> targets = parseArguments(args);
            exitCode = run(targets);
        } catch (UsageException e) {
            System.err.println("bcurl: " + e.getMessage());
            System.err.println("usage: bcurl [-v] host:port/path [host:port/path ...]");
            exitCode = EXIT_FATAL;
        }
        System.out.flush();
        System.exit(exitCode);
    }

    // ------------------------------------------------------------------
    // Command line
    // ------------------------------------------------------------------

    /** Reads "-v" and the URLs; checks that every URL has the same host:port. */
    static List<Target> parseArguments(String[] args) throws UsageException {
        List<Target> targets = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("-v")) {
                verbose = true;
            } else if (arg.startsWith("-")) {
                throw new UsageException("unknown option " + arg);
            } else {
                targets.add(parseTarget(arg));
            }
        }
        if (targets.isEmpty()) {
            throw new UsageException("no URL given");
        }
        String first = targets.get(0).hostPort();
        for (Target t : targets) {
            if (!t.hostPort().equals(first)) {
                throw new UsageException("all URLs must use the same host:port (one connection)");
            }
        }
        return targets;
    }

    /** Splits "host:port/path" into its parts. A missing path means "/". */
    static Target parseTarget(String url) throws UsageException {
        int slash = url.indexOf('/');
        String hostPort = (slash == -1) ? url : url.substring(0, slash);
        String path = (slash == -1) ? "/" : url.substring(slash);

        int colon = hostPort.lastIndexOf(':');
        if (colon <= 0 || colon == hostPort.length() - 1) {
            throw new UsageException("bad URL '" + url + "' (expected host:port/path)");
        }
        String host = hostPort.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(hostPort.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new UsageException("bad port in '" + url + "'");
        }
        if (port < 1 || port > 65535) {
            throw new UsageException("port out of range in '" + url + "'");
        }
        // §3.1 and §7: the whole REQUEST payload must fit in 65,536 bytes.
        if (requestPayloadSize(hostPort, path) > MAX_REQUEST_PAYLOAD) {
            throw new UsageException("path too long in '" + url + "'");
        }
        return new Target(host, port, hostPort, path);
    }

    /** Size of the REQUEST payload that buildRequestPayload() will produce (§3.1, §6). */
    static int requestPayloadSize(String hostPort, String path) {
        int size = 1 + 2 + path.getBytes(StandardCharsets.UTF_8).length + 1; // method, path, count
        size += 3 + hostPort.getBytes(StandardCharsets.UTF_8).length;        // host header
        size += 3 + "bcurl/1.0".length();                                    // user-agent header
        size += 3 + "*/*".length();                                          // accept header
        return size;
    }

    // ------------------------------------------------------------------
    // Main loop: one connection, one request at a time (§1)
    // ------------------------------------------------------------------

    /** Opens the single connection and fetches every target in order. Returns the exit code. */
    static int run(List<Target> targets) {
        Target first = targets.get(0);
        try (Socket socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress(first.host(), first.port()));
            } catch (IOException e) {
                System.err.println("bcurl: cannot connect to " + first.hostPort() + ": " + e.getMessage());
                return EXIT_FATAL;
            }
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            return fetchAll(targets, in, out);
        } catch (IOException e) {
            System.err.println("bcurl: connection error: " + e.getMessage());
            return EXIT_FATAL;
        } catch (ProtocolException e) {
            System.err.println("bcurl: protocol error: " + e.getMessage());
            return EXIT_FATAL;
        }
    }

    /** Sends each request, waits for its response, prints the body. */
    static int fetchAll(List<Target> targets, DataInputStream in, DataOutputStream out)
            throws IOException, ProtocolException {
        int exitCode = EXIT_OK;
        int requestId = 1;
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            boolean isLast = (i == targets.size() - 1);

            // §4: set CLOSE only on the last request.
            int flags = isLast ? FLAG_CLOSE : 0;
            byte[] payload = buildRequestPayload(target);
            writeFrame(out, new Frame(TYPE_REQUEST, flags, requestId, payload));

            Frame frame = readResponseFrame(in);
            // §1: the response must carry the ID of the oldest outstanding request.
            if (frame.id() != requestId) {
                throw new ProtocolException("response has Request ID " + frame.id()
                        + ", expected " + requestId);
            }
            Response response = parseResponsePayload(frame.payload());
            printVerboseResponse(response);

            // The body goes to stdout as raw bytes.
            System.out.write(response.body(), 0, response.body().length);
            System.out.flush();

            if (response.status() < 200 || response.status() >= 400) {
                System.err.println("bcurl: server returned " + response.status());
                exitCode = EXIT_HTTP_ERROR;
            }

            // §4: after a CLOSE response we must not send more requests.
            boolean serverClosing = (frame.flags() & FLAG_CLOSE) != 0;
            if (serverClosing && !isLast) {
                int notSent = targets.size() - i - 1;
                System.err.println("bcurl: server closed the connection; "
                        + notSent + " request(s) not sent");
                return EXIT_FATAL;
            }
            requestId = nextRequestId(requestId);
        }
        return exitCode;
    }

    /** §2: IDs go 1, 2, ..., 65535, then wrap back to 1 (0 is reserved). */
    static int nextRequestId(int id) {
        return (id == 65535) ? 1 : id + 1;
    }

    // ------------------------------------------------------------------
    // Building and sending a REQUEST (§3.1, §6)
    // ------------------------------------------------------------------

    /** Builds the REQUEST payload: method, path, header count, headers. */
    static byte[] buildRequestPayload(Target target) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);

        data.writeByte(METHOD_GET);                                   // Method
        byte[] path = target.path().getBytes(StandardCharsets.UTF_8);
        data.writeShort(path.length);                                 // Path length
        data.write(path);                                             // Path

        data.writeByte(3);                                            // Header count
        writeStaticHeader(data, ID_HOST, target.hostPort());
        writeStaticHeader(data, ID_USER_AGENT, "bcurl/1.0");
        writeStaticHeader(data, ID_ACCEPT, "*/*");

        data.flush();
        return bytes.toByteArray();
    }

    /** §6: a header with a static Name ID: [id][value length (2)][value]. */
    static void writeStaticHeader(DataOutputStream data, int nameId, String value) throws IOException {
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        data.writeByte(nameId);
        data.writeShort(valueBytes.length);
        data.write(valueBytes);
    }

    /** §2: writes the 8-byte header followed by the payload. */
    static void writeFrame(DataOutputStream out, Frame frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(frame.payload().length);   // Length (4)
        data.writeByte(frame.type());            // Type (1)
        data.writeByte(frame.flags());           // Flags (1)
        data.writeShort(frame.id());             // Request ID (2)
        data.write(frame.payload());             // Payload
        data.flush();
        byte[] wire = bytes.toByteArray();

        if (verbose) {
            dumpFrame("> ", frame, wire, "");
        }
        out.write(wire);
        out.flush();
    }

    // ------------------------------------------------------------------
    // Reading frames (§2, §5)
    // ------------------------------------------------------------------

    /** Reads frames until a RESPONSE arrives; other types are skipped (§5). */
    static Frame readResponseFrame(DataInputStream in) throws IOException, ProtocolException {
        while (true) {
            Frame frame = readFrame(in);
            if (frame.type() == TYPE_RESPONSE) {
                return frame;
            }
            // §5: any other type (including REQUEST, §3) is unknown to the client: ignore it.
        }
    }

    /** Reads one whole frame: 8-byte header, then exactly Length payload bytes. */
    static Frame readFrame(DataInputStream in) throws IOException, ProtocolException {
        byte[] header = new byte[HEADER_SIZE];
        readHeaderBytes(in, header);

        ByteBuffer buf = ByteBuffer.wrap(header);       // ByteBuffer is big-endian by default
        long length = Integer.toUnsignedLong(buf.getInt());
        int type = Byte.toUnsignedInt(buf.get());
        int flags = Byte.toUnsignedInt(buf.get());
        int id = Short.toUnsignedInt(buf.getShort());

        // A Java array cannot hold more than about 2 GiB.
        if (length > Integer.MAX_VALUE - HEADER_SIZE) {
            if (type == TYPE_RESPONSE) {
                throw new ProtocolException("response of " + length + " bytes is too large for this client");
            }
            skipPayload(in, length);
            if (verbose) {
                System.err.printf("< unknown frame type=0x%02x length=%d id=%d: skipped (§5), too large to dump%n",
                        type, length, id);
            }
            return new Frame(type, flags, id, new byte[0]);
        }

        byte[] payload = new byte[(int) length];
        try {
            in.readFully(payload);
        } catch (EOFException e) {
            throw new ProtocolException("truncated frame: connection closed inside the payload");
        }
        Frame frame = new Frame(type, flags, id, payload);

        if (verbose) {
            String note = (type == TYPE_RESPONSE) ? "" : " -- unknown type, skipped (§5)";
            dumpFrame("< ", frame, concat(header, payload), note);
        }
        return frame;
    }

    /** Reads the 8 header bytes, telling apart "closed before a frame" and "closed mid-header". */
    static void readHeaderBytes(DataInputStream in, byte[] header) throws IOException, ProtocolException {
        int firstByte = in.read();
        if (firstByte == -1) {
            throw new ProtocolException("connection closed before a response arrived");
        }
        header[0] = (byte) firstByte;
        try {
            in.readFully(header, 1, HEADER_SIZE - 1);
        } catch (EOFException e) {
            throw new ProtocolException("truncated frame: connection closed inside the header");
        }
    }

    /** §5: discards exactly 'length' bytes without storing them. */
    static void skipPayload(InputStream in, long length) throws IOException, ProtocolException {
        try {
            in.skipNBytes(length);
        } catch (EOFException e) {
            throw new ProtocolException("truncated frame: connection closed inside the payload");
        }
    }

    // ------------------------------------------------------------------
    // Decoding a RESPONSE payload (§3.2, §6)
    // ------------------------------------------------------------------

    /** Status (2), header count (1), headers, then the rest is the body. */
    static Response parseResponsePayload(byte[] payload) throws ProtocolException {
        ByteBuffer buf = ByteBuffer.wrap(payload);

        need(buf, 3, "status and header count");
        int status = Short.toUnsignedInt(buf.getShort());
        int headerCount = Byte.toUnsignedInt(buf.get());

        List<Header> headers = new ArrayList<>();
        for (int i = 0; i < headerCount; i++) {
            Header header = readHeader(buf);
            if (header != null) {
                headers.add(header);
            }
        }

        // §3.2: whatever is left is the body.
        byte[] body = new byte[buf.remaining()];
        buf.get(body);
        checkContentLength(headers, body.length);
        return new Response(status, headers, body);
    }

    /** §6: decodes one header entry. Returns null for a reserved Name ID (11-255), which is ignored. */
    static Header readHeader(ByteBuffer buf) throws ProtocolException {
        need(buf, 1, "header Name ID");
        int nameId = Byte.toUnsignedInt(buf.get());

        String name;
        if (nameId == 0) {
            // Literal name: [length (1)][name bytes]
            need(buf, 1, "header Name length");
            int nameLength = Byte.toUnsignedInt(buf.get());
            if (nameLength == 0) {
                throw new ProtocolException("malformed response: literal header Name length is 0");
            }
            name = readString(buf, nameLength, "header Name");
        } else if (nameId < STATIC_NAMES.length) {
            name = STATIC_NAMES[nameId];
        } else {
            name = null;  // reserved ID: still read the value below, then ignore the entry
        }

        need(buf, 2, "header Value length");
        int valueLength = Short.toUnsignedInt(buf.getShort());
        String value = readString(buf, valueLength, "header Value");

        if (name == null) {
            if (verbose) {
                System.err.println("< (header with reserved Name ID " + nameId + " ignored, §6)");
            }
            return null;
        }
        return new Header(name, value);
    }

    /** Reads 'length' bytes from the buffer as UTF-8 text. */
    static String readString(ByteBuffer buf, int length, String what) throws ProtocolException {
        need(buf, length, what);
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Fails if fewer than 'count' bytes are left: the payload ended before a field was complete. */
    static void need(ByteBuffer buf, int count, String what) throws ProtocolException {
        if (buf.remaining() < count) {
            throw new ProtocolException("malformed response: payload ends inside " + what);
        }
    }

    /** §6: if content-length is present it must equal the body length. */
    static void checkContentLength(List<Header> headers, int bodyLength) throws ProtocolException {
        for (Header h : headers) {
            if (h.name().equals("content-length")) {
                if (!h.value().equals(Integer.toString(bodyLength))) {
                    throw new ProtocolException("content-length " + h.value()
                            + " does not match body length " + bodyLength);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Verbose output (-v), always on stderr
    // ------------------------------------------------------------------

    /** Prints the decoded status line and headers. */
    static void printVerboseResponse(Response response) {
        if (!verbose) {
            return;
        }
        System.err.println("< status " + response.status());
        for (Header h : response.headers()) {
            System.err.println("< " + h.name() + ": " + h.value());
        }
        System.err.println("< (body: " + response.body().length + " bytes)");
    }

    /** Prints a one-line summary of a frame, then a hexdump of all its bytes. */
    static void dumpFrame(String prefix, Frame frame, byte[] wire, String note) {
        System.err.printf("%s%s frame: length=%d type=0x%02x flags=0x%02x id=%d (%d bytes total)%s%n",
                prefix, typeName(frame.type()), frame.payload().length, frame.type(),
                frame.flags(), frame.id(), wire.length, note);
        hexdump(prefix, wire);
    }

    /** §3: human-readable name of a frame type. */
    static String typeName(int type) {
        if (type == TYPE_REQUEST) {
            return "REQUEST";
        }
        if (type == TYPE_RESPONSE) {
            return "RESPONSE";
        }
        return "UNKNOWN";
    }

    /** xxd-like dump: offset, 16 hex bytes, then the printable ASCII characters. */
    static void hexdump(String prefix, byte[] data) {
        for (int offset = 0; offset < data.length; offset += 16) {
            StringBuilder line = new StringBuilder(prefix);
            line.append(String.format("%08x  ", offset));

            // Hex column (padded so the ASCII column always lines up).
            for (int i = 0; i < 16; i++) {
                if (offset + i < data.length) {
                    line.append(String.format("%02x ", data[offset + i] & 0xFF));
                } else {
                    line.append("   ");
                }
            }

            // ASCII column: printable characters as-is, everything else as '.'.
            line.append(' ');
            for (int i = 0; i < 16 && offset + i < data.length; i++) {
                int b = data[offset + i] & 0xFF;
                line.append((b >= 0x20 && b <= 0x7E) ? (char) b : '.');
            }
            System.err.println(line);
        }
    }

    /** Joins two byte arrays (used to dump header + payload together). */
    static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
