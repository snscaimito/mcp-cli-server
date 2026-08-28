# MCP CLI Server

`mcp-cli-server` is a loopback-only Spring Boot Model Context Protocol server. It discovers direct-child executable files in one trusted directory. Each executable contributes tools by implementing the JSON protocol in [CLI_BUILDER_SPEC.md](CLI_BUILDER_SPEC.md).

It is infrastructure, not a command executor: an MCP request cannot choose an executable, shell command, working directory, or environment variable.

## Build and package

Java 21 and no globally installed Maven are required. The checked-in wrapper downloads the pinned Maven distribution when needed.

```sh
./mvnw test
./mvnw package
```

The executable JAR is `target/mcp-cli-server-<version>.jar`. The Ubuntu package is `target/mcp-cli-server-<version>.deb` and installs the JAR in `/usr/lib/mcp-cli-server/`, a launcher in `/usr/bin/mcp-cli-server`, and an opt-in systemd unit. Configure `/etc/mcp-cli-server/application.yml` with an absolute trusted CLI directory before enabling the service.

```yaml
mcp:
  cli:
    directory: /opt/mcp-clis
```

The package does not enable the service automatically. After configuration:

```sh
sudo systemctl enable --now mcp-cli-server
```

## Install from APT

Released versions are published to the signed Caimito APT repository. Install its public archive key and source once:

```sh
curl -fsSL https://raw.githubusercontent.com/snscaimito/mcp-cli-server/apt/caimito-mcp-cli-server-archive-keyring.gpg \
  | sudo tee /usr/share/keyrings/caimito-mcp-cli-server-archive-keyring.gpg >/dev/null

echo 'deb [signed-by=/usr/share/keyrings/caimito-mcp-cli-server-archive-keyring.gpg] https://raw.githubusercontent.com/snscaimito/mcp-cli-server/apt stable main' \
  | sudo tee /etc/apt/sources.list.d/caimito-mcp-cli-server.list >/dev/null

sudo apt update
sudo apt install mcp-cli-server
```

The repository is published to the public `apt` branch by the `Publish signed APT repository` workflow when a `vMAJOR.MINOR.PATCH` tag is pushed. It signs APT metadata with a dedicated archive signing key stored only in the repository's `APT_SIGNING_KEY` and `APT_SIGNING_PASSPHRASE` GitHub Actions secrets.

The archive signing-key fingerprint is `1306 3FDE C676 38DD F9A5 B1CF BDAC 9723 1117 D9F0`; verify it after downloading the key before trusting a new installation source.

## Local operation

Create a directory and a minimal conforming executable:

```sh
mkdir -p /tmp/mcp-clis
cat >/tmp/mcp-clis/customer <<'EOF'
#!/bin/sh
if [ "$1" = mcp ] && [ "$2" = describe ]; then
  printf '%s' '{"protocolVersion":"1","name":"customer","version":"1.0.0","description":"Customer lookup","tools":[{"name":"find","title":"Find customers","description":"Find customers by query.","inputSchema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"],"additionalProperties":false},"outputSchema":{"type":"object","properties":{"customers":{"type":"array"}},"required":["customers"],"additionalProperties":false},"annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}}]}'
elif [ "$1" = mcp ] && [ "$2" = invoke ]; then
  cat >/dev/null
  printf '%s' '{"protocolVersion":"1","requestId":"invalid-example","success":false,"error":{"code":"NOT_IMPLEMENTED","message":"Example only","retryable":false,"details":{}}}'
fi
EOF
chmod 755 /tmp/mcp-clis/customer
MCP_CLI_DIRECTORY=/tmp/mcp-clis ./mvnw spring-boot:run
```

The server listens on `127.0.0.1` and Spring AI exposes Streamable HTTP at `/mcp`. Configure an MCP client with:

```json
{ "url": "http://127.0.0.1:8080/mcp" }
```

`tools/list` will include `customer_find`. A real CLI must copy the server-supplied `requestId` from its `mcp invoke` JSON input into its response; the abbreviated example intentionally returns a failure and is only for descriptor discovery.

## Configuration and safety

All configuration is under `mcp.cli`. The directory is mandatory, absolute, readable, and is never modified by the server. Discovery ignores hidden files, symbolic links, non-executable files, and descendants. It periodically reconciles as a fallback to filesystem watching. Invalid or replaced CLIs are removed fail-closed without affecting other valid CLIs.

The child environment is cleared and only `HOME`, `USER`, `LANG`, `LC_ALL`, and `TZ` are inherited by default. Output, concurrency, and timeout limits are configured in `application.yml`; errors exposed to clients use stable bridge codes and never contain process stderr or environment values.

Binding to a non-loopback address is not production-ready: this transport supplies no remote authentication or authorization. Put an explicitly designed security boundary in front of it before any remote exposure.

Health and metrics are available through Spring Boot Actuator at `/actuator/health` and `/actuator/metrics`.

## Demo CLI

[demo/hello-world-cli](demo/hello-world-cli) contains a separately buildable protocol-v1 reference CLI. It contributes `hello_world_greet` and is useful for local discovery and invocation tests.
