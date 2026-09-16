package net.caimito.mcpcli.download;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = {"mcp.download.file", "mcp.download.path"})
public class BridgeDownloadController {
    private final Path file;

    public BridgeDownloadController(@Value("${mcp.download.file}") String file) {
        this.file = Path.of(file);
        if (!this.file.isAbsolute()) throw new IllegalArgumentException("mcp.download.file must be absolute");
    }

    @GetMapping("${mcp.download.path}")
    public ResponseEntity<Resource> download() {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getFileName().toString(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file));
    }
}
