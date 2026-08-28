# Self-Describing CLI JSON Protocol

Status: Version 1 specification

Audience: Authors of command-line programs that will contribute tools to the MCP CLI Server

## 1. Purpose

This specification defines how a command-line program exposes its functionality to the MCP CLI Server.

The server does not know individual CLIs at build time. It discovers executable files at runtime and communicates with each executable through a small, self-describing JSON protocol.

A conforming CLI:

- implements the reserved commands `mcp describe` and `mcp invoke`;
- describes its tools and their JSON Schemas through `mcp describe`;
- accepts one JSON invocation through standard input for `mcp invoke`;
- returns one JSON response through standard output;
- keeps diagnostics on standard error; and
- provides honest safety annotations for every tool.

The protocol is intentionally independent of the CLI's normal human-facing commands. A CLI may provide any additional commands and output formats, but its `mcp` commands must follow this specification exactly.

## 2. Normative language

The words **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** describe normative requirements.

## 3. Process interface

### 3.1 Description

The server discovers a CLI by executing:

```text
<executable> mcp describe
```

The command:

- MUST accept no standard-input payload;
- MUST write exactly one UTF-8 JSON object to standard output;
- MUST NOT write banners, progress messages, or logs to standard output;
- MAY write diagnostics to standard error; and
- MUST exit with status `0` only when it produced a complete, valid descriptor.

### 3.2 Invocation

The server invokes a contributed tool by executing:

```text
<executable> mcp invoke
```

The server writes exactly one UTF-8 JSON request object to the process's standard input and then closes standard input.

The command:

- MUST read exactly one request object;
- MUST perform at most the requested tool operation;
- MUST write exactly one UTF-8 JSON response object to standard output;
- MUST NOT write any other content to standard output;
- MAY write diagnostics to standard error; and
- MUST terminate after responding.

Version 1 is a single-request, single-process protocol. Persistent workers, JSON Lines, and multiple requests per process are outside its scope.

### 3.3 Exit status

Exit status has transport meaning, not domain meaning:

- Status `0` means the CLI completed the protocol exchange and wrote a valid response. This includes responses where `success` is `false`.
- A nonzero status means the CLI failed before completing a trustworthy protocol exchange, for example because it crashed, could not start, or could not parse the request.

When the CLI can parse the request and express the failure as a structured response, it SHOULD return that response and exit with status `0`.

The server is not required to use standard output from a process that exits with a nonzero status.

### 3.4 Standard streams

| Stream | Required use |
| --- | --- |
| Standard input | Invocation request JSON only |
| Standard output | Descriptor or invocation response JSON only |
| Standard error | Human-readable diagnostics and logging |

Secrets, access tokens, credentials, and unredacted sensitive values MUST NOT be written to either output stream.

## 4. Common JSON rules

- JSON MUST be encoded as UTF-8.
- The top-level value MUST be an object.
- `protocolVersion` MUST be the string `"1"`.
- Property names and enum values are case-sensitive.
- Numbers MUST be valid JSON numbers.
- Timestamps, when included inside domain results, SHOULD use RFC 3339.
- Unknown protocol-level properties are prohibited in version 1.
- Domain objects inside `arguments`, `result`, and `error.details` are governed by the relevant tool schema and may contain arbitrary JSON values permitted by that schema.

## 5. CLI descriptor

### 5.1 Example

```json
{
  "protocolVersion": "1",
  "name": "customer",
  "version": "1.0.0",
  "description": "Manages customer records.",
  "tools": [
    {
      "name": "find",
      "title": "Find customers",
      "description": "Find customers matching a textual query.",
      "inputSchema": {
        "type": "object",
        "properties": {
          "query": {
            "type": "string",
            "minLength": 1
          },
          "limit": {
            "type": "integer",
            "minimum": 1,
            "maximum": 100,
            "default": 20
          }
        },
        "required": ["query"],
        "additionalProperties": false
      },
      "outputSchema": {
        "type": "object",
        "properties": {
          "customers": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "id": { "type": "string" },
                "name": { "type": "string" }
              },
              "required": ["id", "name"],
              "additionalProperties": false
            }
          }
        },
        "required": ["customers"],
        "additionalProperties": false
      },
      "annotations": {
        "readOnlyHint": true,
        "destructiveHint": false,
        "idempotentHint": true,
        "openWorldHint": false
      }
    }
  ]
}
```

### 5.2 Descriptor fields

| Field | Requirement | Meaning |
| --- | --- | --- |
| `protocolVersion` | Required | Protocol version; MUST equal `"1"` |
| `name` | Required | Stable CLI namespace |
| `version` | Required | Version of the CLI implementation |
| `description` | Required | Clear description of the CLI's domain |
| `tools` | Required | One or more contributed tool descriptors |

The CLI `name`:

- MUST match `^[a-z][a-z0-9_]{0,63}$`;
- MUST remain stable across releases;
- MUST identify the CLI uniquely within a server directory; and
- SHOULD describe a domain rather than an implementation detail.

The `version` SHOULD follow Semantic Versioning.

### 5.3 Tool fields

| Field | Requirement | Meaning |
| --- | --- | --- |
| `name` | Required | Stable tool name within the CLI namespace |
| `title` | Required | Short human-readable label |
| `description` | Required | Precise behavior, relevant limitations, and important side effects |
| `inputSchema` | Required | JSON Schema for the `arguments` object |
| `outputSchema` | Required | JSON Schema for a successful `result` object |
| `annotations` | Required | Safety and behavioral hints |

The tool `name`:

- MUST match `^[a-z][a-z0-9_]{0,63}$`;
- MUST be unique within the descriptor; and
- MUST remain stable while its meaning remains compatible.

The MCP server exposes a tool under the global name:

```text
<cli-name>_<tool-name>
```

For example, CLI `customer` tool `find` becomes `customer_find`.

Descriptions MUST explain the operation itself. They MUST NOT rely on knowledge of the CLI's human-facing help text.

### 5.4 JSON Schemas

`inputSchema` and `outputSchema`:

- MUST be valid JSON Schema 2020-12 schemas;
- MUST describe a top-level object;
- SHOULD set `additionalProperties` explicitly;
- SHOULD constrain strings, numbers, arrays, and enums as narrowly as the domain permits;
- MUST NOT contain remote schema references; and
- MUST be self-contained within the descriptor.

The CLI MUST validate invocation arguments against `inputSchema`. Server-side validation does not remove the CLI's responsibility to validate its own boundary.

On success, the CLI MUST return a `result` that validates against `outputSchema`.

Defaults in `inputSchema` are descriptive. A CLI MUST NOT assume that the server will insert them. If a property is absent, the CLI is responsible for applying any documented default.

### 5.5 Tool annotations

Every tool MUST provide all four annotations:

| Annotation | Meaning when `true` |
| --- | --- |
| `readOnlyHint` | The tool does not modify state |
| `destructiveHint` | The tool may perform an irreversible or difficult-to-recover modification |
| `idempotentHint` | Repeating the same call with the same arguments has no additional effect |
| `openWorldHint` | The tool may interact with systems outside a closed local domain |

Annotations are declarations, not enforcement. They MUST truthfully describe the operation.

A tool with `destructiveHint: true` MUST also use a description that states the destructive effect plainly.

## 6. Invocation request

### 6.1 Example

```json
{
  "protocolVersion": "1",
  "requestId": "01K5XQH2KG3Y7YN5T2Z5W8B51M",
  "tool": "find",
  "arguments": {
    "query": "Acme",
    "limit": 10
  }
}
```

### 6.2 Fields

| Field | Requirement | Meaning |
| --- | --- | --- |
| `protocolVersion` | Required | MUST equal `"1"` |
| `requestId` | Required | Opaque identifier assigned by the server |
| `tool` | Required | Local tool name from the descriptor, without the CLI namespace |
| `arguments` | Required | Object validated by the tool's `inputSchema` |

The CLI MUST copy `requestId` unchanged into its response.

The CLI MUST return a structured failure with code `UNKNOWN_TOOL` when `tool` does not name a currently supported tool.

## 7. Invocation response

The response is either a success response or a failure response.

### 7.1 Success

```json
{
  "protocolVersion": "1",
  "requestId": "01K5XQH2KG3Y7YN5T2Z5W8B51M",
  "success": true,
  "result": {
    "customers": [
      {
        "id": "customer-123",
        "name": "Acme S.L."
      }
    ]
  },
  "message": "Found one customer."
}
```

Success fields:

| Field | Requirement | Meaning |
| --- | --- | --- |
| `protocolVersion` | Required | MUST equal `"1"` |
| `requestId` | Required | Exact request identifier |
| `success` | Required | MUST be `true` |
| `result` | Required | Structured result matching `outputSchema` |
| `message` | Optional | Concise human-readable summary |

`message` SHOULD summarize the outcome without duplicating a large result. It MUST NOT be the only place where essential result data is returned.

### 7.2 Failure

```json
{
  "protocolVersion": "1",
  "requestId": "01K5XQH2KG3Y7YN5T2Z5W8B51M",
  "success": false,
  "error": {
    "code": "AUTHENTICATION_REQUIRED",
    "message": "The customer service credentials are unavailable.",
    "retryable": false,
    "details": {}
  }
}
```

Failure fields:

| Field | Requirement | Meaning |
| --- | --- | --- |
| `protocolVersion` | Required | MUST equal `"1"` |
| `requestId` | Required | Exact request identifier |
| `success` | Required | MUST be `false` |
| `error.code` | Required | Stable machine-readable error code |
| `error.message` | Required | Concise, actionable explanation |
| `error.retryable` | Required | Whether retrying later without changing arguments may succeed |
| `error.details` | Required | Structured, non-secret diagnostic details; MAY be empty |

Error codes MUST match `^[A-Z][A-Z0-9_]{0,63}$` and SHOULD remain stable across releases.

Recommended common error codes are:

- `INVALID_ARGUMENTS`
- `UNKNOWN_TOOL`
- `AUTHENTICATION_REQUIRED`
- `PERMISSION_DENIED`
- `NOT_FOUND`
- `CONFLICT`
- `DEPENDENCY_UNAVAILABLE`
- `RATE_LIMITED`
- `TIMEOUT`
- `INTERNAL_ERROR`

Domain-specific codes MAY be added.

An error message MUST NOT include a stack trace. A stack trace MAY be written to standard error if it contains no secrets.

## 8. Protocol schemas

The following schemas are normative structural schemas. A tool's own input and output schemas apply in addition to them.

### 8.1 CLI descriptor schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "protocolVersion": { "const": "1" },
    "name": { "type": "string", "pattern": "^[a-z][a-z0-9_]{0,63}$" },
    "version": { "type": "string", "minLength": 1 },
    "description": { "type": "string", "minLength": 1 },
    "tools": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "properties": {
          "name": { "type": "string", "pattern": "^[a-z][a-z0-9_]{0,63}$" },
          "title": { "type": "string", "minLength": 1 },
          "description": { "type": "string", "minLength": 1 },
          "inputSchema": { "type": "object" },
          "outputSchema": { "type": "object" },
          "annotations": {
            "type": "object",
            "properties": {
              "readOnlyHint": { "type": "boolean" },
              "destructiveHint": { "type": "boolean" },
              "idempotentHint": { "type": "boolean" },
              "openWorldHint": { "type": "boolean" }
            },
            "required": [
              "readOnlyHint",
              "destructiveHint",
              "idempotentHint",
              "openWorldHint"
            ],
            "additionalProperties": false
          }
        },
        "required": [
          "name",
          "title",
          "description",
          "inputSchema",
          "outputSchema",
          "annotations"
        ],
        "additionalProperties": false
      }
    }
  },
  "required": ["protocolVersion", "name", "version", "description", "tools"],
  "additionalProperties": false
}
```

The structural schema cannot by itself assert uniqueness of tool names or validate the nested JSON Schemas. Implementations MUST enforce those additional requirements separately.

### 8.2 Invocation request schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "protocolVersion": { "const": "1" },
    "requestId": { "type": "string", "minLength": 1, "maxLength": 128 },
    "tool": { "type": "string", "pattern": "^[a-z][a-z0-9_]{0,63}$" },
    "arguments": { "type": "object" }
  },
  "required": ["protocolVersion", "requestId", "tool", "arguments"],
  "additionalProperties": false
}
```

### 8.3 Invocation response schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "oneOf": [
    {
      "type": "object",
      "properties": {
        "protocolVersion": { "const": "1" },
        "requestId": { "type": "string", "minLength": 1, "maxLength": 128 },
        "success": { "const": true },
        "result": { "type": "object" },
        "message": { "type": "string" }
      },
      "required": ["protocolVersion", "requestId", "success", "result"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "protocolVersion": { "const": "1" },
        "requestId": { "type": "string", "minLength": 1, "maxLength": 128 },
        "success": { "const": false },
        "error": {
          "type": "object",
          "properties": {
            "code": { "type": "string", "pattern": "^[A-Z][A-Z0-9_]{0,63}$" },
            "message": { "type": "string", "minLength": 1 },
            "retryable": { "type": "boolean" },
            "details": { "type": "object" }
          },
          "required": ["code", "message", "retryable", "details"],
          "additionalProperties": false
        }
      },
      "required": ["protocolVersion", "requestId", "success", "error"],
      "additionalProperties": false
    }
  ]
}
```

## 9. Cancellation and termination

The server may terminate a CLI process because of cancellation, timeout, shutdown, or an output limit.

A CLI:

- SHOULD stop promptly when it receives the platform's normal termination signal;
- SHOULD release temporary resources during normal termination;
- MUST NOT depend on receiving an opportunity to clean up; and
- SHOULD design mutating operations so an interrupted invocation has a documented, recoverable outcome.

The protocol does not guarantee that an invocation which timed out had no effect. Tool descriptions and results should make this clear where it matters.

## 10. Security requirements

- A CLI MUST treat all invocation arguments as untrusted input.
- A CLI MUST NOT evaluate argument values as shell programs.
- A CLI MUST NOT expose credentials through its descriptor.
- A CLI SHOULD obtain credentials through its normal secure configuration mechanism.
- A CLI MUST NOT return secrets merely because they are available to its process.
- A CLI MUST enforce domain authorization itself. Exposure through MCP does not bypass the CLI's authorization boundary.
- A CLI MUST use narrow schemas and reject unknown properties unless extensibility is intentional.
- A CLI author MUST review effect annotations whenever behavior changes.

## 11. Compatibility

Within protocol version `1`:

- New tools MAY be added.
- Existing tools MAY add optional input properties.
- Required input properties MUST NOT be added without introducing a new tool name or a coordinated breaking release.
- Result properties MUST NOT change meaning incompatibly.
- Tool names and error codes SHOULD remain stable.
- Removing or incompatibly changing a tool is a breaking CLI capability change even when the executable version changes.

A future protocol version will use a different `protocolVersion`. A CLI MUST NOT silently interpret an unknown protocol version as version `1`.

## 12. CLI conformance checklist

A CLI is ready to install when all of the following are true:

- `mcp describe` returns a valid descriptor and exits with status `0`.
- The descriptor is deterministic for the same installed configuration.
- CLI and tool names satisfy the required patterns and are unique.
- Every tool has valid, self-contained input and output schemas.
- Every tool has complete and truthful annotations.
- Valid invocations return schema-valid success results.
- Domain failures return schema-valid failure responses with status `0`.
- Malformed input cannot cause arbitrary command or code execution.
- Standard output never contains logs or other non-protocol text.
- Standard error and responses contain no secrets.
- The process reacts safely to termination and timeout.
- Automated contract tests exercise description, success, failure, invalid arguments, unknown tools, and output-schema validation.
