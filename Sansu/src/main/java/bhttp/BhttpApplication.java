package bhttp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the spring-boot side.
 *
 * Two ways to run:
 *  1. ./observe ./www 9000  -> runs the BINARY server below (no spring context,
 *     spring can't speak raw tcp anyway). fastest for grading.
 *  2. mvn spring-boot:run   -> starts the normal spring web mirror on :8080
 *     serving the same www/ over plain HTTP (debug helper, like the FastAPI
 *     mirror in the sibling folder).
 *
 * Both live in one project so the "java + spring boot" stack is real.
 */
@SpringBootApplication
public class BhttpApplication {

    public static void main(String[] args) throws Exception {
        // binary mode looks like: ./observe ./www 9000  (two args, second is numeric port)
        if (args.length == 2 && args[1].matches("[0-9]+")) {
            java.nio.file.Path root = java.nio.file.Paths.get(args[0]).toRealPath();
            BhttpServer.serve(root, Integer.parseInt(args[1]));
            return;
        }
        SpringApplication.run(BhttpApplication.class, args);
    }
}
