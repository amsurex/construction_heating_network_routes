package ru.lct.heat.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.validation.*;
import java.io.*;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class GeoJsonReaderTest {
    static final ObjectMapper JSON = new ObjectMapper();
    private final GeoJsonReader reader = new GeoJsonReader(new InputValidator());

    public static ObjectNode fixture() {
        ObjectNode root = JSON.createObjectNode(); root.put("type", "FeatureCollection");
        ArrayNode features = root.putArray("features");
        feature(features, "source", "source", "Point", "[39,55]");
        feature(features, "old", "heat_network", "LineString", "[[39,55],[39.001,55]]").put("diameter", 300);
        feature(features, "target", "oks_connection_point", "Point", "[39,55.001]").put("flow_tph", 20);
        return root;
    }

    public static ObjectNode feature(ArrayNode features, String id, String type, String geometry, String coordinates) {
        try {
            ObjectNode f = features.addObject(); f.put("type", "Feature");
            ObjectNode p = f.putObject("properties"); p.put("id", id); p.put("object_type", type);
            ObjectNode g = f.putObject("geometry"); g.put("type", geometry); g.set("coordinates", JSON.readTree(coordinates));
            return p;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    InputModel read(ObjectNode root) throws IOException {
        return reader.read(new ByteArrayInputStream(JSON.writeValueAsBytes(root))).getInput();
    }

    @Test void spec1PreservesStringAndArbitraryPrecisionNumericIdsAndIgnoresCrs() throws Exception {
        ObjectNode root = fixture();
        ObjectNode p = (ObjectNode) root.path("features").get(2).path("properties");
        p.put("id", new BigInteger("123456789012345678901234567890"));
        root.put("crs", "ignored"); root.put("name", "ignored");
        InputModel input = read(root);
        assertEquals("source", input.getSources().get(0).getId());
        assertEquals(new BigInteger("123456789012345678901234567890"), input.getConnectionPoints().get(0).getId());
        assertEquals(500000, input.getSources().get(0).getGeometry().getX(), 0.01); // §1 EPSG:32637.
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingId", "booleanId", "duplicateId", "missingFlow", "negativeFlow", "stringFlow",
            "unknownType", "wrongGeometry", "missingDiameter", "invalidDiameter", "nullGeometry", "invalidCoordinate",
            "missingSource", "missingNetwork", "missingTargets", "emptyFeatures", "wrongRoot"})
    void spec1ReportsInvalidInputs(String mutation) {
        ObjectNode root = fixture(); ArrayNode fs = (ArrayNode) root.get("features");
        ObjectNode point = (ObjectNode) fs.get(2); ObjectNode props = (ObjectNode) point.get("properties");
        switch (mutation) {
            case "missingId": props.remove("id"); break;
            case "booleanId": props.put("id", true); break;
            case "duplicateId": props.put("id", "source"); break;
            case "missingFlow": props.remove("flow_tph"); break;
            case "negativeFlow": props.put("flow_tph", -1); break;
            case "stringFlow": props.put("flow_tph", "20"); break;
            case "unknownType": props.put("object_type", "unknown"); break;
            case "wrongGeometry": props.put("object_type", "heat_network"); props.put("diameter", 300); break;
            case "missingDiameter": ((ObjectNode) fs.get(1).get("properties")).remove("diameter"); break;
            case "invalidDiameter": ((ObjectNode) fs.get(1).get("properties")).put("diameter", 301); break;
            case "nullGeometry": point.putNull("geometry"); break;
            case "invalidCoordinate": ((ArrayNode) point.get("geometry").get("coordinates")).set(1, DoubleNode.valueOf(100)); break;
            case "missingSource": fs.remove(0); break;
            case "missingNetwork": fs.remove(1); break;
            case "missingTargets": fs.remove(2); break;
            case "emptyFeatures": fs.removeAll(); break;
            default: root.put("type", "Point");
        }
        HeatRoutingException error = assertThrows(HeatRoutingException.class, () -> read(root));
        assertEquals("INVALID_INPUT", error.getCode()); assertFalse(error.getDiagnostics().isEmpty());
    }

    @Test void spec1RejectsSelfIntersectingPolygonAndReportsId() {
        ObjectNode root = fixture();
        feature((ArrayNode) root.get("features"), "bowtie", "restriction", "Polygon",
                "[[[39,55],[39.01,55.01],[39,55.01],[39.01,55],[39,55]]]").put("restriction_type", "park");
        HeatRoutingException error = assertThrows(HeatRoutingException.class, () -> read(root));
        assertEquals("bowtie", error.getDiagnostics().get(0).getObjectId());
        assertEquals("geometry", error.getDiagnostics().get(0).getField());
    }

    @Test void spec1AcceptsHolesAndMultiGeometriesAndWarnsUnknownRestrictions() throws Exception {
        ObjectNode root = fixture(); ArrayNode fs = (ArrayNode) root.get("features");
        feature(fs, "multi", "restriction", "MultiLineString", "[[[39,55],[39.001,55]],[[39,55.01],[39.001,55.01]]]")
                .put("restriction_type", "future_type");
        feature(fs, "polygon", "restriction", "MultiPolygon",
                "[[[[39,55],[39.1,55],[39.1,55.1],[39,55.1],[39,55]],"
                + "[[39.01,55.01],[39.02,55.01],[39.02,55.02],[39.01,55.02],[39.01,55.01]]]]")
                .put("restriction_type", "oks");
        GeoJsonReader.Result result = reader.read(new ByteArrayInputStream(JSON.writeValueAsBytes(root)));
        assertEquals(2, result.getInput().getRestrictions().size());
        assertEquals("WARNING", result.getDiagnostics().get(0).getSeverity());
    }

    @ParameterizedTest @ValueSource(strings = {"{", "[]", "{\"type\":\"FeatureCollection\"}",
            "{\"type\":\"FeatureCollection\",\"features\":[null]}",
            "{\"type\":\"FeatureCollection\",\"features\":[],\"features\":[]}",
            "{\"type\":\"FeatureCollection\",\"features\":[]} {}"})
    void spec1RejectsMalformedOrAmbiguousJson(String value) {
        assertThrows(HeatRoutingException.class, () -> reader.read(new ByteArrayInputStream(value.getBytes())));
    }

    @Test void spec1SkipsLargeUnknownPropertiesWithoutReadingWholeFile() throws Exception {
        // Поток не поддерживает readAllBytes; 32 МБ неизвестного атрибута не становятся JsonNode/String.
        byte[] prefix = "{\"ignored\":\"".getBytes();
        byte[] suffix = ("\"," + JSON.writeValueAsString(fixture()).substring(1)).getBytes();
        InputStream repeating = new InputStream() {
            int left = 32 * 1024 * 1024;
            public int read() { return left-- > 0 ? 'x' : -1; }
            public int read(byte[] b, int offset, int size) {
                if (left <= 0) { return -1; }
                int n = Math.min(size, left); java.util.Arrays.fill(b, offset, offset + n, (byte) 'x'); left -= n; return n;
            }
        };
        InputStream input = new SequenceInputStream(java.util.Collections.enumeration(java.util.List.of(
                new ByteArrayInputStream(prefix), repeating, new ByteArrayInputStream(suffix))));
        assertEquals(1, reader.read(input).getInput().getConnectionPoints().size());
    }
}
