# binhttp

A minimal binary HTTP-like protocol, with a server and a client that speak it.
The spec is the only thing shared between the server and the client.

- [SPEC.md](SPEC.md): the protocol
- [HEXDUMP.md](HEXDUMP.md): one request and response, every byte annotated

## Run

Needs Java 17+.

Server:

    ./bserve ./www 9000

Client:

    ./bcurl -v localhost:9000/index.html

Several paths go over one connection:

    ./bcurl localhost:9000/index.html localhost:9000/hello.txt

## Test

    tests/run_tests.sh
