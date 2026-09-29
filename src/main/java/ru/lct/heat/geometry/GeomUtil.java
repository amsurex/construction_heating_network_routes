package ru.lct.heat.geometry;

import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

import java.util.List;

/** Мелкие геометрические помощники поверх JTS. Все координаты — метры UTM. */
public final class GeomUtil {

    private GeomUtil() {
    }

    public static LineString segment(Coordinate a, Coordinate b) {
        return Crs.UTM.createLineString(new Coordinate[]{a, b});
    }

    public static LineString line(List<Coordinate> coords) {
        return Crs.UTM.createLineString(coords.toArray(new Coordinate[0]));
    }

    public static Point point(Coordinate c) {
        return Crs.UTM.createPoint(c);
    }

    /**
     * Угол поворота в вершине b при движении a→b→c, градусы в [0, 180].
     * 0 — движение по прямой, 180 — разворот. Техприложение §2.1.
     */
    public static double turnAngleDeg(Coordinate a, Coordinate b, Coordinate c) {
        double inner = Angle.angleBetween(a, b, c); // угол между лучами b→a и b→c, [0, π]
        return Math.toDegrees(Math.PI - inner);
    }

    /**
     * Острый угол между направлениями двух отрезков, градусы в [0, 90].
     * Используется для угла пересечения с осью препятствия (Техприложение §4).
     */
    public static double crossingAngleDeg(Coordinate a1, Coordinate a2, Coordinate b1, Coordinate b2) {
        double ang = Math.abs(Angle.angle(a1, a2) - Angle.angle(b1, b2));
        ang = Math.toDegrees(ang) % 180.0;
        return ang > 90.0 ? 180.0 - ang : ang;
    }

    /** Точка на расстоянии dist от from в направлении towards. */
    public static Coordinate along(Coordinate from, Coordinate towards, double dist) {
        double dx = towards.x - from.x;
        double dy = towards.y - from.y;
        double len = Math.hypot(dx, dy);
        if (len == 0) {
            return new Coordinate(from);
        }
        return new Coordinate(from.x + dx / len * dist, from.y + dy / len * dist);
    }
}
