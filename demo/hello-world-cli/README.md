# Hello World CLI

This standalone Go CLI is a protocol-v1 demonstration and test fixture for the MCP CLI Server. It contributes one tool, `hello_world_greet`, and has no runtime dependency beyond the compiled executable.

Build it from this directory:

```sh
go build -o build/hello-world .
```

Install the executable as a direct child of a trusted CLI directory. The server deliberately rejects symbolic links and recursive discovery.

```sh
mkdir -p /tmp/mcp-demo-clis
cp build/hello-world /tmp/mcp-demo-clis/hello-world
MCP_CLI_DIRECTORY=/tmp/mcp-demo-clis java -jar ../../target/mcp-cli-server-1.0.0.jar
```

The server discovers `hello_world_greet`. Calls use this input schema:

```json
{ "name": "Ada" }
```

and return:

```json
{ "greeting": "Hello, Ada!" }
```

The CLI writes exactly one JSON object to standard output for both `mcp describe` and `mcp invoke`; diagnostics are written only to standard error.
