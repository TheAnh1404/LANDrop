package com.landrop.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentFilesTest {
    @TempDir Path directory;

    @Test void localOverridesBaseAndParentIsFoundFromBackendDirectory() throws Exception {
        Files.writeString(directory.resolve(".env"), "DATABASE_URL=base\nJWT_ACCESS_SECRET=base-secret\n");
        Files.writeString(directory.resolve(".env.local"), "# local\nDATABASE_URL='local?options=a=b'\n");
        var backend = Files.createDirectory(directory.resolve("backend"));
        assertEquals("local?options=a=b", EnvironmentFiles.read("DATABASE_URL", backend));
        assertEquals("base-secret", EnvironmentFiles.read("JWT_ACCESS_SECRET", backend));
    }

    @Test void lastDefinitionAndQuotedSpecialCharactersArePreserved() throws Exception {
        Files.writeString(directory.resolve(".env.local"), "KEY=first\nKEY=\"last#value=with spaces\"\nEMPTY=''\n");
        assertEquals("last#value=with spaces", EnvironmentFiles.read("KEY", directory));
        assertEquals("", EnvironmentFiles.read("EMPTY", directory));
        assertNull(EnvironmentFiles.read("MISSING", directory));
    }
}
