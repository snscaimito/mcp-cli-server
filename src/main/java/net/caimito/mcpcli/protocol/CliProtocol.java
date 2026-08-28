package net.caimito.mcpcli.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public final class CliProtocol {
    private CliProtocol() { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CliDescriptor(String protocolVersion, String name, String version, String description,
                                List<CliToolDescriptor> tools) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CliToolDescriptor(String name, String title, String description, JsonNode inputSchema,
                                    JsonNode outputSchema, CliToolAnnotations annotations) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CliToolAnnotations(Boolean readOnlyHint, Boolean destructiveHint, Boolean idempotentHint,
                                     Boolean openWorldHint) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CliInvocationRequest(String protocolVersion, String requestId, String tool, JsonNode arguments) { }

    public sealed interface CliInvocationResponse permits CliSuccessResponse, CliFailureResponse {
        String protocolVersion();
        String requestId();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CliSuccessResponse(String protocolVersion, String requestId, boolean success, JsonNode result,
                                     String message) implements CliInvocationResponse { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CliFailureResponse(String protocolVersion, String requestId, boolean success, CliError error)
            implements CliInvocationResponse { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CliError(String code, String message, boolean retryable, JsonNode details) { }
}
