package net.caimito.mcpcli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.caimito.mcpcli.registry.CliRegistry;
import net.caimito.mcpcli.registry.CliRegistry.RegisteredTool;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;

@Component
public class McpRegistrar {
    private static final String CLI_BUILDER_SPECIFICATION_URI = "mcp-cli-server://specifications/cli-builder/v1";
    private static final String CLI_BUILDER_SPECIFICATION_MIME_TYPE = "text/markdown";
    private final McpSyncServer server;
    private final McpInvocationService invocations;
    private final JacksonMcpJsonMapper jsonMapper;
    private Map<String, RegisteredTool> registered = Map.of();

    public McpRegistrar(McpSyncServer server, McpInvocationService invocations, ObjectMapper objectMapper) {
        this.server = server; this.invocations = invocations; this.jsonMapper = new JacksonMcpJsonMapper(objectMapper);
        registerCliBuilderSpecification();
    }

    private void registerCliBuilderSpecification() {
        String specification = readCliBuilderSpecification();
        McpSchema.Resource resource = McpSchema.Resource.builder()
                .uri(CLI_BUILDER_SPECIFICATION_URI)
                .name("CLI Builder Specification")
                .title("CLI Builder Specification v1")
                .description("Canonical protocol specification for CLIs discovered by MCP CLI Server.")
                .mimeType(CLI_BUILDER_SPECIFICATION_MIME_TYPE)
                .size((long) specification.getBytes(StandardCharsets.UTF_8).length)
                .build();
        server.addResource(new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) ->
                new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents(
                        CLI_BUILDER_SPECIFICATION_URI, CLI_BUILDER_SPECIFICATION_MIME_TYPE, specification)))));
    }

    private String readCliBuilderSpecification() {
        try (var stream = new ClassPathResource("specifications/CLI_BUILDER_SPEC.md").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("CLI Builder Specification resource is unavailable", exception);
        }
    }

    public synchronized void publish(CliRegistry.Snapshot snapshot) {
        Map<String, RegisteredTool> next = snapshot.tools();
        if (registered.equals(next)) return;
        for (String name : registered.keySet()) {
            if (!next.containsKey(name) || !registered.get(name).equals(next.get(name))) server.removeTool(name);
        }
        for (String name : next.keySet()) {
            if (!registered.containsKey(name) || !registered.get(name).equals(next.get(name))) server.addTool(specification(next.get(name)));
        }
        registered = Map.copyOf(next);
        server.notifyToolsListChanged();
    }

    private McpServerFeatures.SyncToolSpecification specification(RegisteredTool registeredTool) {
        var descriptor = registeredTool.tool();
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(registeredTool.globalName()).title(descriptor.title()).description(descriptor.description())
                .inputSchema(jsonMapper, descriptor.inputSchema().toString())
                .outputSchema(jsonMapper, descriptor.outputSchema().toString())
                .annotations(new McpSchema.ToolAnnotations(descriptor.title(), descriptor.annotations().readOnlyHint(),
                        descriptor.annotations().destructiveHint(), descriptor.annotations().idempotentHint(),
                        descriptor.annotations().openWorldHint(), false))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> invocations.call(request.name(), request.arguments())).build();
    }
}
