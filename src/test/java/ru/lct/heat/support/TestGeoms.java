package ru.lct.heat.support;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.model.Restriction;

/** Фабрики синтетических геометрий (UTM, метры) для тестов ядра. */
public final class TestGeoms {

    private TestGeoms() {
    }

    public static Geometry box(double x1, double y1, double x2, double y2) {
        return Crs.UTM.createPolygon(new Coordinate[]{
                new Coordinate(x1, y1), new Coordinate(x2, y1), new Coordinate(x2, y2),
                new Coordinate(x1, y2), new Coordinate(x1, y1)});
    }

    public static Coordinate c(double x, double y) {
        return new Coordinate(x, y);
    }

    public static LineString line(double... xy) {
        Coordinate[] cs = new Coordinate[xy.length / 2];
        for (int i = 0; i < cs.length; i++) {
            cs[i] = new Coordinate(xy[2 * i], xy[2 * i + 1]);
        }
        return Crs.UTM.createLineString(cs);
    }

    public static Restriction restriction(Object id, String type, Geometry g) {
        return new Restriction(id, g, type);
    }
}
