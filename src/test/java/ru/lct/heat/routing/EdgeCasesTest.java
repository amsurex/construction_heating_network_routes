package ru.lct.heat.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ExistingChamber;
import ru.lct.heat.model.ExistingPipe;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.Restriction;
import ru.lct.heat.model.out.NewPipe;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.model.ref.RestrictionRules;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heat.support.TestGeoms.box;
import static ru.lct.heat.support.TestGeoms.c;
import static ru.lct.heat.support.TestGeoms.line;
import static ru.lct.heat.support.TestGeoms.restriction;

/** Граничные случаи входа: ядро не должно падать и должно давать осмысленный результат. */
class EdgeCasesTest {

    private static Variant solve(InputModel in) {
        List<Variant> vs = new HeatRoutingEngine(new RoutingParams()).solve(in, SolveOptions.defaults());
        assertFalse(vs.isEmpty(), "хотя бы один вариант");
        return vs.get(0);
    }

    private static InputModel.InputModelBuilder base() {
        return InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-200, 0, 200, 0), 300))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))));
    }

    @Test
    void pointOutsideAnyBuilding() {
        Variant v = solve(base().connectionPoint(new ConnectionPoint("free", Crs.UTM.createPoint(c(30, 80)), 12.0)).build());
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        assertEquals("free", v.getPipes().get(0).getEndNodeId());
    }

    @Test
    void pointExactlyOnBuildingBoundary() {
        Variant v = solve(base()
                .restriction(restriction("b", RestrictionRules.OKS, box(-20, 100, 20, 140)))
                .connectionPoint(new ConnectionPoint("edge", Crs.UTM.createPoint(c(0, 100)), 12.0)).build());
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        NewPipe last = v.getPipes().stream().filter(p -> "edge".equals(p.getEndNodeId())).findFirst().orElseThrow();
        Coordinate[] cs = last.getGeometry().getCoordinates();
        assertTrue(cs[cs.length - 2].y < 100, "вход снаружи, перпендикулярно стене");
    }

    @Test
    void chamberInTheMiddleOfAPipeCountsTwoConnections() {
        // камера на внутренней вершине участка: линия проходит через камеру = 2 примыкания; ещё 2 доступны
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-200, 0, 0, 0, 200, 0), 300))
                .chamber(new ExistingChamber("mid", Crs.UTM.createPoint(c(0, 0))))
                .connectionPoint(new ConnectionPoint("a", Crs.UTM.createPoint(c(-3, 100)), 12.0))
                .connectionPoint(new ConnectionPoint("b", Crs.UTM.createPoint(c(3, 100)), 12.0))
                .build();
        Variant v = solve(in);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        assertTrue(v.getSummary().getExistingChamberTieInCount() >= 1);
    }

    @Test
    void fullChamberIsNotUsedForTieIn() {
        // 4 участка сходятся в камере — присоединяться к ней нельзя, ставится новая камера рядом (не ближе 10 м? — нет: правило 10 м не действует при заполненной камере)
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("w", line(-200, 0, 0, 0), 300))
                .pipe(new ExistingPipe("e", line(0, 0, 200, 0), 300))
                .pipe(new ExistingPipe("n", line(0, 0, 0, -50), 300))
                .pipe(new ExistingPipe("s", line(0, 0, 0, -100), 300))
                .chamber(new ExistingChamber("full", Crs.UTM.createPoint(c(0, 0))))
                .connectionPoint(new ConnectionPoint("a", Crs.UTM.createPoint(c(0, 100)), 12.0))
                .build();
        Variant v = solve(in);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        assertEquals(0, v.getSummary().getExistingChamberTieInCount(), "в заполненную камеру не врезаемся");
        assertEquals(1, v.getChambers().size());
    }

    @Test
    void newChamberLimitedToFourConnections() {
        // 5 точек, которым выгодно висеть на одной камере-разветвлении: у камеры ≤ 4 примыканий (1 вход + 3 ветви)
        InputModel.InputModelBuilder b = base();
        for (int i = 0; i < 5; i++) {
            b.connectionPoint(new ConnectionPoint("p" + i, Crs.UTM.createPoint(c(-40 + i * 20, 300)), 5.0));
        }
        Variant v = solve(b.build());
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        for (var ch : v.getChambers()) {
            int connections = (Integer) ch.getExtra().get("connections");
            assertTrue(connections <= 4, "камера " + ch.getId() + ": " + connections + " примыканий");
        }
    }

    @Test
    void noExistingNetworkGivesPenaltiesNotCrash() {
        InputModel in = InputModel.builder()
                .connectionPoint(new ConnectionPoint("a", Crs.UTM.createPoint(c(0, 100)), 12.0))
                .build();
        Variant v = solve(in);
        assertEquals(List.of("a"), v.getSummary().getUnconnectedOksIds());
        assertTrue(v.getPipes().isEmpty());
    }

    @Test
    void twoPointsAtSameCoordinates() {
        Variant v = solve(base()
                .connectionPoint(new ConnectionPoint("a", Crs.UTM.createPoint(c(0, 100)), 12.0))
                .connectionPoint(new ConnectionPoint("b", Crs.UTM.createPoint(c(0, 100)), 8.0)).build());
        assertTrue(v.getSummary().getUnconnectedOksIds().size() <= 1, "хотя бы одна из совпадающих точек подключена");
    }

    @Test
    void multiLineStringRestrictionIsCrossedAsSpecial() {
        var gas = Crs.UTM.createMultiLineString(new org.locationtech.jts.geom.LineString[]{
                line(-300, 50, -10, 50), line(-10, 50, 300, 50)});
        Variant v = solve(base()
                .restriction(new Restriction("gas", gas, RestrictionRules.GAS_PIPELINE))
                .connectionPoint(new ConnectionPoint("a", Crs.UTM.createPoint(c(0, 100)), 12.0)).build());
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        assertTrue(v.getPipes().stream().anyMatch(p -> p.getKSpecial() > 1.2), "спецпроход газопровода");
    }

    @Test
    void pointInsideRoadPolygonIsStillReached() {
        // точка подключения (без здания) внутри дорожного полигона: спецучасток заканчивается в точке ОКС
        Variant v = solve(base()
                .restriction(restriction("road", RestrictionRules.ROAD, box(-300, 80, 300, 120)))
                .connectionPoint(new ConnectionPoint("a", Crs.UTM.createPoint(c(0, 100)), 12.0)).build());
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
    }

    @Test
    void hugeFlowUsesBigDiameterAndSevenMetreOffset() {
        // 1500 т/ч → ДУ500 → отступ от ОКС 7 м
        Variant v = solve(base()
                .restriction(restriction("b", RestrictionRules.OKS, box(-60, 40, 60, 60)))
                .connectionPoint(new ConnectionPoint("big", Crs.UTM.createPoint(c(0, 150)), 1500.0)).build());
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        assertEquals(500, v.getPipes().get(0).getDiameter());
        double minDist = Double.MAX_VALUE;
        var bld = box(-60, 40, 60, 60);
        for (NewPipe p : v.getPipes()) {
            minDist = Math.min(minDist, bld.distance(p.getGeometry()));
        }
        assertTrue(minDist >= 7.0 + 0.835 - 0.02, "отступ 7 м + полгабарита, факт " + minDist);
    }

    @Test
    void chamberSlightlyOffThePipeIsSnappedAndUsed() {
        // камера в 0.3 м от участка (шум координат): считается лежащей на участке, врезка в неё возможна
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-200, 0, 200, 0), 300))
                .chamber(new ExistingChamber("ch", Crs.UTM.createPoint(c(0, 0.3))))
                .connectionPoint(new ConnectionPoint("a", Crs.UTM.createPoint(c(0, 100)), 12.0))
                .build();
        Variant v = solve(in);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        assertEquals(1, v.getSummary().getExistingChamberTieInCount(), "врезка в существующую камеру");
        assertEquals("ch", v.getPipes().get(0).getStartNodeId());
    }

    @Test
    void attachedNeighbourBuildingDoesNotBlockFinalApproach() {
        // точка у общей стены с пристроенным корпусом (стык стен): отступ 5 м к соседу на финальном участке
        // невыполним — сосед исключается, но сквозь его интерьер идти нельзя (вход через свою южную стену)
        Variant v = solve(base()
                .restriction(restriction("a", RestrictionRules.OKS, box(0, 100, 20, 120)))
                .restriction(restriction("b", RestrictionRules.OKS, box(20, 100, 40, 120)))
                .connectionPoint(new ConnectionPoint("t", Crs.UTM.createPoint(c(19.5, 103)), 12.0)).build());
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty(), "точка у пристроенного корпуса подключена");
        NewPipe last = v.getPipes().stream().filter(p -> "t".equals(p.getEndNodeId())).findFirst().orElseThrow();
        Coordinate[] cs = last.getGeometry().getCoordinates();
        Coordinate entry = cs[cs.length - 2];
        // ближайшая стена — общая с соседом (0.5 м на восток), сквозь него нельзя → вход с юга
        assertTrue(entry.y < 100, "вход через южную стену: " + entry);
        assertTrue(ru.lct.heat.geometry.GeomUtil.segment(entry, cs[cs.length - 1])
                .intersection(box(20, 100, 40, 120)).getLength() < 1e-6, "не сквозь соседний корпус");
    }
}
