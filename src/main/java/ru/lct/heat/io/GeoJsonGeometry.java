package ru.lct.heat.io;

import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.*;
import ru.lct.heat.geometry.Crs;

/** Геометрия §1: строгий разбор WGS84 без исправления повреждённых колец. */
public final class GeoJsonGeometry {
    private GeoJsonGeometry() { }

    public static Geometry read(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("geometry должна быть объектом");
        }
        JsonNode c = node.path("coordinates");
        requireArray(c);
        switch (node.path("type").asText()) {
            case "Point": return Crs.WGS.createPoint(coordinate(c));
            case "LineString": return Crs.WGS.createLineString(coordinates(c));
            case "Polygon": return polygon(c);
            case "MultiLineString":
                LineString[] lines = new LineString[c.size()];
                for (int i = 0; i < lines.length; i++) {
                    lines[i] = Crs.WGS.createLineString(coordinates(c.get(i)));
                }
                return Crs.WGS.createMultiLineString(lines);
            case "MultiPolygon":
                Polygon[] polygons = new Polygon[c.size()];
                for (int i = 0; i < polygons.length; i++) {
                    polygons[i] = polygon(c.get(i));
                }
                return Crs.WGS.createMultiPolygon(polygons);
            default: throw new IllegalArgumentException("Неподдерживаемый тип геометрии");
        }
    }

    private static Polygon polygon(JsonNode rings) {
        requireArray(rings);
        LinearRing shell = Crs.WGS.createLinearRing(coordinates(rings.get(0)));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = Crs.WGS.createLinearRing(coordinates(rings.get(i)));
        }
        return Crs.WGS.createPolygon(shell, holes);
    }

    private static Coordinate[] coordinates(JsonNode array) {
        requireArray(array);
        Coordinate[] result = new Coordinate[array.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = coordinate(array.get(i));
        }
        return result;
    }

    private static Coordinate coordinate(JsonNode c) {
        requireArray(c);
        if (c.size() < 2 || !c.get(0).isNumber() || !c.get(1).isNumber()) {
            throw new IllegalArgumentException("Координата должна содержать числовые longitude, latitude");
        }
        double x = c.get(0).doubleValue(), y = c.get(1).doubleValue();
        if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 180 || Math.abs(y) > 90) {
            throw new IllegalArgumentException("Координаты вне диапазона WGS84");
        }
        return new Coordinate(x, y);
    }

    private static void requireArray(JsonNode n) {
        if (n == null || !n.isArray() || n.isEmpty()) {
            throw new IllegalArgumentException("Ожидается непустой массив координат");
        }
    }
}
