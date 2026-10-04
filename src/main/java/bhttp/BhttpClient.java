package bhttp;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * bcurl (Java) — track 2.
 * run: ./bcurl [-v] localhost:9000/index.html
 *
 * one tcp connection, one request, body to stdout.
 * -v hexdumps every frame to stderr. exit 0 on 2xx/3xx, 1 on 4xx/5xx, 2 if broken.
 */
public class BhttpClient {

    public static void main(String[] args) throws Exception {
        boolean verbose = false;
        List<String> rest = new ArrayList<>();
        for (String a : args) {
            if (a.equals("-v")) verbose = true;
            else rest.add(a);
        }
        if (rest.size() != 1) {
            System.err.println("usage: bcurl [-v] host:port/path");
            System.exit(2);
        }
        Target t = parse(rest.get(0));

        byte[] req = buildRequest(t.path, t.host);
        try (Socket s = new Socket(t.host, t.port)) {
            s.setSoTimeout(5000); // don't hang forever if server dies mid-frame
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());

            if (verbose) {
                System.err.println(">> REQUEST " + t.path + " (" + req.length + " bytes) -> "
                        + t.host + ":" + t.port);
                dump(req);
            }
            out.write(req);
            out.flush();
            // and that's the only connection we ever open. everything below is this socket.

            Integer status = null;
            List<BhttpFrames.Header> rheaders = new ArrayList<>();
            ByteArrayOutputStream body = new ByteArrayOutputStream();

            while (true) {
                byte[] hdr = new byte[8];
                try {
                    in.readFully(hdr);
                } catch (EOFException e) {
                    System.err.println("bcurl: server hung up mid-frame");
                    System.exit(2);
                }
                int len = ((hdr[0] & 0xFF) << 16) | ((hdr[1] & 0xFF) << 8) | (hdr[2] & 0xFF);
                int type = hdr[3] & 0xFF;
                int flags = hdr[4] & 0xFF;
                byte[] payload = new byte[len];
                if (len > 0) in.readFully(payload);

                if (verbose) {
                    System.err.printf("<< %s len=%d flags=0x%02x %s%n",
                            BhttpFrames.typeName(type), len, flags,
                            ((flags & 1) != 0 ? "END" : ""));
                    byte[] raw = new byte[8 + len];
                    System.arraycopy(hdr, 0, raw, 0, 8);
                    System.arraycopy(payload, 0, raw, 8, len);
                    dump(raw);
                }

                if (type != BhttpFrames.T_RESPONSE && type != BhttpFrames.T_DATA) {
                    // unknown type -> skip cleanly, stay in sync thanks to LENGTH
                    System.err.printf("   (unknown frame type 0x%02x, skipped %d bytes)%n", type, len);
                    continue;
                }
                if (type == BhttpFrames.T_RESPONSE) {
                    if (payload.length < 3) {
                        System.err.println("bcurl: malformed RESPONSE");
                        System.exit(2);
                    }
                    status = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                    int n = payload[2] & 0xFF;
                    try {
                        BhttpFrames.Decoded d = BhttpFrames.decodeHeaders(payload, 3, n);
                        rheaders = d.headers();
                    } catch (BhttpFrames.Malformed e) {
                        System.err.println("bcurl: malformed headers: " + e.getMessage());
                        System.exit(2);
                    }
                    if (verbose) System.err.println("   status=" + status + " headers=" + rheaders);
                    if ((flags & 1) != 0) break;
                } else {
                    body.write(payload);
                    if ((flags & 1) != 0) break;
                }
            }

            if (status == null) {
                System.err.println("bcurl: no response (only unknown frames?)");
                System.exit(2);
            }
            if (verbose) System.err.println("-- status " + status + ", " + body.size() + " body bytes --");
            System.out.write(body.toByteArray());
            System.out.flush();
            if (status >= 400 && status <= 599) System.exit(1);
            System.exit(0);
        } catch (java.net.ConnectException e) {
            System.err.println("bcurl: connect failed: " + e.getMessage());
            System.exit(2);
        }
    }

    record Target(String host, int port, String path) {}

    static Target parse(String arg) {
        String t = arg;
        if (t.contains("://")) t = t.substring(t.indexOf("://") + 3);
        String hostport, path;
        int slash = t.indexOf('/');
        if (slash >= 0) {
            hostport = t.substring(0, slash);
            path = t.substring(slash);
            if (path.isEmpty()) path = "/";
        } else {
            hostport = t;
            path = "/";
        }
        String host;
        int port = 9000;
        if (hostport.contains(":")) {
            host = hostport.substring(0, hostport.indexOf(':'));
            port = Integer.parseInt(hostport.substring(hostport.indexOf(':') + 1));
        } else {
            host = hostport;
        }
        if (host.isEmpty()) host = "localhost";
        return new Target(host, port, path);
    }

    static byte[] buildRequest(String path, String host) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(0x01); // GET
            byte[] pb = path.getBytes(StandardCharsets.UTF_8);
            b.write((pb.length >> 8) & 0xFF);
            b.write(pb.length & 0xFF);
            b.write(pb);
            List<BhttpFrames.Header> hs = List.of(
                    new BhttpFrames.Header("host", host),
                    new BhttpFrames.Header("user-agent", "bcurl-java/1.0"),
                    new BhttpFrames.Header("connection", "keep-alive"),
                    new BhttpFrames.Header("accept", "*/*"));
            b.write(BhttpFrames.encodeHeaders(hs));
            return BhttpFrames.pack(BhttpFrames.T_REQUEST, BhttpFrames.FLAG_END, b.toByteArray());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void dump(byte[] data) {
        for (int i = 0; i < data.length; i += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder asc = new StringBuilder();
            for (int j = 0; j < 16; j++) {
                if (i + j < data.length) {
                    int v = data[i + j] & 0xFF;
                    hex.append(String.format("%02x ", v));
                    asc.append(v >= 32 && v < 127 ? (char) v : '.');
                } else {
                    hex.append("   ");
                }
            }
            System.err.printf("  %04x  %-48s  |%s|%n", i, hex.toString().stripTrailing(), asc);
        }
    }
}
