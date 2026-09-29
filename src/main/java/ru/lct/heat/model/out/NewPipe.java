package ru.lct.heat.model.out;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.LineString;
import ru.lct.heat.model.LayingMethod;

/** Новый участок тепловой сети (выходной heat_network). Геометрия в EPSG:32637. */
@Value
@Builder(toBuilder = true)
public class NewPipe {
    Object id;
    /** Id узла, совпадающего с первой вершиной LineString. */
    Object startNodeId;
    /** Id узла, совпадающего с последней вершиной LineString. */
    Object endNodeId;
    LineString geometry;
    /** Расчётный расход, т/ч. */
    double flowTph;
    /** ДУ, мм. */
    int diameter;
    /** Длина по горизонтальной проекции, м. */
    double length;
    LayingMethod layingMethod;
    /** Глубина в начале/конце, м; null в 2D-режиме. */
    Double depthStart;
    Double depthEnd;
    /** Стоимость участка, руб. */
    double cost;
    /** Kспец, применённый к участку (1.0 для base). Доп. атрибут. */
    double kSpecial;
    /** Kгл участка (1.0 в 2D). Доп. атрибут. */
    double kDepth;
    /** Тип(ы) ограничений спецпрохода через запятую, для объяснимости. Доп. атрибут. */
    String crossedRestrictionTypes;
    /** Прочие доп. properties (объяснения): записываются в GeoJSON как есть. */
    @lombok.Builder.Default
    java.util.Map<String, Object> extra = java.util.Collections.emptyMap();
}
