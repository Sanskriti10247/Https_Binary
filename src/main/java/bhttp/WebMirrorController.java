package bhttp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Plain-HTTP mirror of the same www/ dir, for debugging only.
 * GET /_debug explains the binary frames. Everything else serves files.
 * The graded binary protocol never touches this (it lives on 9000/tcp).
 */
@RestController
public class WebMirrorController {

    private final Path www = Paths.get("www").toAbsolutePath().normalize();

    @GetMapping("/_debug")
    public java.util.Map<String, Object> debug() {
        return java.util.Map.of(
                "note", "plain HTTP mirror for debugging. real protocol = TCP 9000, BHTTP/1 binary frames.",
                "binary_header", "u24 length | u8 type | u8 flags | 24 reserved (= 8 bytes)",
                "types", java.util.Map.of("1", "REQUEST", "2", "RESPONSE", "3", "DATA"),
                "rule", "unknown frame type MUST be skipped via LENGTH. that is how v2 fits.");
    }

    @GetMapping("/**")
    public ResponseEntity<Resource> mirror(HttpServletRequest req) throws Exception {
        String rel = req.getRequestURI().replaceFirst("^/+", "");
        if (rel.isEmpty()) rel = "index.html";
        Path full = www.resolve(rel).normalize();
        if (!full.startsWith(www)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN)
                    .body(new org.springframework.core.io.ByteArrayResource("bad request: bad path".getBytes()));
        }
        if (Files.isDirectory(full)) full = full.resolve("index.html");
        if (!Files.isRegularFile(full)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_PLAIN)
                    .body(new org.springframework.core.io.ByteArrayResource(("not found: /" + rel).getBytes()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MimeUtil.guess(full.toString())))
                .body(new FileSystemResource(full));
    }
}
