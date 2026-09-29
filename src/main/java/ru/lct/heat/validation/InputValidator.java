package ru.lct.heat.validation;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.springframework.stereotype.Component;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.io.GeoJsonGeometry;
import ru.lct.heat.model.*;
import ru.lct.heat.model.ref.DiameterTable;
import ru.lct.heat.model.ref.RestrictionRules;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Component
public class InputValidator {
    /** Отдельная сессия на файл; singleton не хранит состояние загрузки. */
    public Session session() { return new Session(); }

    public static final class Session {
        private static final int MAX_DIAGNOSTICS = 200;
        private final Set<Object> ids = new HashSet<>();
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private boolean errors;

        public void accept(JsonNode feature, InputModel.InputModelBuilder builder) {
            JsonNode p = feature.path("properties");
            Object id = null;
            String field = "properties.id";
            try {
                id = id(p.path("id"));
                if (!ids.add(key(id))) { throw new IllegalArgumentException("Дублирующийся id"); }
                field = "feature";
                if (!"Feature".equals(feature.path("type").asText()) || !p.isObject()) {
                    throw new IllegalArgumentException("Ожидается Feature с properties");
                }
                field = "properties.object_type";
                String type = p.path("object_type").asText();
                field = "geometry";
                Geometry wgs = GeoJsonGeometry.read(feature.path("geometry"));
                checkGeometry(wgs);
                Geometry g = Crs.toUtm(wgs);
                checkGeometry(g);
                field = "properties.object_type";
                switch (type) {
                    case "source":
                        field = "geometry";
                        require(g instanceof Point, "source требует Point");
                        builder.source(new SourceNode(id, (Point) g)); break;
                    case "heat_chamber":
                        field = "geometry";
                        require(g instanceof Point, "heat_chamber требует Point");
                        builder.chamber(new ExistingChamber(id, (Point) g)); break;
                    case "heat_network":
                        field = "geometry";
                        require(g instanceof LineString, "heat_network требует LineString");
                        field = "properties.diameter";
                        JsonNode dn = p.path("diameter");
                        require(dn.isIntegralNumber() && dn.canConvertToInt()
                                && DiameterTable.byDiameter(dn.intValue()).isPresent(), "Неизвестный diameter");
                        builder.pipe(new ExistingPipe(id, (LineString) g, dn.intValue())); break;
                    case "oks_connection_point":
                        field = "geometry";
                        require(g instanceof Point, "oks_connection_point требует Point");
                        field = "properties.flow_tph";
                        JsonNode flow = p.path("flow_tph");
                        require(flow.isNumber() && Double.isFinite(flow.doubleValue()) && flow.doubleValue() > 0,
                                "flow_tph должен быть конечным положительным числом");
                        builder.connectionPoint(new ConnectionPoint(id, (Point) g, flow.doubleValue())); break;
                    case "restriction":
                        field = "geometry";
                        require(g instanceof LineString || g instanceof MultiLineString
                                || g instanceof Polygon || g instanceof MultiPolygon, "Недопустимая геометрия restriction");
                        field = "properties.restriction_type";
                        JsonNode rt = p.path("restriction_type");
                        require(rt.isTextual() && !rt.textValue().isBlank(), "Отсутствует restriction_type");
                        if (!RestrictionRules.isKnown(rt.textValue())) {
                            // неизвестный тип — консервативный запрет (см. «Пространственные ограничения» в docs/solution.md)
                            add(new Diagnostic("WARNING", id, "§1.2/§4", field,
                                    "Неизвестный restriction_type: запрет"));
                            if (diagnostics.size() < MAX_DIAGNOSTICS) {
                                log.warn("Неизвестный тип ограничения {} у {}", rt.textValue(), id);
                            }
                        }
                        builder.restriction(new Restriction(id, g, rt.textValue())); break;
                    default: throw new IllegalArgumentException("Неизвестный object_type: " + type);
                }
            } catch (IllegalArgumentException e) {
                add(Diagnostic.error(id, "§1.1", field, e.getMessage()));
            }
        }

        public List<Diagnostic> finish(InputModel input) {
            if (input.getSources().isEmpty()) {
                add(Diagnostic.error(null, "§1.1", "features", "Отсутствует source"));
            }
            if (input.getPipes().isEmpty()) {
                add(Diagnostic.error(null, "§1.1", "features", "Отсутствует heat_network"));
            }
            if (input.getConnectionPoints().isEmpty()) {
                add(Diagnostic.error(null, "§1.1", "features", "Отсутствует oks_connection_point"));
            }
            if (errors) {
                throw new HeatRoutingException("INVALID_INPUT", null, "Входной GeoJSON не прошёл проверку", diagnostics);
            }
            return List.copyOf(diagnostics);
        }

        private void add(Diagnostic diagnostic) {
            errors |= "ERROR".equals(diagnostic.getSeverity());
            if (diagnostics.size() < MAX_DIAGNOSTICS) { diagnostics.add(diagnostic); }
            else if (diagnostics.size() == MAX_DIAGNOSTICS) {
                diagnostics.add(new Diagnostic("WARNING", null, "§1", "Список диагностик усечён"));
            }
        }
    }

    public static Object id(JsonNode node) {
        if (node.isTextual()) { return node.textValue(); }
        if (node.isIntegralNumber()) { return node.numberValue(); }
        if (node.isNumber()) { return node.decimalValue(); }
        throw new IllegalArgumentException("id должен быть строкой или числом");
    }

    /** Числа 1/1.0 — одно значение связи; строка "1" остаётся отдельным id (§1). */
    public static Object key(Object id) {
        return id instanceof Number ? new BigDecimal(id.toString()).stripTrailingZeros() : id;
    }

    private static void checkGeometry(Geometry g) {
        require(!g.isEmpty(), "Пустая геометрия");
        for (Coordinate c : g.getCoordinates()) {
            require(Double.isFinite(c.x) && Double.isFinite(c.y), "Неопределённая координата после проекции");
        }
        IsValidOp valid = new IsValidOp(g);
        require(valid.isValid(), "Невалидная геометрия: " + valid.getValidationError());
        if (g.getDimension() == 1) {
            require(g.isSimple() && g.getLength() > 0, "Самопересечение или нулевая длина линии");
        }
    }

    private static void require(boolean value, String message) {
        if (!value) { throw new IllegalArgumentException(message); }
    }
}
