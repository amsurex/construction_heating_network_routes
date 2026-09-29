package ru.lct.heat.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.ObstacleMap;
import ru.lct.heat.geometry.SegmentCheck;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.LayingMethod;
import ru.lct.heat.model.ref.DiameterSpec;
import ru.lct.heat.model.ref.DiameterTable;
import ru.lct.heat.model.ref.RestrictionRules;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heat.support.TestGeoms.c;
import static ru.lct.heat.support.TestGeoms.line;
import static ru.lct.heat.support.TestGeoms.restriction;

class SpecialSplitterTest {

    private static final DiameterSpec DN100 = DiameterTable.byDiameter(100).orElseThrow();

    @Test
    void shallowCrossingExtendsSpecialSectionUntilClearanceIsMet() {
        // кабель по оси X; трасса пересекает его под ~15°: за ±2 м от точки пересечения труба всё ещё
        // ближе минимального расстояния (2.0 + 0.255 + 0.1 = 2.355 м) — спецучасток продлевается до выхода
        // из зоны отступа, а участки base по обе стороны отступ выдерживают
        InputModel in = InputModel.builder()
                .restriction(restriction("cable", RestrictionRules.POWER_CABLE, line(-100, 0, 100, 0)))
                .build();
        ObstacleMap map = ObstacleMap.build(in, DN100, 0.05, 10);
        Coordinate a = c(-30, -8);
        Coordinate b = c(30, 8);
        SegmentCheck check = map.check(a, b, Collections.emptySet());
        assertTrue(check.isFree());
        assertEquals(1, check.getCrossings().size());

        NewNetwork net = new NewNetwork();
        NetNode from = net.addNode("ch", a, NetNode.Kind.EXISTING_CHAMBER);
        NetNode to = net.addNode("oks", b, NetNode.Kind.OKS_POINT);
        NetEdge e = net.addEdge(from, to, Arrays.asList(a, b), Collections.singletonList(check));
        e.setDiameter(DN100);
        new SpecialSplitter().split(net);

        List<NetEdge> edges = net.getEdges();
        assertEquals(3, edges.size());
        NetEdge special = edges.stream().filter(x -> x.getLayingMethod() == LayingMethod.SPECIAL).findFirst().orElseThrow();
        double clearance = 2.0 + DN100.halfWidth() + 0.1;
        // вдоль трассы: |y| < clearance ⇔ расстояние от пересечения < clearance / sin(15°)
        double expected = 2 * clearance / Math.sin(Math.atan2(16, 60));
        assertEquals(expected, special.length(), 0.05);
        assertEquals(1.15, special.getKSpecial());
        for (NetEdge x : edges) {
            if (x.getLayingMethod() == LayingMethod.BASE) {
                double d = GeomUtil.line(x.getCoords()).distance(in.getRestrictions().get(0).getGeometry());
                assertTrue(d >= clearance - 1e-6, "base-участок ближе отступа: " + d);
            }
        }
    }

    @Test
    void perpendicularCrossingKeepsTwoMetreMarginWhenClearanceIsSmaller() {
        // теплосеть DN50 пересекает кабель под 90°: отступ 2.0 + 0.2 + 0.1 = 2.3 м > 2 м → спецучасток 2 × 2.3 м
        DiameterSpec dn50 = DiameterTable.byDiameter(50).orElseThrow();
        InputModel in = InputModel.builder()
                .restriction(restriction("cable", RestrictionRules.POWER_CABLE, line(-100, 0, 100, 0)))
                .build();
        ObstacleMap map = ObstacleMap.build(in, dn50, 0.05, 10);
        Coordinate a = c(0, -20);
        Coordinate b = c(0, 20);
        SegmentCheck check = map.check(a, b, Collections.emptySet());
        NewNetwork net = new NewNetwork();
        NetEdge e = net.addEdge(net.addNode("ch", a, NetNode.Kind.EXISTING_CHAMBER),
                net.addNode("oks", b, NetNode.Kind.OKS_POINT), Arrays.asList(a, b), Collections.singletonList(check));
        e.setDiameter(dn50);
        new SpecialSplitter().split(net);
        NetEdge special = net.getEdges().stream().filter(x -> x.getLayingMethod() == LayingMethod.SPECIAL).findFirst().orElseThrow();
        assertEquals(2 * (2.0 + dn50.halfWidth() + 0.1), special.length(), 0.05);
    }

    @Test
    void crossingTwoRoadsAtTheirCornerKeepsOneSpecialSetPerPiece() {
        // две пересекающиеся дороги (полосы 14 м); трасса под 45° проходит внутренний угол их объединения:
        // после выхода из первой дороги 3.2 м вне полигонов, затем вход во вторую. Куски: road / road,road / road,
        // без смены набора внутри куска, и base-участки выдерживают отступ
        DiameterSpec dn700 = DiameterTable.byDiameter(700).orElseThrow();
        InputModel in = InputModel.builder()
                .restriction(restriction("h", RestrictionRules.ROAD, ru.lct.heat.support.TestGeoms.box(-1000, 0, 1000, 14)))
                .restriction(restriction("v", RestrictionRules.ROAD, ru.lct.heat.support.TestGeoms.box(20, -1000, 34, 1000)))
                .build();
        ObstacleMap map = ObstacleMap.build(in, dn700, 0.05, 10);
        Coordinate a = c(-10, -12);
        Coordinate b = c(60, 58);
        SegmentCheck check = map.check(a, b, Collections.emptySet());
        assertTrue(check.isFree(), check.getReason());
        NewNetwork net = new NewNetwork();
        NetEdge e = net.addEdge(net.addNode("ch", a, NetNode.Kind.EXISTING_CHAMBER),
                net.addNode("oks", b, NetNode.Kind.OKS_POINT), Arrays.asList(a, b), Collections.singletonList(check));
        e.setDiameter(dn700);
        new SpecialSplitter().split(net);
        double clearance = 1.5 + dn700.halfWidth();
        for (NetEdge x : net.getEdges()) {
            org.locationtech.jts.geom.LineString l = GeomUtil.line(x.getCoords());
            if (x.getLayingMethod() == LayingMethod.BASE) {
                for (var r : in.getRestrictions()) {
                    assertTrue(l.distance(r.getGeometry()) >= clearance - 1e-6, "base ближе отступа к " + r.getId());
                }
            }
        }
        assertTrue(net.getEdges().size() >= 3);
    }
}
