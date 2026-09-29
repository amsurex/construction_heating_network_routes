package ru.lct.heat.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebUiResourcesTest {

    @Test
    void integratedUiIsPackagedWithItsOfflineDependencies() throws IOException {
        String html = resource("/static/index.html");
        String css = resource("/static/app.css");
        String javascript = resource("/static/app.js");
        String leaflet = resource("/static/vendor/leaflet/leaflet.js");
        byte[] logo = binaryResource("/static/assets/krot-mascot.png");

        assertTrue(html.contains("id=\"source-file\""));
        assertTrue(html.contains("id=\"map\""));
        assertTrue(html.contains("Комплекс расчёта оптимальных трасс"));
        assertTrue(html.contains("/assets/krot-mascot.png"));
        assertTrue(html.contains("/swagger-ui/index.html"));
        assertTrue(css.contains(".workspace"));
        assertTrue(javascript.contains("xhr.open('POST', `/api/v1/jobs?"));
        assertTrue(javascript.contains("/validation"));
        assertTrue(leaflet.contains("Leaflet"));
        assertTrue(logo.length > 10_000, "Logo asset must not be empty or a placeholder");
    }

    private String resource(String path) throws IOException {
        return new String(binaryResource(path), StandardCharsets.UTF_8);
    }

    private byte[] binaryResource(String path) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, "Classpath resource is missing: " + path);
            return stream.readAllBytes();
        }
    }
}
