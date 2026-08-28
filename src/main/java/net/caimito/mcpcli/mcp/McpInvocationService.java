package net.caimito.mcpcli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.caimito.mcpcli.process.ProcessRunner;
import net.caimito.mcpcli.protocol.CliProtocol;
import net.caimito.mcpcli.protocol.ProtocolValidator;
import net.caimito.mcpcli.registry.CliRegistry;
import net.caimito.mcpcli.registry.CliRegistry.RegisteredTool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class McpInvocationService {
    private static final Logger log = LoggerFactory.getLogger(McpInvocationService.class);
    private final CliRegistry registry; private final ProcessRunner runner; private final ProtocolValidator validator;
    private final ObjectMapper mapper; private final Semaphore permits; private final Counter rejections;
    private final net.caimito.mcpcli.config.CliProperties properties;

    public McpInvocationService(CliRegistry registry, ProcessRunner runner, ProtocolValidator validator, ObjectMapper mapper,
                                net.caimito.mcpcli.config.CliProperties properties, MeterRegistry metrics) {
        this.registry = registry; this.runner = runner; this.validator = validator; this.mapper = mapper; this.properties = properties;
        this.permits = new Semaphore(properties.maxConcurrentInvocations(), true);
        this.rejections = metrics.counter("mcp_cli_invocation_rejections_total");
    }

    public McpSchema.CallToolResult call(String globalName, Map<String, Object> arguments) {
        RegisteredTool tool = registry.current().tools().get(globalName);
        if (tool == null) return error("CLI_UNAVAILABLE", "The requested CLI tool is no longer available.");
        JsonNode args = mapper.valueToTree(arguments == null ? Map.of() : arguments);
        try { validator.validateInstance(tool.tool().inputSchema(), args, "arguments"); }
        catch (RuntimeException ex) { return error("INVALID_ARGUMENTS", "Arguments do not satisfy the tool schema."); }
        try {
            if (!permits.tryAcquire(1, TimeUnit.SECONDS)) { rejections.increment(); return error("CLI_BUSY", "The CLI server is busy; retry shortly."); }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return error("CLI_CANCELLED", "The invocation was cancelled."); }
        try {
            if (!trusted(tool.cli().executable())) return error("CLI_UNAVAILABLE", "The CLI executable is no longer available.");
            String requestId = UUID.randomUUID().toString();
            String request = mapper.writeValueAsString(new CliProtocol.CliInvocationRequest("1", requestId, tool.tool().name(), args));
            log.debug("Invoking CLI tool [requestId={}, cli={}, tool={}]", requestId, tool.cli().descriptor().name(), tool.tool().name());
            ProcessRunner.ProcessResult process = runner.invoke(tool.cli().executable(), request);
            log.debug("CLI invocation completed [requestId={}, cli={}, tool={}, outcome={}, duration={}]", requestId,
                    tool.cli().descriptor().name(), tool.tool().name(), process.success() ? "success" : process.failureCode(), process.duration());
            if (!process.success()) return error(process.failureCode(), bridgeMessage(process.failureCode()));
            CliProtocol.CliInvocationResponse response = validator.response(process.stdoutText());
            if (!requestId.equals(response.requestId())) return error("CLI_RESPONSE_MISMATCH", "The CLI returned a response for a different request.");
            if (response instanceof CliProtocol.CliSuccessResponse success) {
                try { validator.validateInstance(tool.tool().outputSchema(), success.result(), "result"); }
                catch (RuntimeException ex) { return error("CLI_RESULT_SCHEMA_VIOLATION", "The CLI returned a result outside its declared schema."); }
                String text = success.message() == null || success.message().isBlank() ? mapper.writeValueAsString(success.result()) : success.message();
                return McpSchema.CallToolResult.builder().addTextContent(text).structuredContent(mapper.convertValue(success.result(), Object.class)).isError(false).build();
            }
            CliProtocol.CliError failure = ((CliProtocol.CliFailureResponse) response).error();
            return McpSchema.CallToolResult.builder().addTextContent(failure.code() + ": " + failure.message()).isError(true).build();
        } catch (Exception ex) { return error("CLI_INVALID_RESPONSE", "The CLI returned an invalid protocol response."); }
        finally { permits.release(); }
    }

    private boolean trusted(Path executable) {
        try {
            Path configuredDirectory = properties.directory().toRealPath();
            Path real = executable.toRealPath();
            return real.equals(executable) && real.getParent().equals(configuredDirectory)
                    && Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(executable) && Files.isExecutable(executable);
        }
        catch (SecurityException ex) { return false; }
        catch (java.io.IOException ex) { return false; }
    }
    private static McpSchema.CallToolResult error(String code, String message) { return McpSchema.CallToolResult.builder().addTextContent(code + ": " + message).isError(true).build(); }
    private static String bridgeMessage(String code) { return switch (code) {
        case "CLI_TIMEOUT" -> "The CLI did not respond before the deadline.";
        case "CLI_OUTPUT_TOO_LARGE" -> "The CLI produced more output than the configured limit.";
        case "CLI_EXITED_NONZERO" -> "The CLI process exited before completing the request.";
        case "CLI_CANCELLED" -> "The invocation was cancelled.";
        default -> "The CLI executable could not be started.";
    }; }
}
