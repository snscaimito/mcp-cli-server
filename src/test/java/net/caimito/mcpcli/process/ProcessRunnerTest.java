package net.caimito.mcpcli.process;

import net.caimito.mcpcli.config.CliProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessRunnerTest {
    @TempDir Path directory;

    @Test
    void sendsOnlyProtocolInputAndBoundsNoisyOutput() throws Exception {
        Path executable = directory.resolve("fixture");
        Files.writeString(executable, "#!/bin/sh\nif [ \"$2\" = describe ]; then printf '{\\\"protocolVersion\\\":\\\"1\\\"}'; else cat; fi\n");
        executable.toFile().setExecutable(true);
        try (ProcessRunner runner = new ProcessRunner(new CliProperties(directory, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofMillis(10), Duration.ofSeconds(1), 32, 32, 1, List.of("LANG")))) {
            var described = runner.describe(executable);
            assertThat(described.success()).isTrue();
            assertThat(described.stdoutText()).contains("protocolVersion");
            var invoked = runner.invoke(executable, "{\"tool\":\"find\"}");
            assertThat(invoked.success()).isTrue();
            assertThat(invoked.stdoutText()).isEqualTo("{\"tool\":\"find\"}");
        }
    }

    @Test
    void returnsStableCodeForExcessiveOutput() throws Exception {
        Path executable = directory.resolve("noisy");
        Files.writeString(executable, "#!/bin/sh\nprintf 'this-output-exceeds-the-limit'\n"); executable.toFile().setExecutable(true);
        try (ProcessRunner runner = new ProcessRunner(new CliProperties(directory, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofMillis(10), Duration.ofSeconds(1), 8, 8, 1, List.of()))) {
            assertThat(runner.describe(executable).failureCode()).isEqualTo("CLI_OUTPUT_TOO_LARGE");
        }
    }
}
