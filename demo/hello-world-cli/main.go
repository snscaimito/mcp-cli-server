// hello-world is a protocol-v1 reference CLI for MCP CLI Server demonstrations.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
)

type annotations struct {
	ReadOnlyHint    bool `json:"readOnlyHint"`
	DestructiveHint bool `json:"destructiveHint"`
	IdempotentHint  bool `json:"idempotentHint"`
	OpenWorldHint   bool `json:"openWorldHint"`
}

type toolDescriptor struct {
	Name         string         `json:"name"`
	Title        string         `json:"title"`
	Description  string         `json:"description"`
	InputSchema  map[string]any `json:"inputSchema"`
	OutputSchema map[string]any `json:"outputSchema"`
	Annotations  annotations    `json:"annotations"`
}

type descriptor struct {
	ProtocolVersion string           `json:"protocolVersion"`
	Name            string           `json:"name"`
	Version         string           `json:"version"`
	Description     string           `json:"description"`
	Tools           []toolDescriptor `json:"tools"`
}

type greetingArguments struct {
	Name string `json:"name"`
}

type invocationRequest struct {
	ProtocolVersion string             `json:"protocolVersion"`
	RequestID       string             `json:"requestId"`
	Tool            string             `json:"tool"`
	Arguments       *greetingArguments `json:"arguments"`
}

type cliError struct {
	Code      string         `json:"code"`
	Message   string         `json:"message"`
	Retryable bool           `json:"retryable"`
	Details   map[string]any `json:"details"`
}

type failureResponse struct {
	ProtocolVersion string   `json:"protocolVersion"`
	RequestID       string   `json:"requestId"`
	Success         bool     `json:"success"`
	Error           cliError `json:"error"`
}

type successResponse struct {
	ProtocolVersion string         `json:"protocolVersion"`
	RequestID       string         `json:"requestId"`
	Success         bool           `json:"success"`
	Result          map[string]any `json:"result"`
	Message         string         `json:"message"`
}

func main() {
	if len(os.Args) != 3 || os.Args[1] != "mcp" {
		fmt.Fprintln(os.Stderr, "Usage: hello-world mcp <describe|invoke>")
		os.Exit(2)
	}

	switch os.Args[2] {
	case "describe":
		writeJSON(helloDescriptor())
	case "invoke":
		invoke()
	default:
		fmt.Fprintln(os.Stderr, "Unknown mcp command")
		os.Exit(2)
	}
}

func invoke() {
	request, err := readRequest()
	if err != nil {
		writeFailure("", "INVALID_ARGUMENTS", "Request must be one valid protocol JSON object.")
		return
	}
	if request.ProtocolVersion != "1" || request.RequestID == "" || len(request.RequestID) > 128 || request.Tool == "" || request.Arguments == nil {
		writeFailure(request.RequestID, "INVALID_ARGUMENTS", "Request has an invalid protocol envelope.")
		return
	}
	if request.Tool != "greet" {
		writeFailure(request.RequestID, "UNKNOWN_TOOL", "The requested tool is not available.")
		return
	}
	if request.Arguments.Name == "" {
		writeFailure(request.RequestID, "INVALID_ARGUMENTS", "name must be a non-empty string.")
		return
	}

	writeJSON(successResponse{
		ProtocolVersion: "1",
		RequestID:       request.RequestID,
		Success:         true,
		Result:          map[string]any{"greeting": "Hello, " + request.Arguments.Name + "!"},
		Message:         "Greeting created.",
	})
}

func readRequest() (invocationRequest, error) {
	decoder := json.NewDecoder(os.Stdin)
	decoder.DisallowUnknownFields()
	var request invocationRequest
	if err := decoder.Decode(&request); err != nil {
		return invocationRequest{}, err
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return invocationRequest{}, fmt.Errorf("request contains additional JSON values")
	}
	return request, nil
}

func writeFailure(requestID, code, message string) {
	writeJSON(failureResponse{
		ProtocolVersion: "1",
		RequestID:       requestID,
		Success:         false,
		Error:           cliError{Code: code, Message: message, Retryable: false, Details: map[string]any{}},
	})
}

func writeJSON(value any) {
	if err := json.NewEncoder(os.Stdout).Encode(value); err != nil {
		fmt.Fprintln(os.Stderr, "Unable to write MCP JSON response")
		os.Exit(1)
	}
}

func helloDescriptor() descriptor {
	return descriptor{
		ProtocolVersion: "1",
		Name:            "hello_world",
		Version:         "1.0.0",
		Description:     "A minimal protocol-v1 greeting CLI for MCP CLI Server demonstrations.",
		Tools: []toolDescriptor{{
			Name:        "greet",
			Title:       "Greet someone",
			Description: "Returns a greeting for the supplied name.",
			InputSchema: map[string]any{
				"type": "object", "properties": map[string]any{"name": map[string]any{"type": "string", "minLength": 1}},
				"required": []string{"name"}, "additionalProperties": false,
			},
			OutputSchema: map[string]any{
				"type": "object", "properties": map[string]any{"greeting": map[string]any{"type": "string"}},
				"required": []string{"greeting"}, "additionalProperties": false,
			},
			Annotations: annotations{ReadOnlyHint: true, DestructiveHint: false, IdempotentHint: true, OpenWorldHint: false},
		}},
	}
}
