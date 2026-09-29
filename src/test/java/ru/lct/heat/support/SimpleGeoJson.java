package ru.lct.heat.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ExistingChamber;
import ru.lct.heat.model.ExistingPipe;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.ObjectType;
import ru.lct.heat.model.Restriction;
import ru.lct.heat.model.SourceNode;
import ru.lct.heat.model.out.NewChamber;
import ru.lct.heat.model.out.NewPipe;
import ru.lct.heat.model.out.TechnicalNode;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.model.out.VariantSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Простой (не потоковый) GeoJSON-ввод/вывод для тестов и dev-прогонов ядра.
 * Боевая потоковая реализация — в пакете io (Вадим).
 */
public final class SimpleGeoJson {

    private static final ObjectMapper M = new ObjectMapper();

    private SimpleGeoJson() {
    }

    public static InputModel read(Path file) throws IOException {
        JsonNode root = M.readTree(Files.readAllBytes(file));
        InputModel.InputModelBuilder b = InputModel.builder();
        for (JsonNode f : root.get("features")) {
            JsonNode p = f.get("properties");
            Object id = idValue(p.get("id"));
            ObjectType t = ObjectType.fromCode(p.path("object_type").asText());
            Geometry g = Crs.toUtm(geometry(f.get("geometry"), Crs.WGS));
            if (t == null) {
                continue;
            }
            switch (t) {
                case SOURCE:
                    b.source(new SourceNode(id, (Point) g));
                    break;
                case HEAT_NETWORK:
                    b.pipe(new ExistingPipe(id, (LineString) g, p.get("diameter").asInt()));
                    break;
                case HEAT_CHAMBER:
                    b.chamber(new ExistingChamber(id, (Point) g));
                    break;
                case OKS_CONNECTION_POINT:
                    b.connectionPoint(new ConnectionPoint(id, (Point) g, p.get("flow_tph").asDouble()));
                    break;
                case RESTRICTION:
                    b.restriction(new Restriction(id, g, p.path("restriction_type").asText()));
                    break;
                default:
                    break;
            }
        }
        return b.build();
    }

    static Object idValue(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        return n.asText();
    }

    static Geometry geometry(JsonNode g, org.locationtech.jts.geom.GeometryFactory gf) {
        String type = g.get("type").asText();
        JsonNode c = g.get("coordinates");
        switch (type) {
            case "Point":
                return gf.createPoint(coord(c));
            case "LineString":
                return gf.createLineString(coords(c));
            case "MultiLineString": {
                LineString[] ls = new LineString[c.size()];
                for (int i = 0; i < c.size(); i++) {
                    ls[i] = gf.createLineString(coords(c.get(i)));
                }
                return gf.createMultiLineString(ls);
            }
            case "Polygon":
                return polygon(c, gf);
            case "MultiPolygon": {
                Polygon[] ps = new Polygon[c.size()];
                for (int i = 0; i < c.size(); i++) {
                    ps[i] = polygon(c.get(i), gf);
                }
                return gf.createMultiPolygon(ps);
            }
            default:
                throw new IllegalArgumentException("Unsupported geometry " + type);
        }
    }

    private static Polygon polygon(JsonNode rings, org.locationtech.jts.geom.GeometryFactory gf) {
        LinearRing shell = gf.createLinearRing(coords(rings.get(0)));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = gf.createLinearRing(coords(rings.get(i)));
        }
        return gf.createPolygon(shell, holes);
    }

    private static Coordinate coord(JsonNode c) {
        return new Coordinate(c.get(0).asDouble(), c.get(1).asDouble());
    }

    private static Coordinate[] coords(JsonNode arr) {
        Coordinate[] out = new Coordinate[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            out[i] = coord(arr.get(i));
        }
        return out;
    }

    // ---------- write ----------

    public static void write(List<Variant> variants, Path file) throws IOException {
        ObjectNode root = M.createObjectNode();
        root.put("type", "FeatureCollection");
        ArrayNode features = root.putArray("features");
        for (Variant v : variants) {
            for (NewPipe p : v.getPipes()) {
                ObjectNode f = feature(features, p.getId(), ObjectType.HEAT_NETWORK, v.getVariantId(), p.getGeometry());
                ObjectNode pr = (ObjectNode) f.get("properties");
                putId(pr, "start_node_id", p.getStartNodeId());
                putId(pr, "end_node_id", p.getEndNodeId());
                pr.put("flow_tph", p.getFlowTph());
                pr.put("diameter", p.getDiameter());
                pr.put("length", p.getLength());
                pr.put("laying_method", p.getLayingMethod().code());
                if (p.getDepthStart() == null) {
                    pr.putNull("depth_start");
                    pr.putNull("depth_end");
                } else {
                    pr.put("depth_start", p.getDepthStart());
                    pr.put("depth_end", p.getDepthEnd());
                }
                pr.put("cost", p.getCost());
                pr.put("k_special", p.getKSpecial());
                if (p.getCrossedRestrictionTypes() != null) {
                    pr.put("crossed", p.getCrossedRestrictionTypes());
                }
                putExtra(pr, p.getExtra());
            }
            for (NewChamber c : v.getChambers()) {
                ObjectNode f = feature(features, c.getId(), ObjectType.HEAT_CHAMBER, v.getVariantId(), c.getGeometry());
                ObjectNode pr = (ObjectNode) f.get("properties");
                pr.put("diameter", c.getDiameter());
                pr.put("cost", c.getCost());
                if (c.getTieInPipeId() != null) {
                    putId(pr, "tie_in_pipe_id", c.getTieInPipeId());
                }
                putExtra(pr, c.getExtra());
            }
            for (TechnicalNode n : v.getTechnicalNodes()) {
                feature(features, n.getId(), ObjectType.TECHNICAL_NODE, v.getVariantId(), n.getGeometry());
            }
            VariantSummary s = v.getSummary();
            ObjectNode f = feature(features, s.getId(), ObjectType.VARIANT_SUMMARY, v.getVariantId(), null);
            ObjectNode pr = (ObjectNode) f.get("properties");
            pr.put("rank", s.getRank());
            pr.put("construction_cost", s.getConstructionCost());
            pr.put("chamber_construction_cost", s.getChamberConstructionCost());
            pr.put("existing_chamber_tie_in_count", s.getExistingChamberTieInCount());
            pr.put("existing_chamber_tie_in_cost", s.getExistingChamberTieInCost());
            pr.put("unconnected_penalty", s.getUnconnectedPenalty());
            pr.put("calculated_cost", s.getCalculatedCost());
            pr.put("new_network_length", s.getNewNetworkLength());
            pr.put("score", s.getScore());
            ArrayNode un = pr.putArray("unconnected_oks_ids");
            for (Object id : s.getUnconnectedOksIds()) {
                addId(un, id);
            }
            putExtra(pr, s.getExtra());
        }
        Files.createDirectories(file.getParent());
        M.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
    }

    private static ObjectNode feature(ArrayNode features, Object id, ObjectType type, Object variantId, Geometry utm) {
        ObjectNode f = features.addObject();
        f.put("type", "Feature");
        ObjectNode pr = f.putObject("properties");
        putId(pr, "id", id);
        pr.put("object_type", type.code());
        putId(pr, "variant_id", variantId);
        if (utm == null) {
            f.putNull("geometry");
        } else {
            Geometry wgs = Crs.toWgs(utm);
            ObjectNode g = f.putObject("geometry");
            g.put("type", wgs.getGeometryType());
            ArrayNode cs = g.putArray("coordinates");
            if (wgs instanceof Point) {
                addCoord(cs, wgs.getCoordinate());
            } else {
                for (Coordinate c : wgs.getCoordinates()) {
                    addCoord(cs.addArray(), c);
                }
            }
        }
        return f;
    }

    private static void addCoord(ArrayNode arr, Coordinate c) {
        arr.add(Math.round(c.x * 1e9) / 1e9);
        arr.add(Math.round(c.y * 1e9) / 1e9);
    }

    private static void putExtra(ObjectNode n, java.util.Map<String, Object> extra) {
        if (extra == null) {
            return;
        }
        for (java.util.Map.Entry<String, Object> en : extra.entrySet()) {
            n.set(en.getKey(), M.valueToTree(en.getValue()));
        }
    }

    private static void putId(ObjectNode n, String key, Object id) {
        if (id instanceof Long || id instanceof Integer) {
            n.put(key, ((Number) id).longValue());
        } else if (id instanceof Number) {
            n.put(key, ((Number) id).doubleValue());
        } else {
            n.put(key, String.valueOf(id));
        }
    }

    private static void addId(ArrayNode arr, Object id) {
        if (id instanceof Long || id instanceof Integer) {
            arr.add(((Number) id).longValue());
        } else if (id instanceof Number) {
            arr.add(((Number) id).doubleValue());
        } else {
            arr.add(String.valueOf(id));
        }
    }

    public static List<Coordinate> coordsOf(LineString ls) {
        List<Coordinate> out = new ArrayList<>();
        for (Coordinate c : ls.getCoordinates()) {
            out.add(c);
        }
        return out;
    }
}
