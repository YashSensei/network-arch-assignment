#!/bin/bash
# Integration tests for bserve + bcurl.
# Usage: tests/run_tests.sh   (needs java, nc, xxd)
#
# Starts bserve on a temporary document root, then checks:
#   - normal requests through bcurl
#   - error cases (400) with hand-built raw frames, since bcurl never sends bad frames
#   - unknown frame types being skipped by both the server and the client
#   - several requests on one connection
#   - path traversal

set -u
cd "$(dirname "$0")/.."

PORT=9090        # bserve under test
FAKE_PORT=9091   # fake server for the client-side skip test
TMP=$(mktemp -d)
PASS=0
FAIL=0

# ---- test document root ------------------------------------------------------
# $TMP/secret.txt sits OUTSIDE the root and must never be served.
mkdir -p "$TMP/root/sub"
cp www/index.html www/hello.txt "$TMP/root/"
echo "sub page" > "$TMP/root/sub/index.html"
echo "TOP SECRET" > "$TMP/secret.txt"
ln -s ../secret.txt "$TMP/root/escape"   # symlink inside root pointing outside it

./bserve "$TMP/root" $PORT 2> "$TMP/serve.log" &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null; rm -rf "$TMP"' EXIT
for i in $(seq 1 50); do nc -z localhost $PORT 2>/dev/null && break; sleep 0.2; done

# ---- helpers -------------------------------------------------------------------

check() {   # check "<name>" <condition result: 0 = pass>
    if [ "$2" -eq 0 ]; then echo "PASS  $1"; PASS=$((PASS+1))
    else                    echo "FAIL  $1"; FAIL=$((FAIL+1)); fi
}

hexstr() { printf '%s' "$1" | xxd -p | tr -d '\n'; }   # text -> hex

# frame <type> <flags> <id> <payload-hex>  -> one frame as hex (SPEC §2)
frame() { printf '%08x%02x%02x%04x%s' $(( ${#4} / 2 )) "$1" "$2" "$3" "$4"; }

# get_payload <path> -> REQUEST payload as hex: GET, path, 0 headers (SPEC §3.1)
get_payload() { local p; p=$(hexstr "$1"); printf '01%04x%s00' $(( ${#p} / 2 )) "$p"; }

# send <hex>: send raw bytes to bserve, return everything it sends back as hex.
# The last frame we send always sets CLOSE (or is oversized), so the server closes and nc exits.
send() { printf '%s' "$1" | xxd -r -p | nc localhost $PORT | xxd -p | tr -d '\n'; }

# frames <hex>: list the received frames as "type:status ..." (e.g. "02:200 02:404")
frames() {
    local hex=$1 pos=0 out="" len
    while [ $pos -lt ${#hex} ]; do
        len=$((16#${hex:pos:8}))
        out+="${hex:pos+8:2}:$((16#${hex:pos+16:4})) "
        pos=$((pos + (8 + len) * 2))
    done
    echo "${out% }"
}

# bc <args...>: run bcurl -v, saving stdout/stderr; returns bcurl's exit code
bc() { ./bcurl -v "$@" > "$TMP/out" 2> "$TMP/err"; }

# ---- 1. normal requests --------------------------------------------------------
echo "== normal requests"

bc localhost:$PORT/index.html; rc=$?
cmp -s "$TMP/out" www/index.html && [ $rc -eq 0 ]
check "GET /index.html -> body matches file, exit 0" $?

bc localhost:$PORT/; rc=$?
cmp -s "$TMP/out" www/index.html && [ $rc -eq 0 ]
check "GET / -> serves index.html" $?

bc localhost:$PORT/sub/; rc=$?
[ "$(cat "$TMP/out")" = "sub page" ] && [ $rc -eq 0 ]
check "GET /sub/ -> serves sub/index.html" $?

# ---- 2. 404 ---------------------------------------------------------------------
echo "== 404"

bc localhost:$PORT/nope.html; rc=$?
grep -q "< status 404" "$TMP/err" && [ $rc -ne 0 ]
check "GET /nope.html -> 404, exit non-zero ($rc)" $?

# ---- 3. 400 malformed frames (raw) -----------------------------------------------
echo "== 400 malformed"

r=$(frames "$(send "$(frame 1 1 1 "09000b$(hexstr /index.html)00")")")
[ "$r" = "02:400" ]; check "unknown method 0x09 -> 400   [$r]" $?

r=$(frames "$(send "$(frame 1 1 1 "0100ff$(hexstr /x)")")")
[ "$r" = "02:400" ]; check "path length 255 but only 2 bytes -> 400   [$r]" $?

r=$(frames "$(send "$(frame 1 1 1 "$(get_payload /index.html)ffff")")")
[ "$r" = "02:400" ]; check "extra bytes after headers -> 400   [$r]" $?

r=$(frames "$(send "$(frame 1 1 1 "$(get_payload index.html)")")")
[ "$r" = "02:400" ]; check "path without leading / -> 400   [$r]" $?

r=$(frames "$(send "$(frame 1 1 0 "$(get_payload /index.html)")")")
[ "$r" = "02:400" ]; check "request ID 0 -> 400   [$r]" $?

r=$(frames "$(send "$(frame 1 0 1 "$(get_payload /index.html)0000")$(frame 1 1 2 "$(get_payload /hello.txt)")")")
[ "$r" = "02:400 02:200" ]; check "connection stays open after 400   [$r]" $?

r=$(frames "$(send "0010000001000001")")   # Length = 1 MiB, no payload sent
[ "$r" = "02:400" ]; check "oversized frame -> 400 + close   [$r]" $?

# ---- 4. unknown frame types are skipped -----------------------------------------
echo "== unknown frame types"

UNKNOWN="$(frame 0x7f 0 0 deadbeef)$(frame 0 0 0 "")$(frame 2 0 9 00c800)"
r=$(frames "$(send "${UNKNOWN}$(frame 1 1 1 "$(get_payload /hello.txt)")")")
[ "$r" = "02:200" ]; check "server skips types 0x7F, 0x00, 0x02, then answers   [$r]" $?

# Client side: a fake server sends an unknown frame before the real response.
FAKE="$(frame 0x7f 0 0 cafebabe)$(frame 2 1 1 "00c800$(hexstr hi)")"
printf '%s' "$FAKE" | xxd -r -p | nc -l $FAKE_PORT > /dev/null &
FAKE_PID=$!
sleep 0.5
./bcurl -v localhost:$FAKE_PORT/x > "$TMP/out" 2> "$TMP/err"; rc=$?
kill $FAKE_PID 2>/dev/null
[ "$(cat "$TMP/out")" = "hi" ] && [ $rc -eq 0 ] && grep -q "skipped" "$TMP/err"
check "client skips unknown frame type 0x7F, reads response" $?

# ---- 5. several requests on one connection --------------------------------------
echo "== multiple requests, one connection"

before=$(wc -l < "$TMP/serve.log")
bc localhost:$PORT/index.html localhost:$PORT/hello.txt localhost:$PORT/index.html; rc=$?
new_log=$(tail -n +$((before + 1)) "$TMP/serve.log")
conns=$(echo "$new_log" | grep -o 'conn#[0-9]* id=[0-9]* GET' | cut -d' ' -f1 | sort -u | wc -l)
reqs=$(echo "$new_log" | grep -c ' GET ')
cat www/index.html www/hello.txt www/index.html | cmp -s - "$TMP/out" && [ $rc -eq 0 ] \
    && [ "$conns" -eq 1 ] && [ "$reqs" -eq 3 ]
check "3 requests -> 3 responses on 1 connection" $?

bc localhost:$PORT/hello.txt localhost:$PORT/nope localhost:$PORT/hello.txt; rc=$?
[ "$(grep -c '< status' "$TMP/err")" -eq 3 ] && [ $rc -eq 1 ]
check "404 in the middle doesn't break the connection, exit 1" $?

r=$(frames "$(send "$(frame 1 0 1 "$(get_payload /hello.txt)")$(frame 1 0 2 "$(get_payload /nope)")$(frame 1 1 3 "$(get_payload /)")")")
[ "$r" = "02:200 02:404 02:200" ]; check "pipelined raw requests answered in order   [$r]" $?

# ---- 6. path traversal ----------------------------------------------------------
echo "== path traversal"

for p in "/../secret.txt" "/sub/../../secret.txt" "/./../secret.txt" "/$TMP/secret.txt" "/escape"; do
    bc "localhost:$PORT$p"; rc=$?
    grep -q "< status 403" "$TMP/err" && [ $rc -ne 0 ] && ! grep -q "TOP SECRET" "$TMP/out"
    check "GET $p -> 403, secret not leaked" $?
done

# ---- summary ---------------------------------------------------------------------
echo
echo "$PASS passed, $FAIL failed"
[ $FAIL -eq 0 ]
