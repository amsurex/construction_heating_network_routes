package ru.lct.heat.model.out;

import lombok.Value;
import org.locationtech.jts.geom.Point;

/** Технический узел — смена параметра участка без разветвления. EPSG:32637. */
@Value
public class TechnicalNode {
    Object id;
    Point geometry;
    /** Причина создания (special_boundary, depth_change, diameter_change) — доп. атрибут для объяснимости. */
    String reason;
}
