package ru.lct.heat.geometry;

import lombok.Value;

import java.util.Collections;
import java.util.List;

/** Результат проверки прямого отрезка новой сети относительно препятствий. */
@Value
public class SegmentCheck {

    /** Одно разрешённое специальное пересечение внутри отрезка. */
    @Value
    public static class Crossing {
        Obstacle obstacle;
        /** Длина части отрезка внутри спецзоны препятствия, м. */
        double zoneLengthM;
    }

    boolean free;
    /** Препятствие, из-за которого отрезок недопустим (null если free). */
    Obstacle blockedBy;
    /** Причина блокировки (для диагностики). */
    String reason;
    List<Crossing> crossings;

    public static SegmentCheck ok(List<Crossing> crossings) {
        return new SegmentCheck(true, null, null, crossings);
    }

    public static SegmentCheck blocked(Obstacle by, String reason) {
        return new SegmentCheck(false, by, reason, Collections.emptyList());
    }

    /** Максимальный Kспец среди пересечений (1.0 если их нет). Техприложение §4: коэффициенты не перемножаются. */
    public double maxKSpecial() {
        double k = 1.0;
        for (Crossing c : crossings) {
            k = Math.max(k, c.obstacle.getRule().getKSpecial());
        }
        return k;
    }

    /**
     * Оценка добавочной стоимости отрезка из-за спецпроходов в метрах-эквивалентах:
     * Σ (K−1)·L_zone. Точный расчёт с разбиением на участки — в cost/, здесь — для A*.
     */
    public double extraLengthEquivalent() {
        double extra = 0;
        for (Crossing c : crossings) {
            extra += (c.obstacle.getRule().getKSpecial() - 1.0) * c.zoneLengthM;
        }
        return extra;
    }
}
