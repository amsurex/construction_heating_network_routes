package ru.lct.heat.routing.network;

import lombok.Getter;
import lombok.Setter;
import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.List;

/** Узел строящейся сети: точка ОКС, существующая камера, новая камера или технический узел. */
@Getter
public final class NetNode {

    public enum Kind {
        /** Точка подключения ОКС — лист дерева. */
        OKS_POINT,
        /** Существующая тепловая камера — корень (врезка 5 млн за каждый новый участок). */
        EXISTING_CHAMBER,
        /** Новая тепловая камера на существующем участке — корень (стоимость по табл. 3.2). */
        TIE_IN_CHAMBER,
        /** Новая тепловая камера в точке разветвления новой сети. */
        BRANCH_CHAMBER,
        /** Технический узел — смена параметра без разветвления. */
        TECH_NODE
    }

    private final Object id;
    /** Координата узла; меняется только вместе с концами прилегающих рёбер (см. NewNetwork#moveNode). */
    @Setter(lombok.AccessLevel.PACKAGE)
    private Coordinate coord;
    @Setter
    private Kind kind;
    /** Для TIE_IN_CHAMBER — id существующего участка, в который врезаемся. */
    @Setter
    private Object tieInPipeId;
    /** Наибольший ДУ существующих участков, примыкающих к узлу (для стоимости камеры-врезки). */
    @Setter
    private int existingMaxDiameter;

    /** Для точки ОКС: пояснение выбора точки входа в здание (если вход не через ближайшую границу). */
    @Setter
    private String approachNote;
    private final List<NetEdge> edges = new ArrayList<>();

    public NetNode(Object id, Coordinate coord, Kind kind) {
        this.id = id;
        this.coord = coord;
        this.kind = kind;
    }

    public boolean isRoot() {
        return kind == Kind.EXISTING_CHAMBER || kind == Kind.TIE_IN_CHAMBER;
    }

    public int degree() {
        return edges.size();
    }

    @Override
    public String toString() {
        return kind + "#" + id;
    }
}
