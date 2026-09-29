package ru.lct.heat.io;

import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.*;
import org.springframework.stereotype.Component;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.model.LayingMethod;
import ru.lct.heat.model.out.*;
import ru.lct.heat.validation.InputValidator;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** §7: повторное чтение готового файла для независимой проверки, без доверия writer/ядру. */
@Component
public class OutputGeoJsonReader {
    private static final Set<String> FIELDS = Set.of("id", "object_type", "variant_id", "start_node_id", "end_node_id",
            "flow_tph", "diameter", "length", "laying_method", "depth_start", "depth_end", "cost", "k_special", "k_depth",
            "crossed", "tie_in_pipe_id", "reason", "rank", "construction_cost", "chamber_construction_cost",
            "existing_chamber_tie_in_count", "existing_chamber_tie_in_cost", "unconnected_penalty", "calculated_cost",
            "new_network_length", "score", "unconnected_oks_ids", "description", "approach");

    public List<Variant> read(Path path) throws IOException {
        Map<Object, Variant.VariantBuilder> variants = new LinkedHashMap<>();
        Set<Object> summaries = new HashSet<>();
        Set<Object> ids = new HashSet<>();
        try (InputStream in = Files.newInputStream(path)) {
            GeoJsonReader.forEachFeature(in, FIELDS, f -> {
                require("Feature".equals(f.path("type").asText()), "Ожидается Feature");
                JsonNode p = f.path("properties");
                Object id = InputValidator.id(p.path("id"));
                require(ids.add(InputValidator.key(id)), "Повторный id результата: " + id);
                Object variantId = InputValidator.id(p.path("variant_id"));
                Object key = InputValidator.key(variantId);
                Variant.VariantBuilder v = variants.computeIfAbsent(key, k -> Variant.builder().variantId(variantId));
                String type = p.path("object_type").asText();
                Geometry geometry = null;
                if (!"variant_summary".equals(type)) {
                    geometry = Crs.toUtm(GeoJsonGeometry.read(f.path("geometry")));
                    require(!geometry.isEmpty() && geometry.isValid(), "Невалидная выходная геометрия " + id);
                }
                switch (type) {
                    case "heat_network":
                        require(geometry instanceof LineString, "heat_network требует LineString");
                        String laying = p.path("laying_method").asText();
                        require("base".equals(laying) || "special".equals(laying), "Неизвестный laying_method");
                        v.pipe(NewPipe.builder().id(id).startNodeId(InputValidator.id(p.path("start_node_id")))
                                .endNodeId(InputValidator.id(p.path("end_node_id"))).geometry((LineString) geometry)
                                .flowTph(number(p, "flow_tph")).diameter(integer(p, "diameter")).length(number(p, "length"))
                                .layingMethod("base".equals(laying) ? LayingMethod.BASE : LayingMethod.SPECIAL)
                                .depthStart(nullableNumber(p, "depth_start")).depthEnd(nullableNumber(p, "depth_end"))
                                .cost(number(p, "cost")).kSpecial(p.path("k_special").asDouble(Double.NaN))
                                .kDepth(p.path("k_depth").asDouble(Double.NaN))
                                .extra(p.has("approach") && p.get("approach").isTextual()
                                        ? Map.of("approach", p.get("approach").asText()) : Map.of())
                                .build()); break;
                    case "heat_chamber":
                        require(geometry instanceof Point, "heat_chamber требует Point");
                        v.chamber(NewChamber.builder().id(id).geometry((Point) geometry)
                                .diameter(integer(p, "diameter")).cost(number(p, "cost"))
                                .tieInPipeId(p.path("tie_in_pipe_id").isNull() || !p.has("tie_in_pipe_id") ? null
                                        : InputValidator.id(p.get("tie_in_pipe_id"))).build()); break;
                    case "technical_node":
                        require(geometry instanceof Point, "technical_node требует Point");
                        v.technicalNode(new TechnicalNode(id, (Point) geometry, p.path("reason").asText())); break;
                    case "variant_summary":
                        require(f.has("geometry") && f.get("geometry").isNull(), "Сводка требует geometry: null");
                        require(summaries.add(key), "Две сводки одного варианта");
                        require(p.path("unconnected_oks_ids").isArray(), "unconnected_oks_ids должен быть массивом");
                        List<Object> unconnected = new ArrayList<>();
                        p.get("unconnected_oks_ids").forEach(n -> unconnected.add(InputValidator.id(n)));
                        v.description(p.path("description").asText()).summary(VariantSummary.builder().id(id)
                                .rank(integer(p, "rank"))
                                .constructionCost(number(p, "construction_cost"))
                                .chamberConstructionCost(number(p, "chamber_construction_cost"))
                                .existingChamberTieInCount(integer(p, "existing_chamber_tie_in_count"))
                                .existingChamberTieInCost(number(p, "existing_chamber_tie_in_cost"))
                                .unconnectedPenalty(number(p, "unconnected_penalty"))
                                .calculatedCost(number(p, "calculated_cost"))
                                .newNetworkLength(number(p, "new_network_length"))
                                .score(number(p, "score"))
                                .unconnectedOksIds(unconnected).build()); break;
                    default: throw new IllegalArgumentException("Неизвестный выходной object_type: " + type);
                }
            });
        }
        require(!variants.isEmpty() && variants.size() <= 3, "Требуется от одного до трёх вариантов");
        require(summaries.equals(variants.keySet()), "Для каждого варианта требуется сводка");
        List<Variant> result = new ArrayList<>();
        variants.values().forEach(v -> result.add(v.build()));
        return result;
    }

    private static double number(JsonNode p, String name) {
        JsonNode n = p.path(name);
        require(n.isNumber() && Double.isFinite(n.doubleValue()), "Требуется конечное число " + name);
        return n.doubleValue();
    }
    private static int integer(JsonNode p, String name) {
        require(p.path(name).isIntegralNumber() && p.path(name).canConvertToInt(), "Требуется целое " + name);
        return p.get(name).intValue();
    }
    private static Double nullableNumber(JsonNode p, String name) {
        require(p.has(name), "Отсутствует обязательное поле " + name);
        return p.get(name).isNull() ? null : number(p, name);
    }
    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }
}
