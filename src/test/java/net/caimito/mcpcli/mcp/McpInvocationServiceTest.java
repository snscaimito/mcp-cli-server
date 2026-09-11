package net.caimito.mcpcli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.caimito.mcpcli.config.CliProperties;
import net.caimito.mcpcli.process.ProcessRunner;
import net.caimito.mcpcli.protocol.CliProtocol;
import net.caimito.mcpcli.protocol.ProtocolValidator;
import net.caimito.mcpcli.registry.CliRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpInvocationServiceTest {
    @TempDir Path cliDirectory;

    private final ObjectMapper mapper = new ObjectMapper();
    private ProcessRunner runner;
    private McpInvocationService invocations;
    private Path executable;

    @BeforeEach
    void setUp() throws Exception {
        cliDirectory = cliDirectory.toRealPath();
        executable = cliDirectory.resolve("fixture-cli");
        Files.writeString(executable, "fixture");
        executable.toFile().setExecutable(true);
        runner = mock(ProcessRunner.class);

        CliRegistry registry = new CliRegistry();
        JsonNode outputSchema = mapper.readTree("""
                {"type":"object","additionalProperties":true}
                """);
        registry.replace(List.of(contribution(outputSchema)));
        invocations = new McpInvocationService(registry, runner, new ProtocolValidator(mapper), mapper,
                properties(), new SimpleMeterRegistry());
    }

    @Test
    void includesResultJsonBeforeTheSuccessMessageAndKeepsStructuredContent() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"organizations":[{"id":"organization-1","name":"Acme"}]}
                """);
        successfulCliResponse(payload, "Listed organizations available to the configured Workyard API token.");

        McpSchema.CallToolResult result = invocations.call("fixture_list", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(textContents(result)).containsExactly(
                "{\"organizations\":[{\"id\":\"organization-1\",\"name\":\"Acme\"}]}",
                "Listed organizations available to the configured Workyard API token.");
        assertTextJsonMatchesStructuredContent(result);
    }

    @Test
    void omitsMissingAndBlankMessagesWithoutOmittingResultJson() throws Exception {
        JsonNode payload = mapper.readTree("{" + "\"items\":[1]}");
        for (String message : Arrays.asList(null, "", "   ")) {
            successfulCliResponse(payload, message);

            McpSchema.CallToolResult result = invocations.call("fixture_list", Map.of());

            assertThat(textContents(result)).hasSize(1);
            assertTextJsonMatchesStructuredContent(result);
        }
    }

    @Test
    void preservesEmptyArraysAndNestedObjectsInTextJson() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"organizations":[],"pagination":{"next":null,"metadata":{"total":0}}}
                """);
        successfulCliResponse(payload, null);

        McpSchema.CallToolResult result = invocations.call("fixture_list", Map.of());

        assertTextJsonMatchesStructuredContent(result);
        assertThat(mapper.readTree(textContents(result).getFirst()).path("organizations")).isEmpty();
        assertThat(mapper.readTree(textContents(result).getFirst()).path("pagination").path("metadata").path("total").asInt()).isZero();
    }

    @Test
    void exposesAuthorizationFieldsToTextOnlyConsumers() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"verification_uri":"https://example.test/verify","user_code":"test-user-code","authorization_id":"test-authorization-id"}
                """);
        successfulCliResponse(payload, "Authorize the connection in your browser.");

        McpSchema.CallToolResult result = invocations.call("fixture_list", Map.of());

        JsonNode textJson = mapper.readTree(textContents(result).getFirst());
        assertThat(textJson.path("verification_uri").asText()).isEqualTo("https://example.test/verify");
        assertThat(textJson.path("user_code").asText()).isEqualTo("test-user-code");
        assertThat(textJson.path("authorization_id").asText()).isEqualTo("test-authorization-id");
        assertTextJsonMatchesStructuredContent(result);
    }

    @Test
    void preservesSchemaViolationAndCliFailureErrors() throws Exception {
        CliRegistry invalidRegistry = new CliRegistry();
        invalidRegistry.replace(List.of(contribution(mapper.readTree("""
                {"type":"object","required":["id"],"properties":{"id":{"type":"string"}},"additionalProperties":false}
                """))));
        McpInvocationService invalidInvocations = new McpInvocationService(invalidRegistry, runner, new ProtocolValidator(mapper), mapper,
                properties(), new SimpleMeterRegistry());
        successfulCliResponse(mapper.readTree("{}"), null);

        McpSchema.CallToolResult schemaError = invalidInvocations.call("fixture_list", Map.of());

        assertThat(schemaError.isError()).isTrue();
        assertThat(textContents(schemaError)).containsExactly("CLI_RESULT_SCHEMA_VIOLATION: The CLI returned a result outside its declared schema.");
        assertThat(schemaError.structuredContent()).isNull();

        when(runner.invoke(eq(executable), anyString())).thenReturn(new ProcessRunner.ProcessResult(
                false, "CLI_EXITED_NONZERO", new byte[0], new byte[0], Duration.ZERO));

        McpSchema.CallToolResult cliError = invocations.call("fixture_list", Map.of());

        assertThat(cliError.isError()).isTrue();
        assertThat(textContents(cliError)).containsExactly("CLI_EXITED_NONZERO: The CLI process exited before completing the request.");
        assertThat(cliError.structuredContent()).isNull();
    }

    private CliRegistry.CliContribution contribution(JsonNode outputSchema) {
        CliProtocol.CliToolDescriptor tool = new CliProtocol.CliToolDescriptor("list", "List", "Lists fixture data",
                mapper.createObjectNode().put("type", "object"), outputSchema,
                new CliProtocol.CliToolAnnotations(true, false, true, false));
        return new CliRegistry.CliContribution(executable, new CliRegistry.Fingerprint(null, 0, 0),
                new CliProtocol.CliDescriptor("1", "fixture", "1.0", "Fixture CLI", List.of(tool)));
    }

    private CliProperties properties() {
        return new CliProperties(cliDirectory, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(10),
                Duration.ofSeconds(1), 1024, 1024, 1, List.of());
    }

    private void successfulCliResponse(JsonNode payload, String message) throws Exception {
        when(runner.invoke(eq(executable), anyString())).thenAnswer(invocation -> {
            JsonNode request = mapper.readTree(invocation.getArgument(1, String.class));
            ObjectNode response = mapper.createObjectNode().put("protocolVersion", "1")
                    .put("requestId", request.path("requestId").asText()).put("success", true);
            response.set("result", payload);
            if (message != null) response.put("message", message);
            return new ProcessRunner.ProcessResult(true, null, mapper.writeValueAsBytes(response), new byte[0], Duration.ZERO);
        });
    }

    private List<String> textContents(McpSchema.CallToolResult result) {
        return result.content().stream().map(McpSchema.TextContent.class::cast).map(McpSchema.TextContent::text).toList();
    }

    private void assertTextJsonMatchesStructuredContent(McpSchema.CallToolResult result) throws Exception {
        assertThat(mapper.readTree(textContents(result).getFirst())).isEqualTo(mapper.valueToTree(result.structuredContent()));
    }
}
