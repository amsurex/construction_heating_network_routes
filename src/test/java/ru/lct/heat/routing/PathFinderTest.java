package ru.lct.heat.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.ObstacleMap;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.Restriction;
import ru.lct.heat.model.ref.CostConstants;
import ru.lct.heat.model.ref.DiameterSpec;
import ru.lct.heat.model.ref.DiameterTable;
import ru.lct.heat.model.ref.RestrictionRules;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heat.support.TestGeoms.box;
import static ru.lct.heat.support.TestGeoms.c;
import static ru.lct.heat.support.TestGeoms.restriction;

class PathFinderTest {

    private static final DiameterSpec DN100 = DiameterTable.byDiameter(100).orElseThrow();
    private final RoutingParams params = new RoutingParams();

    private static void assertPathLegal(Path p, ObstacleMap map) {
        List<Coordinate> cs = p.getCoords();
        for (int i = 0; i + 1 < cs.size(); i++) {
            assertTrue(map.check(cs.get(i), cs.get(i + 1), Collections.emptySet()).isFree(),
                    "отрезок " + i + " нарушает ограничения");
            if (i >= 1) {
                double turn = GeomUtil.turnAngleDeg(cs.get(i - 1), cs.get(i), cs.get(i + 1));
                assertTrue(turn <= CostConstants.MAX_TURN_ANGLE_DEG + 1e-9, "поворот " + turn + "° > 90°");
            }
        }
    }

    @Test
    void straightLineWithoutObstacles() {
        ObstacleMap map = ObstacleMap.build(InputModel.builder().build(), DN100, 0.05, 10);
        Optional<Path> p = new PathFinder(map, params).find(c(0, 0),
                Collections.singletonList(new Goal(c(100, 0), 0, "g")));
        assertTrue(p.isPresent());
        assertEquals(2, p.get().getCoords().size());
        assertEquals(100.0, p.get().getLength(), 1e-9);
        assertEquals(100 * DN100.getCostPerMeter(), p.get().getCost(), 1e-6);
    }

    @Test
    void goesAroundBuilding() {
        InputModel in = InputModel.builder()
                .restriction(restriction("b1", RestrictionRules.OKS, box(40, -10, 60, 10)))
                .build();
        ObstacleMap map = ObstacleMap.build(in, DN100, 0.05, 10);
        Path p = new PathFinder(map, params).find(c(0, 0),
                Collections.singletonList(new Goal(c(100, 0), 0, "g"))).orElseThrow();
        assertPathLegal(p, map);
        assertTrue(p.getLength() > 100 && p.getLength() < 130, "длина " + p.getLength());
        assertEquals(2, p.turns());
    }

    @Test
    void picksCheaperGoalConsideringTerminalCost() {
        // цель A ближе, но с врезкой 5 млн; цель B дальше на 30 м, но без терминальной стоимости
        ObstacleMap map = ObstacleMap.build(InputModel.builder().build(), DN100, 0.05, 10);
        List<Goal> goals = Arrays.asList(
                new Goal(c(100, 0), 5_000_000, "A"),
                new Goal(c(130, 0), 0, "B"));
        Path p = new PathFinder(map, params).find(c(0, 0), goals).orElseThrow();
        assertEquals("B", p.getGoal().getRef());
    }

    @Test
    void crossesRoadPerpendicularlyRatherThanDiagonally() {
        // старт и цель смещены: по диагонали дешевле по длине, но угол < 45° запрещён →
        // путь должен поворачивать и пересекать дорогу под допустимым углом
        InputModel in = InputModel.builder()
                .restriction(restriction("r1", RestrictionRules.ROAD, box(100, -500, 120, 500)))
                .build();
        ObstacleMap map = ObstacleMap.build(in, DN100, 0.05, 10);
        Path p = new PathFinder(map, params).find(c(0, 0),
                Collections.singletonList(new Goal(c(220, 400), 0, "g"))).orElseThrow();
        assertPathLegal(p, map);
        assertTrue(p.getChecks().stream().anyMatch(ch -> !ch.getCrossings().isEmpty()), "дорога пересечена");
    }

    @Test
    void nearestEntryIsKeptUnlessDetourIsBothRelativeAndAbsolute() {
        // §2.2: первый старт — ближайшая граница; альтернатива берётся только если трасса через ближайшую
        // границу длиннее и в N раз, и не менее чем на minDetourM (полная длина трубы вместе с финальным участком)
        ObstacleMap map = ObstacleMap.build(InputModel.builder().build(), DN100, 0.05, 10);
        PathFinder finder = new PathFinder(map, params);
        List<PathFinder.Start> starts = Arrays.asList(
                new PathFinder.Start(c(0, 0), 3 * DN100.getCostPerMeter(), c(0, -3)),    // ближайшая: 12 + 3 = 15 м
                new PathFinder.Start(c(10, 0), 3 * DN100.getCostPerMeter(), c(10, -3))); // другая стена: 2 + 3 = 5 м
        List<Goal> goals = Collections.singletonList(new Goal(c(12, 0), 0, "g"));

        Path strict = finder.findPreferFirst(starts, goals, 2.0, 30).orElseThrow();
        assertEquals(c(0, 0), strict.getCoords().get(0), "обход 10 м < 30 м — остаёмся у ближайшей границы");
        assertTrue(finder.getLastEntryNote() == null);

        Path relaxed = finder.findPreferFirst(starts, goals, 2.0, 5).orElseThrow();
        assertEquals(c(10, 0), relaxed.getCoords().get(0), "15 м > 2 × 5 м и разница ≥ 5 м — альтернатива");
        assertTrue(finder.getLastEntryNote() != null && finder.getLastEntryNote().contains("15 м"));

        Path ratioOnly = finder.findPreferFirst(starts, goals, 4.0, 5).orElseThrow();
        assertEquals(c(0, 0), ratioOnly.getCoords().get(0), "15 < 4 × 5 — фактор не превышен");
    }

    @Test
    void noPathWhenFullyEnclosed() {
        InputModel in = InputModel.builder()
                .restriction(restriction("wall", RestrictionRules.WATER,
                        box(-50, -50, 50, 50).difference(box(-40, -40, 40, 40))))
                .build();
        ObstacleMap map = ObstacleMap.build(in, DN100, 0.05, 10);
        Optional<Path> p = new PathFinder(map, params).find(c(0, 0),
                Collections.singletonList(new Goal(c(200, 0), 0, "g")));
        assertTrue(p.isEmpty());
    }
}
