package ru.lct.heat.routing.network;

import lombok.Getter;
import lombok.Setter;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heat.geometry.SegmentCheck;
import ru.lct.heat.model.LayingMethod;
import ru.lct.heat.model.ref.DiameterSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Ребро строящейся сети — ломаная между двумя узлами. Направление from→to: от корня (врезки)
 * к листьям (ОКС), т.е. по потоку теплоносителя.
 */
@Getter
public final class NetEdge {

    private final NetNode from;
    private final NetNode to;
    /** Вершины ломаной, включая координаты from и to. */
    private final List<Coordinate> coords;
    /** checks.get(i) — проверка отрезка coords[i]→coords[i+1]. */
    private final List<SegmentCheck> checks;

    /** Расчётный расход, т/ч (сумма по всем ОКС ниже по дереву). Заполняется sizing. */
    @Setter
    private double flowTph;
    /** Подобранный ДУ. Заполняется sizing. */
    @Setter
    private DiameterSpec diameter;
    /** Способ прокладки; special — участок целиком внутри спецзоны. Заполняется SpecialSplitter. */
    @Setter
    private LayingMethod layingMethod = LayingMethod.BASE;
    /** Kспец участка (1.0 для base; max по наложившимся зонам для special). */
    @Setter
    private double kSpecial = 1.0;
    /** Типы пересекаемых ограничений спецучастка через запятую (для объяснимости). */
    @Setter
    private String crossedTypes;
    /** Глубина до верха габарита в начале/конце, м; null в 2D-режиме. Заполняется DepthPlanner. */
    @Setter
    private Double depthStart;
    @Setter
    private Double depthEnd;
    /** Kгл участка (среднее по концам), 1.0 в 2D. */
    @Setter
    private double kDepth = 1.0;
    /** Id точки ОКС, при подключении которой создано ребро (для подсказки ДУ во втором проходе). */
    @Setter
    private Object creatorPointId;

    public NetEdge(NetNode from, NetNode to, List<Coordinate> coords, List<SegmentCheck> checks) {
        this.from = from;
        this.to = to;
        this.coords = new ArrayList<>(coords);
        this.checks = new ArrayList<>(checks);
    }

    public double length() {
        double len = 0;
        for (int i = 0; i + 1 < coords.size(); i++) {
            len += coords.get(i).distance(coords.get(i + 1));
        }
        return len;
    }

    public NetNode other(NetNode n) {
        return n == from ? to : from;
    }
}
