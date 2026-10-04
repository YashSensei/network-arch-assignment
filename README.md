# binhttp

A minimal binary HTTP-like protocol, with a server and a client that speak it.
The spec is the only thing shared between the server and the client.

Protocol: see [SPEC.md](SPEC.md)

## Run

Needs Java 17+.

Server:

    ./bserve ./www 9000

Client (in progress):

    ./bcurl -v localhost:9000/index.html
