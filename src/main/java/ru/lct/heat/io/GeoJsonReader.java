package ru.lct.heat.io;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Component;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.validation.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** §1: потоковый FeatureCollection; в JSON-дереве находится только одна геометрия и нужные properties. */
@Component
@RequiredArgsConstructor
public class GeoJsonReader {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Set<String> INPUT_FIELDS = Set.of("id", "object_type", "diameter", "flow_tph", "restriction_type");
    private final InputValidator validator;

    @Value
    public static class Result {
        InputModel input;
        List<Diagnostic> diagnostics;
    }

    public InputModel read(Path path) throws IOException { return readValidated(path).getInput(); }

    public Result readValidated(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) { return read(in); }
    }

    public Result read(InputStream in) throws IOException {
        InputModel.InputModelBuilder builder = InputModel.builder();
        InputValidator.Session session = validator.session();
        forEachFeature(in, INPUT_FIELDS, f -> session.accept(f, builder));
        InputModel input = builder.build();
        return new Result(input, session.finish(input));
    }

    /** Чтение feature по одному; неизвестные поля пропускаются без материализации. Поток принадлежит вызывающему. */
    public static void forEachFeature(InputStream in, Set<String> properties,
                                      java.util.function.Consumer<JsonNode> consumer) throws IOException {
        try (JsonParser p = JSON.getFactory().createParser(in)) {
            p.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
            p.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            expect(p.nextToken(), JsonToken.START_OBJECT);
            boolean features = false;
            String type = null;
            while (p.nextToken() != JsonToken.END_OBJECT) {
                expect(p.currentToken(), JsonToken.FIELD_NAME);
                String field = p.currentName();
                p.nextToken();
                if ("type".equals(field)) { type = p.getValueAsString(); }
                else if ("features".equals(field)) {
                    expect(p.currentToken(), JsonToken.START_ARRAY);
                    features = true;
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        expect(p.currentToken(), JsonToken.START_OBJECT);
                        consumer.accept(feature(p, properties));
                    }
                } else { p.skipChildren(); }
            }
            if (!features || !"FeatureCollection".equals(type) || p.nextToken() != null) {
                throw new IllegalArgumentException("Ожидается один FeatureCollection с массивом features");
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            Diagnostic d = Diagnostic.error(null, "§1", "$", "Некорректный JSON: " + e.getMessage());
            throw new HeatRoutingException("INVALID_INPUT", null, d.getMessage(), List.of(d));
        }
    }

    private static ObjectNode feature(JsonParser p, Set<String> fields) throws IOException {
        ObjectNode feature = JSON.createObjectNode();
        while (p.nextToken() != JsonToken.END_OBJECT) {
            expect(p.currentToken(), JsonToken.FIELD_NAME);
            String name = p.currentName();
            p.nextToken();
            if ("geometry".equals(name) || "type".equals(name)) { feature.set(name, JSON.readTree(p)); }
            else if ("properties".equals(name) && p.currentToken() == JsonToken.START_OBJECT) {
                ObjectNode props = feature.putObject("properties");
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    expect(p.currentToken(), JsonToken.FIELD_NAME);
                    String key = p.currentName();
                    p.nextToken();
                    if (fields.contains(key)) { props.set(key, JSON.readTree(p)); }
                    else { p.skipChildren(); }
                }
            } else { p.skipChildren(); }
        }
        return feature;
    }

    private static void expect(JsonToken actual, JsonToken expected) {
        if (actual != expected) { throw new IllegalArgumentException("Ожидался " + expected + ", получен " + actual); }
    }
}
