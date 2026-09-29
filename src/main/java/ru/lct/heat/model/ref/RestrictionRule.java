package ru.lct.heat.model.ref;

import lombok.Builder;
import lombok.Value;

/** Строка таблицы 2 Техприложения — правило для типа ограничения. */
@Value
@Builder(toBuilder = true)
public class RestrictionRule {

    public enum Kind {
        /** Пересечение запрещено, обходим с отступом. */
        FORBIDDEN,
        /** Специальный проход одним прямым участком. */
        SPECIAL_CROSSING
    }

    String restrictionType;
    Kind kind;
    /**
     * Минимальное горизонтальное расстояние от границы ограничения до внешней границы габарита сети, м.
     * Для oks зависит от ДУ — см. {@link #minDistance(int)}.
     */
    double minDistanceM;
    /** Минимальный угол пересечения, градусы (0 — не задан). */
    double minCrossingAngleDeg;
    /** Отступ границ спецучастка: от полигона (road/tram — 3 м) или от точки пересечения (линейные — 2 м), м. */
    double specialMarginM;
    /** Коэффициент удорожания спецпрохода Kспец (1.0 для запретов). */
    double kSpecial;
    /** Собственный расчётный габарит ограничения (ширина), м; 0 если не задан. */
    double ownWidthM;
    /** Собственный расчётный габарит ограничения (высота), м; 0 если не задан. */
    double ownHeightM;
    /** Условная глубина до верха габарита ограничения, м (режим с глубиной); 0 если не задана. */
    double ownDepthM;
    /** Минимальный вертикальный просвет / глубина под объектом (режим с глубиной), м. */
    double verticalClearanceM;
    /** Режим с глубиной: новая сеть проходит строго под объектом (дороги, трамвай) — иначе выше или ниже. */
    boolean crossUnderOnly;

    public boolean isForbidden() {
        return kind == Kind.FORBIDDEN;
    }

    /** Минимальный отступ с учётом ДУ (для oks — 5/7/9 м). */
    public double minDistance(int diameter) {
        if (RestrictionRules.OKS.equals(restrictionType)) {
            if (diameter < 500) {
                return 5.0;
            }
            if (diameter <= 800) {
                return 7.0;
            }
            return 9.0;
        }
        return minDistanceM;
    }
}
