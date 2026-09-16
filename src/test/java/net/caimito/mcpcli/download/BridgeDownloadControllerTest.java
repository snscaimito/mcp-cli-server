package net.caimito.mcpcli.download;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BridgeDownloadControllerTest {
    @TempDir Path directory;

    @Test void servesOnlyConfiguredFileWithInstallableFilename() throws Exception {
        Path file = directory.resolve("lan-mcp-bridge.mcpb");
        Files.writeString(file, "bundle bytes");
        var mvc = MockMvcBuilders.standaloneSetup(new BridgeDownloadController(file.toString()))
                .addPlaceholderValue("mcp.download.path", "/claude").build();
        mvc.perform(get("/claude")).andExpect(status().isOk())
                .andExpect(content().string("bundle bytes"))
                .andExpect(header().string("Content-Disposition", containsString("attachment;")))
                .andExpect(header().string("Content-Disposition", containsString("lan-mcp-bridge.mcpb")));
        mvc.perform(get("/claude/anything")).andExpect(status().isNotFound());
        Files.delete(file);
        mvc.perform(get("/claude")).andExpect(status().isNotFound());
    }
}
