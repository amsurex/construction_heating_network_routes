package ru.lct.heat.model;

import lombok.Value;
import org.locationtech.jts.geom.Point;

/** Точка подключения ОКС — самостоятельная цель подключения. */
@Value
public class ConnectionPoint {
    Object id;
    /** Геометрия в EPSG:32637. */
    Point geometry;
    /** Расчётный расход, т/ч. */
    double flowTph;
}
