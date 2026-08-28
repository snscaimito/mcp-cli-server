package net.caimito.mcpcli.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ProtocolValidator {
    public static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    public static final Pattern ERROR_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private final ObjectMapper mapper;
    private final SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    public ProtocolValidator(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public CliProtocol.CliDescriptor descriptor(String json) throws IOException {
        JsonNode root = exactlyOneObject(json);
        CliProtocol.CliDescriptor value = mapper.treeToValue(root, CliProtocol.CliDescriptor.class);
        require("1".equals(value.protocolVersion()), "unsupported protocolVersion");
        requireName(value.name(), "CLI name");
        require(nonBlank(value.version()) && nonBlank(value.description()) && value.tools() != null && !value.tools().isEmpty(),
                "missing descriptor field");
        Set<String> names = new HashSet<>();
        for (CliProtocol.CliToolDescriptor tool : value.tools()) {
            require(tool != null, "null tool");
            requireName(tool.name(), "tool name");
            require(names.add(tool.name()), "duplicate tool name: " + tool.name());
            require(nonBlank(tool.title()) && nonBlank(tool.description()), "missing tool metadata");
            require(tool.annotations() != null && tool.annotations().readOnlyHint() != null
                            && tool.annotations().destructiveHint() != null && tool.annotations().idempotentHint() != null
                            && tool.annotations().openWorldHint() != null, "missing tool annotations");
            validateSchema(tool.inputSchema(), "inputSchema");
            validateSchema(tool.outputSchema(), "outputSchema");
        }
        return value;
    }

    public CliProtocol.CliInvocationResponse response(String json) throws IOException {
        JsonNode root = exactlyOneObject(json);
        require(root.has("success") && root.get("success").isBoolean(), "response success is required");
        CliProtocol.CliInvocationResponse response = root.get("success").booleanValue()
                ? mapper.treeToValue(root, CliProtocol.CliSuccessResponse.class)
                : mapper.treeToValue(root, CliProtocol.CliFailureResponse.class);
        require("1".equals(response.protocolVersion()) && nonBlank(response.requestId()), "invalid response envelope");
        if (response instanceof CliProtocol.CliSuccessResponse success) {
            require(success.result() != null && success.result().isObject(), "success result must be an object");
        } else {
            CliProtocol.CliError error = ((CliProtocol.CliFailureResponse) response).error();
            require(error != null && ERROR_CODE.matcher(error.code() == null ? "" : error.code()).matches()
                    && nonBlank(error.message()) && error.details() != null && error.details().isObject(), "invalid error response");
        }
        return response;
    }

    public void validateInstance(JsonNode schema, JsonNode instance, String label) {
        var errors = schemaRegistry.getSchema(schema).validate(instance);
        require(errors.isEmpty(), label + " violates schema");
    }

    public void validateSchema(JsonNode schema, String label) {
        require(schema != null && schema.isObject() && "object".equals(schema.path("type").asText()), label + " must be an object-rooted schema");
        rejectRemoteReferences(schema);
        try { schemaRegistry.getSchema(schema); }
        catch (RuntimeException ex) { throw new IllegalArgumentException(label + " is not valid JSON Schema"); }
    }

    private JsonNode exactlyOneObject(String json) throws IOException {
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            JsonNode root = mapper.readTree(parser);
            require(root != null && root.isObject() && parser.nextToken() == null, "expected exactly one JSON object");
            return root;
        }
    }

    private void rejectRemoteReferences(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if ("$ref".equals(entry.getKey()) && entry.getValue().isTextual() && !entry.getValue().asText().startsWith("#")) {
                    throw new IllegalArgumentException("remote schema reference is not allowed");
                }
                rejectRemoteReferences(entry.getValue());
            });
        } else if (node.isArray()) node.forEach(this::rejectRemoteReferences);
    }

    public static void requireName(String name, String label) { require(NAME.matcher(name == null ? "" : name).matches(), "invalid " + label); }
    public static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
}
