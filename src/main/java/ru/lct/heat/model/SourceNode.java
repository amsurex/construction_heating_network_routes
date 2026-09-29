package ru.lct.heat.model;

import lombok.Value;
import org.locationtech.jts.geom.Point;

/** Источник теплоснабжения. */
@Value
public class SourceNode {
    /** Исходный id из GeoJSON (String или Number) — тип сохраняем как есть. */
    Object id;
    /** Геометрия в EPSG:32637. */
    Point geometry;
}
