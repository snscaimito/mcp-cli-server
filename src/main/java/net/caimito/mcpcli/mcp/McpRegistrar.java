package net.caimito.mcpcli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.caimito.mcpcli.registry.CliRegistry;
import net.caimito.mcpcli.registry.CliRegistry.RegisteredTool;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class McpRegistrar {
    private final McpSyncServer server;
    private final McpInvocationService invocations;
    private final JacksonMcpJsonMapper jsonMapper;
    private Map<String, RegisteredTool> registered = Map.of();

    public McpRegistrar(McpSyncServer server, McpInvocationService invocations, ObjectMapper objectMapper) {
        this.server = server; this.invocations = invocations; this.jsonMapper = new JacksonMcpJsonMapper(objectMapper);
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
