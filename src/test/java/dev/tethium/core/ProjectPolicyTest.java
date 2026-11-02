package dev.tethium.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class ProjectPolicyTest {
    @TempDir Path root;
    @Test void checkboxOnlyOwnsItsSectionAndRepositoryRemainsAuthoritative()throws Exception{
        Path file=Files.writeString(root.resolve("AGENTS.md"),"# Existing rules\nDo not delete my tests.\n");ProjectPolicy.set(root,true);assertTrue(ProjectPolicy.enabled(root));assertTrue(Files.readString(file).startsWith("# Existing rules\nDo not delete my tests."));ProjectPolicy.set(root,true);assertEquals(1,Files.readString(file).split("## GitHub synchronization",-1).length-1);ProjectPolicy.set(root,false);assertFalse(ProjectPolicy.enabled(root));assertTrue(Files.readString(file).contains("Do not delete my tests."));assertTrue(ProjectPolicy.context(root).contains("disabled"));
    }
}
