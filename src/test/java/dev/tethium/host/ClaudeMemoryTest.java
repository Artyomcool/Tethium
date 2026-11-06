package dev.tethium.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClaudeMemoryTest {
    @TempDir Path root;
    @Test void projectDirectoryIsEncodedLikeClaudeCode() {
        assertEquals("C--git-Tethium", ClaudeMemory.encode(Path.of("C:\\git\\Tethium")));
        assertTrue(ClaudeMemory.directory(Path.of("H:\\.claude"), Path.of("C:\\git\\Tethium")).endsWith(Path.of("projects", "C--git-Tethium", "memory")));
    }
    @Test void listsFactsWithFrontMatterAndMovesDeletedOnesToTrashDroppingIndexLines() throws Exception {
        Path memory = Files.createDirectories(root.resolve("memory")); Path trash = root.resolve("trash");
        Files.writeString(memory.resolve("MEMORY.md"), "- [Phone link](phone-link.md) — one transport\n- [Other](other.md) — keep\n");
        Files.writeString(memory.resolve("phone-link.md"), "---\nname: phone-link\ndescription: one transport in stone\nmetadata:\n  type: project\n---\n\nbody\n");
        Files.writeString(memory.resolve("other.md"), "---\nname: other\ndescription: keep me\n---\nbody\n");
        Files.writeString(memory.resolve("notes.txt"), "ignored");
        var listed = ClaudeMemory.list(memory);
        assertTrue(listed.path("exists").asBoolean()); assertEquals(3, listed.path("files").size());
        var phone = listed.path("files").get(2); assertEquals("phone-link.md", phone.path("name").asText()); assertEquals("one transport in stone", phone.path("description").asText());
        assertTrue(listed.path("files").get(0).path("index").asBoolean(), "MEMORY.md is flagged as the index");
        var result = ClaudeMemory.delete(memory, List.of("phone-link.md", "missing.md"), trash);
        assertEquals(1, result.path("moved").size()); assertFalse(Files.exists(memory.resolve("phone-link.md")));
        assertTrue(Files.exists(Path.of(result.path("trash").asText()).resolve("phone-link.md")), "deleted facts land in the trash");
        assertEquals("- [Other](other.md) — keep\n", Files.readString(memory.resolve("MEMORY.md")).replace("\r\n", "\n"));
        assertThrows(IllegalArgumentException.class, () -> ClaudeMemory.delete(memory, List.of("../x.md"), trash));
        assertFalse(ClaudeMemory.list(root.resolve("absent")).path("exists").asBoolean());
    }
}
