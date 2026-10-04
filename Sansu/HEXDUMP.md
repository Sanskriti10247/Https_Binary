# Annotated hexdump — one full exchange (Java side)

real bytes, captured with:

```bash
./observe ./www 9001 &
./bcurl -v localhost:9001/hello.txt
```

(different file than the python folder's dump on purpose — proves the same
framing works for any path/body. dates change per run, layout doesn't.)

## frame 1: REQUEST, client → server (70 bytes)

```
0000  00 00 3e 01 01 00 00 00 01 00 0a 2f 68 65 6c 6c   |..>......../hell|
0010  6f 2e 74 78 74 04 05 00 09 6c 6f 63 61 6c 68 6f   |o.txt....localho|
0020  73 74 06 00 0e 62 63 75 72 6c 2d 6a 61 76 61 2f   |st...bcurl-java/|
0030  31 2e 30 08 00 0a 6b 65 65 70 2d 61 6c 69 76 65   |1.0...keep-alive|
0040  07 00 03 2a 2f 2a                                 |...*/*|
```

- `00 00 3e` — LENGTH 62. `01` — REQUEST. `01` — END_STREAM.
  `00 00 00` — reserved, zero.
- payload: `01` = GET; `00 0a` = path len 10; `/hello.txt`;
  `04` headers: `05`=host "localhost", `06`=user-agent "bcurl-java/1.0"
  (14 chars = 0x0e), `08`=connection "keep-alive", `07`=accept "*/*".
- math: 1+2+10+1 = 14, headers 12+17+13+6 = 48, total 62 = 0x3e ✓

## frame 2: RESPONSE, server → client (109 bytes)

```
0000  00 00 65 02 00 00 00 00 00 c8 05 01 00 0a 74 65   |..e...........te|
0010  78 74 2f 70 6c 61 69 6e 02 00 03 31 32 34 03 00   |xt/plain...124..|
0020  0c 42 48 54 54 50 2d 31 2f 6a 61 76 61 04 00 1d   |.BHTTP-1/java...|
0030  53 75 6e 2c 20 30 34 20 4f 63 74 20 32 30 32 36   |Sun, 04 Oct 2026|
0040  20 30 35 3a 31 36 3a 33 31 20 47 4d 54 0a 00 1d   | 05:16:31 GMT...|
0050  53 75 6e 2c 20 30 34 20 4f 63 74 20 32 30 32 36   |Sun, 04 Oct 2026|
0060  20 30 35 3a 31 35 3a 32 35 20 47 4d 54            | 05:15:25 GMT|
```

- `00 00 65` — LENGTH 101. `02` — RESPONSE. `00` — flags: no END yet,
  DATA follows.
- `00 c8` = status 200, `05` headers: content-type "text/plain" (10 chars),
  content-length "124", server "BHTTP-1/java" (12 chars = 0x0c),
  date + last-modified (29 chars = 0x1d each).
- math: 2+1 + 13+6+15+32+32 = 101 = 0x65 ✓

## frame 3: DATA, server → client (132 bytes)

```
0000  00 00 7c 03 01 00 00 00 68 65 6c 6c 6f 20 66 72   |..|.....hello fr|
0010  6f 6d 20 74 68 65 20 6a 61 76 61 20 73 65 72 76   |om the java serv|
... 124 body bytes total, exactly www/hello.txt ...
0070  2c 20 69 6e 74 65 72 6f 70 20 70 61 73 73 65 64   |, interop passed|
0080  20 3a 29 0a                                       | :).|
```

- `00 00 7c` — LENGTH 124 = content-length from frame 2 ✓
- `03` — DATA. `01` — END_STREAM, exchange done, socket stays open.
- the 124 bytes went straight to stdout (binary-safe, no decoding).

## extra checks done on this build

1. `bcurl localhost:9001/nope` → body `not found: /nope`, exit 1.
2. unknown method byte → 400 `bad request`, connection still usable.
3. `0xFF` frame sent before a GET → logged `[skip]`, GET still answered 200.
4. python bcurl read this server fine and java bcurl read the python server
   fine — the only shared artefact was spec.md, so interop is genuine.
5. a fake server injecting `0xFF` between RESPONSE and DATA: this client
   skipped it and still printed the body, exit 0.
