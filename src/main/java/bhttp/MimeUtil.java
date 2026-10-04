package bhttp;

/** tiny mime map, same behaviour as python's mimetypes guess. */
public final class MimeUtil {
    private MimeUtil() {}

    public static String guess(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".txt")) return "text/plain";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }
}
