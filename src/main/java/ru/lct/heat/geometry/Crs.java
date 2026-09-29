package ru.lct.heat.geometry;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.util.GeometryTransformer;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Проекция WGS84 (EPSG:4326) ↔ UTM 37N (EPSG:32637). Все расчёты ядра — в метрах UTM.
 */
public final class Crs {

    public static final int SRID_WGS84 = 4326;
    public static final int SRID_UTM37N = 32637;

    /** Фабрика геометрий ядра: float precision, SRID 32637. */
    public static final GeometryFactory UTM = new GeometryFactory(new PrecisionModel(), SRID_UTM37N);
    public static final GeometryFactory WGS = new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    private static final CoordinateTransform TO_UTM;
    private static final CoordinateTransform TO_WGS;

    static {
        CRSFactory f = new CRSFactory();
        CoordinateReferenceSystem wgs = f.createFromParameters("EPSG:4326",
                "+proj=longlat +datum=WGS84 +no_defs");
        CoordinateReferenceSystem utm = f.createFromParameters("EPSG:32637",
                "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs");
        CoordinateTransformFactory tf = new CoordinateTransformFactory();
        TO_UTM = tf.createTransform(wgs, utm);
        TO_WGS = tf.createTransform(utm, wgs);
    }

    private Crs() {
    }

    public static Coordinate toUtm(double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate();
        TO_UTM.transform(new ProjCoordinate(lon, lat), out);
        return new Coordinate(out.x, out.y);
    }

    public static Coordinate toWgs(double x, double y) {
        ProjCoordinate out = new ProjCoordinate();
        TO_WGS.transform(new ProjCoordinate(x, y), out);
        return new Coordinate(out.x, out.y);
    }

    public static Geometry toUtm(Geometry wgsGeometry) {
        return new Transformer(TO_UTM, UTM).transform(wgsGeometry);
    }

    public static Geometry toWgs(Geometry utmGeometry) {
        return new Transformer(TO_WGS, WGS).transform(utmGeometry);
    }

    private static final class Transformer extends GeometryTransformer {
        private final CoordinateTransform ct;

        Transformer(CoordinateTransform ct, GeometryFactory target) {
            this.ct = ct;
            this.factory = target;
        }

        @Override
        protected CoordinateSequence transformCoordinates(CoordinateSequence coords, Geometry parent) {
            Coordinate[] src = coords.toCoordinateArray();
            Coordinate[] dst = new Coordinate[src.length];
            ProjCoordinate in = new ProjCoordinate();
            ProjCoordinate out = new ProjCoordinate();
            for (int i = 0; i < src.length; i++) {
                in.x = src[i].x;
                in.y = src[i].y;
                ct.transform(in, out);
                dst[i] = new Coordinate(out.x, out.y);
            }
            return factory.getCoordinateSequenceFactory().create(dst);
        }
    }
}
