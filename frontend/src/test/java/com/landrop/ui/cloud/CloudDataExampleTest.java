package com.landrop.ui.cloud;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CloudDataExampleTest {
    @Test
    void rendersAllMockEntitiesThroughThePublicClientWithoutLanOrJavafx() {
        var output = new ByteArrayOutputStream();
        CloudDataExample.renderMock(new PrintStream(output, true, StandardCharsets.UTF_8));
        String rendered = output.toString(StandardCharsets.UTF_8);
        System.out.print(rendered);
        assertEquals(1, rendered.lines().filter(line -> line.startsWith("Workspace: ")).count());
        assertEquals(3, rendered.lines().filter(line -> line.startsWith("Member: ")).count());
        assertEquals(3, rendered.lines().filter(line -> line.startsWith("Channel: ")).count());
        assertEquals(4, rendered.lines().filter(line -> line.startsWith("Message: ")).count());
        assertEquals(2, rendered.lines().filter(line -> line.startsWith("Folder: ")).count());
        assertEquals(3, rendered.lines().filter(line -> line.startsWith("File: ")).count());
        assertFalse(rendered.contains("null"));
    }
}
