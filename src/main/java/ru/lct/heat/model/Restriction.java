package ru.lct.heat.model;

import lombok.Value;
import org.locationtech.jts.geom.Geometry;

/**
 * Пространственное ограничение. Геометрия может быть LineString / MultiLineString /
 * Polygon / MultiPolygon (EPSG:32637). Тип ограничения — строка из входа; правило для него
 * берётся из {@link ru.lct.heat.model.ref.RestrictionRules}.
 */
@Value
public class Restriction {
    Object id;
    Geometry geometry;
    String restrictionType;
}
