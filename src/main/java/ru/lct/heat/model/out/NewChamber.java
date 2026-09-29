package ru.lct.heat.model.out;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Point;

/** Новая тепловая камера (разветвление или врезка в существующий участок). EPSG:32637. */
@Value
@Builder(toBuilder = true)
public class NewChamber {
    Object id;
    Point geometry;
    /** Наибольший ДУ примыкающих участков, мм. */
    int diameter;
    /** Стоимость по таблице 3.2, руб. */
    double cost;
    /** Id существующего участка heat_network, в который выполнена врезка; null для камеры-разветвления. */
    Object tieInPipeId;
    /** Прочие доп. properties (объяснения): записываются в GeoJSON как есть. */
    @lombok.Builder.Default
    java.util.Map<String, Object> extra = java.util.Collections.emptyMap();
}
