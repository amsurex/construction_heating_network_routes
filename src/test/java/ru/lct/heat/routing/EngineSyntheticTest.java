package ru.lct.heat.routing;

import org.junit.jupiter.api.Test;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ExistingChamber;
import ru.lct.heat.model.ExistingPipe;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.LayingMethod;
import ru.lct.heat.model.out.NewPipe;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.model.ref.ChamberCostTable;
import ru.lct.heat.model.ref.DiameterTable;
import ru.lct.heat.model.ref.RestrictionRules;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heat.support.TestGeoms.box;
import static ru.lct.heat.support.TestGeoms.c;
import static ru.lct.heat.support.TestGeoms.line;
import static ru.lct.heat.support.TestGeoms.restriction;

/** Сквозные сценарии ядра на синтетике: правила Техприложения на выходе. */
class EngineSyntheticTest {

    private static Variant solve(InputModel in) {
        return new HeatRoutingEngine(new RoutingParams()).solve(in, SolveOptions.defaults()).get(0);
    }

    @Test
    void specExampleStraightPipeToExistingChamber() {
        // §7.3: точка в 100 м от существующей камеры, 20 т/ч → один участок ДУ100, 8 974 800 + врезка 5 000 000
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-100, 0, 0, 0), 300))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))))
                .connectionPoint(new ConnectionPoint("oks1", Crs.UTM.createPoint(c(0, 100)), 20.0))
                .build();
        Variant v = solve(in);
        assertEquals(1, v.getPipes().size());
        NewPipe p = v.getPipes().get(0);
        assertEquals("ch1", p.getStartNodeId());
        assertEquals("oks1", p.getEndNodeId());
        assertEquals(100, p.getDiameter());
        assertEquals(100.0, p.getLength(), 1e-6);
        assertEquals(8_974_800, p.getCost(), 1e-6);
        assertEquals(1, v.getSummary().getExistingChamberTieInCount());
        assertEquals(13_974_800, v.getSummary().getConstructionCost(), 1e-6);
        assertEquals(0.6913, v.getSummary().getScore(), 1e-4);
        assertTrue(v.getChambers().isEmpty());
    }

    @Test
    void roadCrossingIsSplitIntoSpecialSection() {
        // §4: дорога между точкой и сетью → base / special(полигон + 3 м с каждой стороны, K=1.6) / base
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-100, 0, 100, 0), 300))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))))
                .restriction(restriction("road", RestrictionRules.ROAD, box(-500, 40, 500, 60)))
                .connectionPoint(new ConnectionPoint("oks1", Crs.UTM.createPoint(c(0, 100)), 20.0))
                .build();
        Variant v = solve(in);
        List<NewPipe> pipes = v.getPipes();
        assertEquals(3, pipes.size(), "участок делится на границах спецзоны");
        assertEquals(2, v.getTechnicalNodes().size());
        NewPipe special = pipes.stream().filter(p -> p.getLayingMethod() == LayingMethod.SPECIAL).findFirst().orElseThrow();
        assertEquals(26.0, special.getLength(), 0.01);
        assertEquals(1.6, special.getKSpecial());
        assertEquals(26.0 * DiameterTable.byDiameter(100).orElseThrow().getCostPerMeter() * 1.6, special.getCost(), 1.0);
        double total = pipes.stream().mapToDouble(NewPipe::getLength).sum();
        assertEquals(100.0, total, 0.01);
        // цепочка узлов: ch1 → node → node → oks1
        assertEquals(1, pipes.stream().filter(p -> "ch1".equals(p.getStartNodeId())).count());
        assertEquals(1, pipes.stream().filter(p -> "oks1".equals(p.getEndNodeId())).count());
    }

    @Test
    void twoNearbyPointsShareTrunkWithBranchChamber() {
        // две точки рядом далеко от сети: выгоднее общая магистраль + камера-разветвление (3 млн),
        // чем две врезки в существующую камеру (2 × 5 млн)
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-100, 0, 100, 0), 300))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))))
                .connectionPoint(new ConnectionPoint("a", Crs.UTM.createPoint(c(-10, 300)), 20.0))
                .connectionPoint(new ConnectionPoint("b", Crs.UTM.createPoint(c(10, 300)), 20.0))
                .build();
        Variant v = solve(in);
        assertEquals(1, v.getSummary().getExistingChamberTieInCount());
        assertEquals(1, v.getChambers().size());
        assertEquals(ChamberCostTable.newChamberCost(125), v.getChambers().get(0).getCost());
        assertEquals(3, v.getPipes().size());
        NewPipe trunk = v.getPipes().stream().filter(p -> "ch1".equals(p.getStartNodeId())).findFirst().orElseThrow();
        assertEquals(40.0, trunk.getFlowTph(), 1e-6);
        assertEquals(125, trunk.getDiameter(), "40 т/ч → ДУ125");
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
    }

    @Test
    void limitLengthRaisesDiameterOneStep() {
        // §2.3: 20 т/ч → ДУ100, но 450 м > 419 м → ДУ125 (554 м)
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-100, 0, 100, 0), 300))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))))
                .connectionPoint(new ConnectionPoint("oks1", Crs.UTM.createPoint(c(0, 450)), 20.0))
                .build();
        Variant v = solve(in);
        assertEquals(125, v.getPipes().get(0).getDiameter());
    }

    @Test
    void unreachablePointGetsPenaltyAndOthersAreKept() {
        // §2.5, §6: точка внутри замкнутого запретного кольца → штраф 100 млн + 500 тыс·G, остальные строятся
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-100, 0, 100, 0), 300))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))))
                .restriction(restriction("ring", RestrictionRules.WATER,
                        box(150, 150, 250, 250).difference(box(160, 160, 240, 240))))
                .connectionPoint(new ConnectionPoint("trapped", Crs.UTM.createPoint(c(200, 200)), 10.0))
                .connectionPoint(new ConnectionPoint("ok", Crs.UTM.createPoint(c(0, 100)), 20.0))
                .build();
        Variant v = solve(in);
        assertEquals(List.of("trapped"), v.getSummary().getUnconnectedOksIds());
        assertEquals(105_000_000, v.getSummary().getUnconnectedPenalty(), 1e-6);
        assertEquals(1, v.getPipes().size());
        assertEquals(v.getSummary().getConstructionCost() + 105_000_000, v.getSummary().getCalculatedCost(), 1e-6);
    }

    @Test
    void buildingInsideSchoolTerritoryIsReachableThroughTheTerritory() {
        // школа: здание (oks) внутри своей территории (social_area, запрет 1 м). Точка внутри здания.
        // Финальный прямой участок проходит через территорию и здание — отступ к содержащим полигонам не действует.
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-100, 0, 100, 0), 300))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))))
                .restriction(restriction("school", RestrictionRules.SOCIAL_AREA, box(-60, 60, 60, 180)))
                .restriction(restriction("bld", RestrictionRules.OKS, box(-20, 100, 20, 140)))
                .connectionPoint(new ConnectionPoint("oks1", Crs.UTM.createPoint(c(0, 120)), 20.0))
                .build();
        Variant v = solve(in);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty(), "школа должна подключаться");
        NewPipe last = v.getPipes().stream().filter(p -> "oks1".equals(p.getEndNodeId())).findFirst().orElseThrow();
        // вход через ближайшую границу здания (южная стена, y=100): финальный отрезок вертикальный
        var cs = last.getGeometry().getCoordinates();
        assertEquals(0.0, cs[cs.length - 2].x, 1e-6);
        assertTrue(cs[cs.length - 2].y < 60 - 1.0, "точка входа снаружи территории и её отступа");
    }

    @Test
    void expertCanPinTieInAndExcludeRestriction() {
        // две камеры: ближняя ch_near и дальняя ch_far; по умолчанию — ближняя; эксперт фиксирует дальнюю
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-200, 0, 200, 0), 300))
                .chamber(new ExistingChamber("ch_near", Crs.UTM.createPoint(c(0, 0))))
                .chamber(new ExistingChamber("ch_far", Crs.UTM.createPoint(c(150, 0))))
                .restriction(restriction("park", RestrictionRules.PARK, box(-30, 30, 30, 70)))
                .connectionPoint(new ConnectionPoint("oks1", Crs.UTM.createPoint(c(0, 100)), 20.0))
                .build();
        Variant base = solve(in);
        assertTrue(base.getSummary().getNewNetworkLength() > 100.5, "парк обходится");
        Variant pinned = new HeatRoutingEngine(new RoutingParams()).solve(in,
                SolveOptions.builder().pinTieInIds(java.util.Set.of("ch_far")).build()).get(0);
        assertEquals("ch_far", pinned.getPipes().stream().filter(p -> p.getStartNodeId().equals("ch_far"))
                .findFirst().orElseThrow().getStartNodeId());
        Variant noPark = new HeatRoutingEngine(new RoutingParams()).solve(in,
                SolveOptions.builder().excludeRestrictionIds(java.util.Set.of("park")).build()).get(0);
        assertEquals(100.0, noPark.getSummary().getNewNetworkLength(), 1e-6, "без парка — прямая");
    }
}
