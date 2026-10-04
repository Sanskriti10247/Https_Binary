# BHTTP/1 — binary HTTP spec (v1)

*A two-page spec. If a stranger can't implement from this, it's not done.*

## 1. What it is

BHTTP/1 carries HTTP semantics (GET a path → status + headers + bytes)
in **binary frames over one long-lived TCP connection** (default port 9000).
No text, no `\r\n`. One exchange at a time (stop-and-wait, like HTTP/1.1
keep-alive, not multiplexed like HTTP/2). The client sends one REQUEST;
the server answers with one RESPONSE plus zero or more DATA frames.
Either side may then start the next exchange on the **same** connection.

Byte order is big-endian everywhere. Strings are UTF-8.

## 2. The frame header — 8 bytes, and why

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                PAYLOAD LENGTH (24)            |  TYPE (8)       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| FLAGS (8)     |             RESERVED (24, zero)                 |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| ... payload (LENGTH bytes) ...                                  |
```

| field | width | meaning |
|---|---|---|
| LENGTH | 24-bit uint | bytes in payload after the header (0…16,777,215). 0 is legal. |
| TYPE | 8-bit | `0x01` REQUEST, `0x02` RESPONSE, `0x03` DATA. **Anything else is reserved.** |
| FLAGS | 8-bit | only bit 0 defined: `0x01 END_STREAM` = "exchange over, nothing more follows". Bits 1–7 MUST be 0 on send, ignored on receive. |
| RESERVED | 24-bit | MUST be 0 on send, MUST be ignored on receive. This is v2's room. |

**Why these widths (and what HTTP/2's 24/8/8/31 taught us).**
HTTP/2 uses 9 bytes: 24 length + 8 type + 8 flags + 31 stream-id. We kept
the first three and dropped the last — deliberately:

* **24 length:** 16 MB max per frame. Our files are KBs; 24 bits bounds
  memory (a receiver can refuse huge LENGTH up front) while staying 1 byte
  smaller than a u32. 16 bits (64 KB) would force chunking of ordinary
  pages; 32 bits invites 4 GB alloc attacks. HTTP/2 made the same call.
* **8 type:** we need 3 types; 256 slots leave 253 for v2. 4 bits would
  already feel cramped.
* **8 flags:** we need 1 bit today (END_STREAM); 7 spare bits ride free.
* **No stream-id (the 31):** that field exists *only* for multiplexing
  many in-flight streams. We run one exchange at a time, so an id would
  always be `1` — pure overhead. We replace it with 24 reserved zero bits,
  so v2 can add streams/versions **without changing the header size**.
  Net: 8-byte header instead of 9 (11% smaller on every frame).

**The line you may not skip:** *a receiver meeting a frame type it does
not know MUST skip it cleanly* — read exactly LENGTH bytes, discard them,
keep parsing the next frame. That is the whole v2 story: old code ignores
new frames and stays in sync because LENGTH always tells it where the next
header starts.

## 3. Payloads

**REQUEST** (`0x01`, normally `END_STREAM=1` — GET carries no body):

```
METHOD u8 (0x01 GET, 0x02 HEAD; anything else → server replies 400)
PATH_LEN u16 + PATH bytes (MUST start with '/', MUST NOT contain NUL)
NHEADERS u8, then per header:
  NAME_CODE u8: 0x00 = literal, 0x01–0x0A = indexed (table below),
                anything else → 400
  if indexed:  VALUE_LEN u16 + VALUE bytes
  if literal:  NAME_LEN u8 + NAME bytes + VALUE_LEN u16 + VALUE bytes
no trailing bytes allowed (else 400)
```

**RESPONSE** (`0x02`; `END_STREAM=1` if no body follows, else `0`):

```
STATUS u16 (200, 404, 400 minimum; server MAY send others)
NHEADERS u8 + headers, encoded exactly like requests
body is NEVER inline — it comes next, in DATA frames
```

**DATA** (`0x03`): raw body bytes; LENGTH *is* the chunk size.
Concatenating all DATA payloads of the exchange = the body.
Last DATA has `END_STREAM=1`. Zero-length DATA is legal. HEAD sends none.

## 4. Headers — number ten, length-prefix the rest

Borrowed from HPACK's first two tricks (index + literal), without the
table-size/Huffman machinery that takes weeks:

| code | name | code | name |
|---|---|---|---|
| `0x01` | content-type | `0x06` | user-agent |
| `0x02` | content-length | `0x07` | accept |
| `0x03` | server | `0x08` | connection |
| `0x04` | date | `0x09` | content-encoding |
| `0x05` | host | `0x0A` | last-modified |
| `0x00` | *literal* (followed by `NAME_LEN u8 + NAME + VALUE_LEN u16 + VALUE*) | | |

These ten are the only names our client/server actually emit, so the
common path is 2–3 bytes of overhead per header instead of the full name.
Anything else goes literal with explicit lengths — no NUL-termination,
no ambiguity. Receivers MUST accept literal names case-insensitively
(we lowercase on send).

## 5. Server and client behaviour

**Server** (`./observe ./www 9000`): bind, accept, then per connection loop
`read header → read LENGTH bytes → dispatch`. Map `PATH` under root:
strip `?query`, `/` → `/index.html`, directories → `index.html` inside,
reject escapes (`..` leaving root, absolute tricks) with **400**,
missing files with **404** (small `text/plain` body), malformed frames
(bad lengths, truncation, bad method/code, trailing garbage) with **400** —
*and keep the connection open in every case* so the next request can reuse
it. Success → **200** + `content-type` (by extension), `content-length`,
`server`, `date`, `last-modified`, then the file bytes in ≤16 KB DATA
chunks. Unknown incoming types are skipped, never fatal.

**Client** (`./bcurl -v localhost:9000/index.html`): open **exactly one**
TCP connection, send one REQUEST (`host`, `user-agent`, `connection`,
`accept` headers), then read until `END_STREAM`, skipping unknown types.
Body bytes go to **stdout** (binary-safe); with `-v` every frame is
hexdumped to stderr with its meaning. Exit `0` on 2xx/3xx, `1` on 4xx/5xx,
`2` on network/protocol failure.

## 6. Interop proof

If both of us built from this page, my `bcurl` works against your
`observe` and vice versa — a client that only talks to its own server is
an implementation, not a protocol. The annotated hexdump (`HEXDUMP.md`)
shows one real REQUEST + RESPONSE + DATA with every byte accounted for.
Unknown-type tolerance is tested by injecting a `0xFF` frame mid-exchange:
both sides must still deliver the body.
