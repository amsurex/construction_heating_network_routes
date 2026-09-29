package ru.lct.heat.geometry;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heat.model.ExistingPipe;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.ref.DiameterSpec;
import ru.lct.heat.model.ref.DiameterTable;
import ru.lct.heat.model.ref.RestrictionRules;

import java.util.Collections;

import static ru.lct.heat.support.TestGeoms.box;
import static ru.lct.heat.support.TestGeoms.c;
import static ru.lct.heat.support.TestGeoms.line;
import static ru.lct.heat.support.TestGeoms.restriction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObstacleMapTest {

    private static final DiameterSpec DN100 = DiameterTable.byDiameter(100).orElseThrow();
    private static final double EPS = 0.05;

    @Test
    void roadExitExactlyThroughACornerIsNotAValidCrossing() {
        // §4: дорогу пересекают под углом не менее 45° к границе в точке входа. Полоса дороги
        // x ∈ [0, 100], y ∈ [10, 18]; трасса идёт почти вертикально и выходит ровно через северо-
        // западный угол (0, 18), касаясь там же западной стены — к ней угол ~4°. Такое пересечение
        // недопустимо, хотя к горизонтальным стенам угол почти прямой.
        InputModel in = InputModel.builder()
                .restriction(restriction("road", RestrictionRules.ROAD, box(0, 10, 100, 18)))
                .build();
        ObstacleMap map = ObstacleMap.build(in, DN100, EPS, 10);
        assertFalse(map.check(c(0.17, 2), c(-0.07, 26), Collections.emptySet()).isFree(),
                "выход в сантиметре от угла: к западной стене угол меньше 45°");
        // та же полоса, но пересечение далеко от углов — обычный спецпроход
        assertTrue(map.check(c(50, 2), c(50, 26), Collections.emptySet()).isFree());
    }

    @Test
    void forbiddenOksBlocksSegmentInsideOffsetZone() {
        // Техприложение табл. 2: ОКС — пересечение запрещено, отступ 5 м при ДУ<500 (+ полширины габарита)
        InputModel in = InputModel.builder()
                .restriction(restriction("b1", RestrictionRules.OKS, box(40, -10, 60, 10)))
                .build();
        ObstacleMap map = ObstacleMap.build(in, DN100, EPS, 10);
        assertFalse(map.check(c(0, 0), c(100, 0), Collections.emptySet()).isFree(), "сквозь здание");
        assertFalse(map.check(c(0, 14), c(100, 14), Collections.emptySet()).isFree(), "4 м от стены");
        // 5 + 0.255 = 5.255 м от стены — уже можно
        assertTrue(map.check(c(0, 15.3), c(100, 15.3), Collections.emptySet()).isFree());
        // вершины графа — вне зоны отступа и по всем углам
        assertFalse(map.getVertices().isEmpty());
        for (Coordinate v : map.getVertices()) {
            assertTrue(map.isPointFree(v, Collections.emptySet()));
        }
    }

    @Test
    void exemptOwnPolygonAllowsFinalApproach() {
        // Техприложение §2.2: для собственного полигона отступ на финальный участок не действует
        InputModel in = InputModel.builder()
                .restriction(restriction("own", RestrictionRules.OKS, box(40, -10, 60, 10)))
                .build();
        ObstacleMap map = ObstacleMap.build(in, DN100, EPS, 10);
        assertFalse(map.check(c(0, 0), c(50, 0), Collections.emptySet()).isFree());
        assertTrue(map.check(c(0, 0), c(50, 0), Collections.singleton("own")).isFree());
    }

    @Test
    void roadCrossingAllowedOnlyStraightAndSteep() {
        // Техприложение табл. 2: дорога — спецпроход, угол ≥ 45°, спецзона = полигон + 3 м, Kспец 1.60
        InputModel in = InputModel.builder()
                .restriction(restriction("r1", RestrictionRules.ROAD, box(40, -100, 60, 100)))
                .build();
        ObstacleMap map = ObstacleMap.build(in, DN100, EPS, 10);

        SegmentCheck perpendicular = map.check(c(0, 0), c(100, 0), Collections.emptySet());
        assertTrue(perpendicular.isFree());
        assertEquals(1, perpendicular.getCrossings().size());
        assertEquals(1.60, perpendicular.maxKSpecial());
        assertEquals(26.0, perpendicular.getCrossings().get(0).getZoneLengthM(), 1e-6); // 20 + 3 + 3

        assertTrue(map.check(c(0, 0), c(100, 100), Collections.emptySet()).isFree(), "45° — можно");
        assertFalse(map.check(c(0, -50), c(100, 250), Collections.emptySet()).isFree(), "~18° к оси дороги — нельзя");
        assertFalse(map.check(c(0, 0), c(50, 0), Collections.emptySet()).isFree(), "конец внутри дороги");
        assertFalse(map.check(c(0, 0), c(62, 0), Collections.emptySet()).isFree(), "конец в 3-м зоне за дорогой");
        assertFalse(map.check(c(38, 0), c(100, 0), Collections.emptySet()).isFree(), "начало в спецзоне");
        assertFalse(map.check(c(0, 0), c(38.6, 0), Collections.emptySet()).isFree(), "конец ближе 1.5 м к дороге");
        assertTrue(map.check(c(0, 0), c(37.5, 0), Collections.emptySet()).isFree(), "конец в 2.5 м без пересечения — можно");
    }

    @Test
    void existingPipeIsSpecialCrossingButTieInAllowed() {
        // FAQ п.10: пересечение существующей сети без врезки — спецпроход K=1.05, отступ 1 м между габаритами
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(0, 0, 100, 0), 300))
                .build();
        ObstacleMap map = ObstacleMap.build(in, DN100, EPS, 10);

        assertFalse(map.check(c(0, 1), c(100, 1), Collections.emptySet()).isFree(), "вдоль трубы в 1 м");
        // 1.0 + 0.255 (DN100) + 0.575 (DN300) = 1.83 м → на 2 м уже можно
        assertTrue(map.check(c(0, 2), c(100, 2), Collections.emptySet()).isFree());

        SegmentCheck cross = map.check(c(50, -10), c(50, 10), Collections.emptySet());
        assertTrue(cross.isFree());
        assertEquals(1.05, cross.maxKSpecial());
        assertEquals(4.0, cross.getCrossings().get(0).getZoneLengthM(), 1e-6);

        SegmentCheck tieIn = map.check(c(50, 10), c(50, 0), Collections.emptySet());
        assertTrue(tieIn.isFree(), "врезка: конец на трубе");
        assertTrue(tieIn.getCrossings().isEmpty());
        assertFalse(map.check(c(50, -10), c(50, 1), Collections.emptySet()).isFree(), "конец в спецзоне, не на трубе");
        // отход от врезки: сразу из зоны отступа (в зоне ≤ 2·1.83 м вдоль отрезка), а не вдоль трубы
        assertTrue(map.check(c(50, 0), c(53, 3), Collections.emptySet()).isFree(), "отход под 45°");
        assertFalse(map.check(c(50, 0), c(80, 1.5), Collections.emptySet()).isFree(), "отход вдоль трубы в 1.5 м");
    }

    @Test
    void oksOffsetGrowsWithDiameter() {
        InputModel in = InputModel.builder()
                .restriction(restriction("b1", RestrictionRules.OKS, box(40, -10, 60, 10)))
                .build();
        ObstacleMap dn500 = ObstacleMap.build(in, DiameterTable.byDiameter(500).orElseThrow(), EPS, 10);
        // 7 м + 0.835 = 7.835: на 7.5 м нельзя, на 8 м можно
        assertFalse(dn500.check(c(0, 17.5), c(100, 17.5), Collections.emptySet()).isFree());
        assertTrue(dn500.check(c(0, 18), c(100, 18), Collections.emptySet()).isFree());
    }
}
