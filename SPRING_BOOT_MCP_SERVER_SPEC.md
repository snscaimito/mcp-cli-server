# Spring Boot MCP CLI Server Implementation Specification

Status: Build specification

Audience: The implementation agent responsible for building the Spring Boot application

Normative dependency: [`CLI_BUILDER_SPEC.md`](CLI_BUILDER_SPEC.md)

## 1. Objective

Build a Spring Boot MCP server that discovers self-describing executables from a configured directory and exposes their declared tools through Model Context Protocol.

The application MUST require no Java code, rebuild, or restart when a conforming CLI is added, updated, or removed.

The application is generic infrastructure. It MUST NOT contain adapters or domain logic for individual CLIs.

## 2. Required outcome

Given an executable named `customer` that implements the CLI JSON protocol and describes a local tool named `find`, an MCP client must discover and invoke:

```text
customer_find
```

The complete flow is:

```text
MCP tools/list
    |
    v
Dynamic MCP tool registry
    |
MCP tools/call customer_find
    |
    v
Validate MCP arguments
    |
    v
Execute customer mcp invoke
    |
    v
JSON request on stdin / JSON response on stdout
    |
    v
MCP structuredContent + text content or MCP tool error
```

## 3. Scope

### 3.1 Included

- Runtime discovery of direct-child executable files.
- CLI description through `<executable> mcp describe`.
- Strict validation of descriptors and JSON Schemas.
- Dynamic MCP tool registration and removal.
- MCP tool-list change notifications.
- Invocation through `<executable> mcp invoke`.
- JSON request and response mapping.
- Timeouts, cancellation, concurrency control, and output limits.
- Safe process construction without a shell.
- Operational health, metrics, and diagnostic logging.
- Automated unit, process-integration, and MCP end-to-end tests.
- Local Streamable HTTP operation.

### 3.2 Excluded from the first implementation

- CLI-specific Java contributors.
- Parsing human-oriented `--help` output.
- Arbitrary shell execution.
- Recursive directory discovery.
- Persistent CLI worker processes.
- Multiple requests per CLI process.
- Interactive terminal support.
- Binary tool responses.
- Installing, downloading, updating, or deleting CLI executables.
- An administration UI.
- Remote production authentication and authorization.

## 4. Technology baseline

Use:

- Java 21;
- Maven with a checked-in Maven Wrapper;
- the current stable Spring Boot release compatible with the selected stable Spring AI release;
- the Spring AI BOM for Spring AI dependency management;
- `spring-ai-starter-mcp-server-webmvc`;
- synchronous MCP server APIs;
- Streamable HTTP transport;
- Jackson for JSON;
- Bean Validation where appropriate;
- Spring Boot Actuator; and
- virtual threads for blocking process work.

The implementation MUST pin resolved release versions in the build. It MUST NOT depend on snapshots, milestones, release candidates, or dynamic dependency versions.

Use the programmatic MCP server tool specification API. Annotation-based `@McpTool` methods are unsuitable because the tool set is unknown at application build time.

## 5. Project requirements

- Artifact name: `mcp-cli-server`.
- Produce one executable Spring Boot JAR.
- Use a conventional package-by-feature layout.
- Keep protocol models independent of Spring and MCP SDK types.
- Include `README.md` with build, configuration, startup, and local MCP connection instructions.
- Include the two specification files in the repository unchanged unless the user explicitly approves a specification revision.
- Do not introduce a database.
- Do not introduce Docker as a runtime requirement.

## 6. Configuration

Provide type-safe configuration properties under `mcp.cli`.

At minimum support:

```yaml
mcp:
  cli:
    directory: /absolute/path/to/clis
    discovery-timeout: 5s
    invocation-timeout: 60s
    change-debounce: 500ms
    max-stdout-bytes: 1048576
    max-stderr-bytes: 65536
    max-concurrent-invocations: 16
    environment-allowlist:
      - HOME
      - USER
      - LANG
      - LC_ALL
      - TZ

spring:
  ai:
    mcp:
      server:
        name: mcp-cli-server
        version: ${project.version}
        type: SYNC
        protocol: STREAMABLE
  threads:
    virtual:
      enabled: true

server:
  address: 127.0.0.1
```

Exact Spring property names MUST be verified against the selected stable Spring AI release during implementation.

Requirements:

- `mcp.cli.directory` MUST be absolute.
- Startup MUST fail with an actionable configuration error if the directory does not exist, is not a directory, or cannot be read.
- Durations and byte limits MUST be positive.
- Concurrency MUST be at least one.
- The default bind address MUST be loopback.
- The README MUST warn that binding to a non-loopback address requires a separately designed security boundary.
- MCP HTTP endpoints MUST NOT be represented as production-ready for remote exposure.

Additional environment variable names may be configured. Invocation arguments MUST never control which environment variables are inherited.

## 7. Discovery boundary

The configured directory is a trusted installation boundary managed outside the application.

The scanner MUST:

- examine only direct children of the configured directory;
- ignore hidden files whose names begin with `.`;
- accept only regular files with an executable permission bit;
- reject symbolic links;
- resolve and verify each candidate's real path;
- verify that the real path remains a direct child of the configured directory; and
- process candidates in deterministic filename order.

Directories, sockets, devices, non-executable files, and other filesystem entries MUST be ignored with DEBUG-level diagnostics.

The application MUST NOT modify the directory or its contents.

## 8. Discovery lifecycle

### 8.1 Startup

At startup:

1. Scan the directory.
2. Execute each candidate with arguments `mcp`, `describe`.
3. Apply the discovery timeout and output limits.
4. Parse standard output as exactly one JSON object.
5. Validate the object against the CLI protocol.
6. Validate every input and output schema as JSON Schema 2020-12.
7. Build a complete candidate registry.
8. Register valid tools with the MCP server.
9. Start accepting MCP traffic only after the initial registry is internally consistent.

One invalid CLI MUST NOT prevent valid CLIs or the server from starting. It MUST be excluded and reported through diagnostics.

### 8.2 Runtime changes

Use `WatchService` for prompt change detection and a periodic full reconciliation as a fallback for missed filesystem events.

The reconciliation interval MAY be a fixed internal value for the first implementation, but it SHOULD be configurable if doing so remains simple.

Debounce filesystem events. Before describing a changed file, verify that its size and modification time are stable across the debounce interval.

Reconciliation MUST handle:

- a newly added executable;
- an atomically replaced executable;
- a modified executable;
- a removed executable;
- executable permission changes; and
- an invalid or temporarily incomplete executable.

Build and validate a candidate contribution before publishing it. Registry readers MUST observe either the complete old contribution or the complete new contribution, never a partial state.

If an installed executable becomes absent, non-executable, invalid, or incompatible, fail closed by removing all of its tools from the active registry.

After an effective tool-set change, update the MCP server registrations and emit `notifications/tools/list_changed` to connected clients. Do not emit a notification when reconciliation produces an equivalent tool set.

### 8.3 Duplicate identities

- A descriptor's CLI name MUST be unique across active executables.
- Tool names MUST be unique inside one descriptor.
- The global MCP name is `<cli-name>_<tool-name>`.
- If multiple installed executables declare the same CLI name, none of the conflicting contributions may be active.
- Log one actionable warning that identifies the conflicting paths without logging their environment.

## 9. Descriptor validation

Validation MUST occur before MCP registration.

Reject a complete CLI contribution when:

- the process times out or exits nonzero;
- standard output exceeds its limit;
- standard output is empty or contains anything other than one JSON object;
- `protocolVersion` is unsupported;
- required fields are absent or unknown protocol fields are present;
- names violate their patterns;
- tool names are duplicated;
- an input or output schema is invalid, remote, or not object-rooted;
- an annotation is missing;
- a global MCP tool name is invalid for the selected SDK; or
- the descriptor cannot be represented faithfully as an MCP tool.

Do not partially register the valid-looking tools of an invalid descriptor.

Descriptor failures are operational conditions, not application crashes.

## 10. Internal architecture

The design MUST preserve the following responsibilities, although exact class names may vary.

### 10.1 Protocol model

Plain Java records representing:

- `CliDescriptor`;
- `CliToolDescriptor`;
- `CliToolAnnotations`;
- `CliInvocationRequest`;
- success and failure response variants; and
- `CliError`.

Use explicit polymorphic response parsing based on `success`. Do not deserialize protocol documents into untyped nested maps except for schema documents, arguments, results, and error details.

### 10.2 Process runner

A single process-execution abstraction responsible for:

- exact executable path and argument vector;
- sanitized environment;
- fixed working directory;
- UTF-8 standard input;
- concurrent draining of stdout and stderr;
- byte limits;
- timeout and cancellation;
- exit status;
- process duration; and
- graceful then forced termination.

### 10.3 Descriptor client

Executes `mcp describe`, parses its response, and returns either a validated descriptor candidate or a typed discovery failure.

### 10.4 Registry

Owns an immutable snapshot containing:

- installed CLI identity and executable path;
- CLI version and description;
- local and global tool names;
- validated schemas and annotations; and
- the executable fingerprint used for reconciliation.

Registry updates MUST be atomic and safe under concurrent list and call operations.

### 10.5 Reconciler

Converts a filesystem snapshot into a validated candidate registry, compares it with the active registry, and publishes only effective changes.

### 10.6 MCP registrar

Converts validated CLI tool descriptors into programmatic synchronous MCP tool specifications. It owns calls to the selected Spring AI or MCP Java SDK APIs for adding tools, removing tools, and notifying clients that the tool list changed.

Keep SDK-specific types inside this boundary.

### 10.7 Invocation service

Resolves a global MCP name against one registry snapshot, validates arguments, creates the CLI request, invokes the process runner, validates the response, and maps the outcome to MCP.

## 11. Process execution

All child processes MUST be created with `ProcessBuilder` or an equivalently safe direct-process API.

The application MUST NOT use:

- `sh -c`;
- `bash -c`;
- `zsh -c`;
- command strings assembled for shell evaluation; or
- user-controlled executable paths or process arguments other than JSON sent through standard input.

The only process argument vectors are:

```text
[<verified executable>, "mcp", "describe"]
[<verified executable>, "mcp", "invoke"]
```

Before every invocation, verify that the executable still resolves to the registered trusted path. A replacement detected during a call may complete under operating-system semantics, but subsequent calls MUST use a reconciled contribution.

### 11.1 Environment

Clear the inherited child environment and copy only allowlisted keys that exist in the server environment.

The application MUST NOT log environment values.

The child working directory MUST be fixed by server configuration or use the CLI directory. It MUST NOT be controlled by MCP arguments.

### 11.2 Stream handling

Drain stdout and stderr concurrently to prevent process deadlock.

Enforce byte limits while reading, not after buffering an unbounded stream. If either configured hard limit is exceeded:

1. terminate the process;
2. mark the call as a process failure;
3. retain only the bounded diagnostic prefix or suffix needed for troubleshooting; and
4. do not attempt to parse a truncated standard output document.

### 11.3 Timeout and cancellation

On timeout, client cancellation, or application shutdown:

1. request normal process termination;
2. wait a short bounded grace period; and
3. forcibly terminate the process if it remains alive.

No CLI child process may be left running after its server invocation has completed exceptionally.

### 11.4 Concurrency

Use a fair application-wide concurrency limiter before starting child processes.

When capacity cannot be obtained within the remaining invocation deadline, return an MCP tool error with a retryable server-busy code. Do not create an unbounded process queue.

Virtual threads may wait for processes, streams, and concurrency permits. Do not introduce a reactive application architecture around blocking child processes.

## 12. Invocation behavior

For an MCP call to `customer_find` with arguments `{ "query": "Acme" }`:

1. Resolve the active descriptor and local tool `find`.
2. Validate arguments against its `inputSchema`.
3. Generate an opaque request identifier.
4. Construct the protocol request using local name `find`.
5. Start the registered executable with `mcp invoke`.
6. Write one JSON object to stdin and close stdin.
7. Read bounded stdout and stderr concurrently.
8. Enforce cancellation and timeout.
9. Require exit status `0`.
10. Parse exactly one response object.
11. Verify protocol version and matching request identifier.
12. For success, validate `result` against `outputSchema`.
13. Map the response to an MCP tool result.

The MCP SDK's input validation SHOULD remain enabled. The invocation service MUST still validate at its own boundary when needed to produce deterministic tests and errors.

## 13. MCP mapping

### 13.1 Tool definition

Map:

- global tool name to MCP `name`;
- CLI tool `title` to MCP `title`;
- CLI tool `description` to MCP `description`;
- `inputSchema` to MCP `inputSchema`;
- `outputSchema` to MCP `outputSchema`; and
- all four annotations to their MCP equivalents.

Do not add undocumented arguments or silently weaken schemas.

### 13.2 Successful call

For `success: true`:

- set MCP `structuredContent` to the CLI `result` object;
- set `isError` to `false`;
- include one text content item containing `message` when present; and
- otherwise include a compact JSON rendering of `result` as text for clients that do not display structured content.

### 13.3 CLI-declared failure

For `success: false`:

- set MCP `isError` to `true`;
- provide concise text containing the error code and message;
- include non-secret structured error details when supported without violating the declared output contract; and
- do not convert a domain failure into a JSON-RPC protocol error.

### 13.4 Bridge failure

Timeouts, nonzero exits, invalid JSON, mismatched request identifiers, oversized output, schema-invalid results, and similar execution failures become MCP tool results with `isError: true` and stable bridge error codes.

Messages returned to MCP clients MUST be actionable but MUST NOT expose:

- stack traces;
- environment values;
- credentials;
- arbitrary full stderr;
- server filesystem paths beyond what is necessary; or
- internal exception class names.

Unknown MCP tool names are handled through normal MCP protocol behavior rather than starting a process.

## 14. Stable bridge error codes

Define and test at least:

- `CLI_BUSY`
- `CLI_TIMEOUT`
- `CLI_CANCELLED`
- `CLI_EXITED_NONZERO`
- `CLI_OUTPUT_TOO_LARGE`
- `CLI_INVALID_RESPONSE`
- `CLI_RESPONSE_MISMATCH`
- `CLI_RESULT_SCHEMA_VIOLATION`
- `CLI_UNAVAILABLE`
- `CLI_INTERNAL_ERROR`

These codes are part of the server's externally observable behavior and SHOULD remain stable.

## 15. Observability

### 15.1 Logging

Use layered logger categories so operators can enable discovery or process detail independently.

At INFO, log only meaningful lifecycle events:

- server startup with directory and effective limits;
- initial discovery summary;
- CLI contribution added, updated, or removed;
- effective MCP tool count changes; and
- graceful shutdown summary.

At DEBUG, log:

- candidate discovery decisions;
- descriptor probe timing and exit status;
- reconciliation comparisons; and
- invocation lifecycle with request identifier, CLI name, tool name, duration, and outcome.

At WARN, log actionable recoverable conditions such as an invalid descriptor, duplicate namespace, timeout, nonzero exit, or result-schema violation.

At ERROR, log only application-level failures that prevent the server or a major subsystem from operating.

Do not log the same exception at multiple layers. Log argument names if useful, but never log raw argument values by default.

### 15.2 Metrics

Expose Micrometer metrics for:

- active CLI count;
- active MCP tool count;
- discovery attempts and failures by reason;
- invocation count by CLI, tool, and outcome;
- invocation duration;
- active child processes;
- concurrency rejections;
- timeouts;
- forced terminations; and
- invalid or oversized responses.

Metric tags MUST be bounded. Do not tag with request identifiers, filesystem paths, error messages, or user input.

### 15.3 Health

Provide an Actuator health contributor for the CLI subsystem.

- `UP`: directory accessible and reconciliation running, even when it contains zero valid CLIs.
- `DEGRADED` is not a standard Spring status unless explicitly configured; if used, configure its HTTP mapping and document it.
- `DOWN`: directory inaccessible after startup, watcher/reconciler terminated, or registry subsystem cannot operate.

Individual invalid CLIs belong in health details and metrics but do not make the whole server `DOWN` while valid reconciliation continues.

## 16. Security

- Bind to loopback by default.
- Treat every MCP request and every CLI response as untrusted data.
- Treat directory contents as trusted installed code but still validate their protocol output.
- Never expose a generic executable or arguments tool.
- Never permit MCP input to select an executable path, environment variable, working directory, or process argument.
- Do not follow symlinks.
- Do not execute files outside the configured direct-child boundary.
- Do not log secrets or complete process environments.
- Preserve all MCP effect annotations supplied by the CLI.
- Document that the MCP transport starter does not provide authentication by itself.

Remote authentication and authorization require a separate approved design. Do not invent a production security scheme as part of this implementation.

## 17. Shutdown

On application shutdown:

1. stop accepting new CLI invocations;
2. stop the watcher and reconciliation scheduler;
3. cancel or allow a short bounded grace period for active calls;
4. terminate remaining child processes;
5. close MCP server resources through Spring lifecycle management; and
6. leave the CLI directory unchanged.

Shutdown MUST complete within a configured or documented bounded duration.

## 18. Testing requirements

### 18.1 Unit tests

Cover:

- protocol JSON parsing and rejection of unknown fields;
- success and failure response polymorphism;
- name validation and global-name construction;
- JSON Schema validation;
- duplicate CLI and tool detection;
- registry snapshot comparison;
- argument validation;
- MCP success and error mapping;
- environment allowlisting; and
- bounded-output behavior.

### 18.2 Process integration fixtures

Create test-only executable fixtures that can exhibit:

- a valid descriptor;
- a valid success response;
- a valid CLI-declared failure;
- invalid descriptor JSON;
- standard-output contamination;
- nonzero exit;
- mismatched request identifier;
- invalid result schema;
- excessive stdout;
- excessive stderr;
- timeout;
- ignored termination followed by forced termination; and
- secret-like stderr used to verify that client errors do not echo it.

Fixtures MUST NOT become production CLIs.

### 18.3 Reconciliation integration tests

Using a temporary directory, prove:

- startup with an empty directory;
- startup with valid and invalid CLIs together;
- addition without restart;
- atomic replacement without a partial registry;
- removal without restart;
- permission removal;
- duplicate namespace handling;
- unchanged rescans do not emit changes; and
- invalid replacement fails closed.

### 18.4 MCP end-to-end tests

Start the application on an ephemeral port and use an MCP client to prove:

- initialization succeeds;
- `tools/list` contains a dynamically discovered tool with correct schemas and annotations;
- `tools/call` executes the corresponding CLI;
- structured success content is returned;
- CLI-declared failures have `isError: true`;
- invalid arguments do not start the CLI;
- runtime addition and removal change `tools/list` without restarting; and
- tool-list change notification behavior matches the selected SDK.

### 18.5 Build verification

The completed repository MUST pass:

```text
./mvnw test
```

Also run any configured formatting, static-analysis, and packaging checks. The Maven Wrapper must work from a clean checkout without a globally installed Maven.

## 19. Acceptance criteria

The implementation is complete only when all of the following are demonstrated:

1. The application builds as one executable Spring Boot JAR.
2. It starts with an absolute empty CLI directory and exposes an empty dynamic tool list.
3. Dropping in a conforming executable adds its tools without restart.
4. Each MCP tool has the specified namespaced name, metadata, schemas, and annotations.
5. Calling a tool launches only the corresponding verified executable with `mcp invoke`.
6. The request sent to stdin conforms to the CLI protocol.
7. A valid CLI success becomes valid MCP structured content.
8. A valid CLI failure becomes an MCP tool error rather than a server crash.
9. Invalid, slow, noisy, missing, or replaced CLIs are contained and reported without taking down valid contributions.
10. Removing a CLI removes its tools and announces the tool-list change.
11. No shell is involved in process execution.
12. Concurrency, timeout, cancellation, and output limits are enforced and tested.
13. The default network bind is loopback-only.
14. Logs and client-visible errors do not expose secrets, environments, or unrestricted stderr.
15. The full automated test suite passes.
16. The README lets a new developer build the server, create a minimal conforming CLI, run both, and make one MCP tool call.

## 20. Implementation discipline

- Verify the selected Spring AI APIs against official documentation before coding because dynamic registration APIs may vary by release.
- Prefer the smallest design that satisfies this specification.
- Do not replace explicit protocol validation with permissive data binding.
- Do not weaken fail-closed behavior to keep a malformed CLI visible.
- Do not add speculative plugin formats, databases, message brokers, or administration surfaces.
- Keep transport concerns, MCP SDK concerns, process execution, protocol models, and registry state in separate boundaries.
- Report local tests, packaging, commit, push, deployment, and live verification as distinct states.

