package ru.lct.heat.model;

import lombok.Value;
import org.locationtech.jts.geom.Point;

/** Существующая тепловая камера. */
@Value
public class ExistingChamber {
    Object id;
    /** Геометрия в EPSG:32637. */
    Point geometry;
}
