package bhttp;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BHTTP/1 server (Java) — track 1.
 * run: ./observe ./www 9000
 *
 * plain java.net.ServerSocket + a thread pool. spring boot (BhttpApplication)
 * is the declared stack so it serves the debug mirror, but spring doesn't do
 * raw tcp — this class is the actual protocol. same bytes on both sides,
 * the only thing shared was spec.md.
 */
public class BhttpServer {

    static final String SERVER_NAME = "BHTTP-1/java";
    static final int CHUNK = 16 * 1024;

    static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneId.of("GMT"));

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: observe <www-root> <port>");
            System.exit(2);
        }
        Path root = Paths.get(args[0]).toRealPath();
        int port = Integer.parseInt(args[1]);
        if (!Files.isDirectory(root)) {
            System.err.println("no such dir: " + args[0]);
            System.exit(2);
        }
        serve(root, port);
    }

    /** also called from the spring bean (BhttpService) when run via maven. */
    public static void serve(Path root, int port) throws IOException {
        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket ss = new ServerSocket(port)) {
            System.err.println("serving " + root + " on :" + port + " (BHTTP/1, keep-alive) — ctrl-c to stop");
            while (true) {
                Socket s = ss.accept();
                pool.submit(() -> handle(s, root));
            }
        }
    }

    static void handle(Socket s, Path root) {
        String who = s.getRemoteSocketAddress().toString();
        System.err.println("[+] " + who + " connected");
        try (Socket sock = s) {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            while (true) {
                byte[] hdr;
                try {
                    hdr = new byte[8];
                    in.readFully(hdr);
                } catch (EOFException e) {
                    break; // client closed, normal for keep-alive
                }
                int len = ((hdr[0] & 0xFF) << 16) | ((hdr[1] & 0xFF) << 8) | (hdr[2] & 0xFF);
                int type = hdr[3] & 0xFF;
                int flags = hdr[4] & 0xFF;
                // hdr[5..7] reserved — ignore (v2's room)
                byte[] payload = new byte[len];
                if (len > 0) in.readFully(payload);
                oneRequest(out, root, type, flags, payload);
            }
        } catch (Exception e) {
            System.err.println("[!] conn " + who + " error: " + e);
        }
        System.err.println("[-] " + who + " closed");
    }

    static void oneRequest(OutputStream out, Path root, int type, int flags, byte[] p) throws IOException {
        // THE rule: unknown frame type MUST be skipped cleanly.
        // we already consumed LENGTH bytes, so just ignore it.
        if (type != BhttpFrames.T_REQUEST) {
            System.err.printf("  [skip] unknown/ejected frame type 0x%02x, ignored%n", type);
            return;
        }
        String path;
        boolean isHead;
        try {
            if (p.length < 4) throw new BhttpFrames.Malformed("request too short");
            int method = p[0] & 0xFF;
            if (method == 0x01) isHead = false;
            else if (method == 0x02) isHead = true;
            else throw new BhttpFrames.Malformed(String.format("unknown method 0x%02x", method));
            int pl = ((p[1] & 0xFF) << 8) | (p[2] & 0xFF);
            if (3 + pl + 1 > p.length) throw new BhttpFrames.Malformed("path overruns frame");
            path = new String(p, 3, pl, StandardCharsets.UTF_8);
            int n = p[3 + pl] & 0xFF;
            BhttpFrames.Decoded d = BhttpFrames.decodeHeaders(p, 3 + pl + 1, n);
            if (d.nextPos() != p.length) throw new BhttpFrames.Malformed("trailing garbage after headers");
            if (!path.startsWith("/") || path.indexOf('\0') >= 0)
                throw new BhttpFrames.Malformed("bad path");
            System.err.println("  " + (isHead ? "HEAD" : "GET") + " " + path);
            serveFile(out, root, path, isHead);
        } catch (BhttpFrames.Malformed e) {
            System.err.println("  [400] malformed: " + e.getMessage());
            byte[] body = "bad request".getBytes(StandardCharsets.UTF_8);
            send(out, 400, List.of(
                    new BhttpFrames.Header("content-type", "text/plain"),
                    new BhttpFrames.Header("content-length", String.valueOf(body.length)),
                    new BhttpFrames.Header("server", SERVER_NAME),
                    new BhttpFrames.Header("date", HTTP_DATE.format(Instant.now())),
                    new BhttpFrames.Header("connection", "keep-alive")), body, false);
        }
    }

    static Path map(Path root, String urlPath) {
        String p = urlPath.split("\\?", 2)[0].split("#", 2)[0];
        if (!p.startsWith("/")) return null;
        String rel = p.replaceFirst("^/+", "");
        if (rel.isEmpty()) rel = "index.html";
        Path full = root.resolve(rel).normalize();
        if (!full.startsWith(root)) return null; // escape attempt -> caller sends 400
        if (Files.isDirectory(full)) full = full.resolve("index.html");
        return full;
    }

    static void serveFile(OutputStream out, Path root, String path, boolean isHead) throws IOException {
        Path full = map(root, path);
        if (full == null) {
            byte[] body = "bad request: bad path".getBytes(StandardCharsets.UTF_8);
            send(out, 400, baseHeaders("text/plain", body.length), body, false);
            return;
        }
        if (!Files.isRegularFile(full)) {
            byte[] body = ("not found: " + path).getBytes(StandardCharsets.UTF_8);
            send(out, 404, baseHeaders("text/plain", body.length), body, false);
            return;
        }
        byte[] data = Files.readAllBytes(full);
        List<BhttpFrames.Header> h = new ArrayList<>(baseHeaders(MimeUtil.guess(full.toString()), data.length));
        h.add(new BhttpFrames.Header("last-modified",
                HTTP_DATE.format(Files.getLastModifiedTime(full).toInstant())));
        send(out, 200, h, data, isHead);
    }

    static List<BhttpFrames.Header> baseHeaders(String ctype, int len) {
        return List.of(
                new BhttpFrames.Header("content-type", ctype),
                new BhttpFrames.Header("content-length", String.valueOf(len)),
                new BhttpFrames.Header("server", SERVER_NAME),
                new BhttpFrames.Header("date", HTTP_DATE.format(Instant.now())));
    }

    static void send(OutputStream out, int status, List<BhttpFrames.Header> headers,
                     byte[] body, boolean isHead) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write((status >> 8) & 0xFF);
        b.write(status & 0xFF);
        b.write(BhttpFrames.encodeHeaders(headers));
        byte[] respPayload = b.toByteArray();
        if (body.length > 0 && !isHead) {
            out.write(BhttpFrames.pack(BhttpFrames.T_RESPONSE, 0x00, respPayload));
            for (int i = 0; i < body.length; i += CHUNK) {
                int j = Math.min(i + CHUNK, body.length);
                boolean last = (j == body.length);
                byte[] piece = java.util.Arrays.copyOfRange(body, i, j);
                out.write(BhttpFrames.pack(BhttpFrames.T_DATA,
                        last ? BhttpFrames.FLAG_END : 0x00, piece));
            }
        } else {
            out.write(BhttpFrames.pack(BhttpFrames.T_RESPONSE, BhttpFrames.FLAG_END, respPayload));
        }
        out.flush();
    }
}
