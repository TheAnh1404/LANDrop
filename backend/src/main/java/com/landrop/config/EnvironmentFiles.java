package com.landrop.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared dotenv resolution for direct Java/desktop launches and the Node launcher. */
public final class EnvironmentFiles {
    private EnvironmentFiles() {}

    public static String resolve(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        if (value == null || value.isBlank()) value = read(environment, Path.of(".").toAbsolutePath().normalize());
        return value;
    }

    static String read(String key, Path directory) {
        for (int level = 0; level < 3 && directory != null; level++, directory = directory.getParent()) {
            for (String name : new String[]{".env.local", ".env"}) {
                Path file = directory.resolve(name);
                if (!Files.isRegularFile(file)) continue;
                try {
                    String found = null;
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        int separator = line.indexOf('=');
                        if (separator < 1 || !key.equals(line.substring(0, separator).trim())) continue;
                        String value = line.substring(separator + 1).trim();
                        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                                || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
                        found = value;
                    }
                    if (found != null) return found;
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read configuration file: " + file.getFileName());
                }
            }
        }
        return null;
    }
}
