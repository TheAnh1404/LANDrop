package com.landrop.ui.cloud.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class ApiPaths {
    private ApiPaths() { }

    static String segment(String id) {
        if (id == null || id.isBlank() || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException("id must be a nonblank opaque identifier, not a dot segment");
        }
        return encode(id);
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
