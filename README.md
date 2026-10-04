# BHTTP/1 in Java + Spring Boot

this folder is the java half. `spec.md` is byte-for-byte the same file as in
the sibling folder — only the spec crossed between the two implementations.

## layout

- `spec.md` — the protocol (shared, identical)
- `src/main/java/bhttp/` — `BhttpFrames.java` (codec), `BhttpServer.java`
  (track 1), `BhttpClient.java` (track 2), `MimeUtil.java`,
  `BhttpApplication.java` + `WebMirrorController.java` (spring boot mirror)
- `observe` / `bcurl` — launchers (`./observe ./www 9000`,
  `./bcurl -v localhost:9000/index.html`)
- `pom.xml` — spring boot project (debug mirror on :8080 via `mvn spring-boot:run`)
- `www/` — test files, deliberately different content from the other folder
- `HEXDUMP.md` — one real request+response, every byte annotated

## 1. build (no maven needed for grading)

```bash
chmod +x observe bcurl
./observe ./www 9000 &          # compiles with javac, serves on 9000
./bcurl -v localhost:9000/index.html
```

the binary server/client only use the JDK. maven + spring are used only for
the optional debug mirror:

```bash
mvn spring-boot:run            # plain HTTP mirror on :8080, same www/
```

## 2. try it

```bash
./bcurl localhost:9000/hello.txt; echo "exit=$?"      # 0, body on stdout
./bcurl localhost:9000/nothing-here; echo "exit=$?"   # 1, 404 body on stdout
./bcurl -v localhost:9000/ 2>&1 | head -40             # see every frame
```

keep-alive: run two requests over one connection — the server answers both
and only closes when you do (there is a small python check for this in the
sibling folder's README; same bytes, so it works here too).

## notes

- thread pool per connection, 16 KB DATA chunks, same as the python side.
- unknown frame types are skipped via LENGTH (log line `[skip]`), never fatal.
- method must be GET (`0x01`) / HEAD (`0x02`), path must start with `/`,
  `..` escapes give 400, missing files 404. connection always stays open.
- header names go out lowercased; indexed codes 0x01–0x0A, rest literal.
