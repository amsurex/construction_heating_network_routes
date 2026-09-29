package ru.lct.heat.model;

import lombok.Value;
import org.locationtech.jts.geom.LineString;

/** Существующий участок тепловой сети. */
@Value
public class ExistingPipe {
    Object id;
    /** Геометрия в EPSG:32637. */
    LineString geometry;
    /** Условный диаметр, мм. */
    int diameter;
}
