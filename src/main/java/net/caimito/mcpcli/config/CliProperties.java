package net.caimito.mcpcli.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mcp.cli")
public record CliProperties(
        @NotNull Path directory,
        @NotNull Duration discoveryTimeout,
        @NotNull Duration invocationTimeout,
        @NotNull Duration changeDebounce,
        @NotNull Duration reconciliationInterval,
        @Positive long maxStdoutBytes,
        @Positive long maxStderrBytes,
        @Min(1) int maxConcurrentInvocations,
        @NotNull List<String> environmentAllowlist) {
    public CliProperties {
        if (directory == null || !directory.isAbsolute()) throw new IllegalArgumentException("mcp.cli.directory must be an absolute path");
        if (!positive(discoveryTimeout) || !positive(invocationTimeout) || !positive(changeDebounce) || !positive(reconciliationInterval)) {
            throw new IllegalArgumentException("mcp.cli durations must be positive");
        }
    }
    private static boolean positive(Duration duration) { return duration != null && !duration.isZero() && !duration.isNegative(); }
}
