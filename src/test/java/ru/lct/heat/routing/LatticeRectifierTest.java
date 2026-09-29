package ru.lct.heat.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.ObstacleMap;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.ref.DiameterSpec;
import ru.lct.heat.model.ref.DiameterTable;
import ru.lct.heat.model.ref.RestrictionRules;
import ru.lct.heat.model.ref.RuleProfile;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heat.support.TestGeoms.box;
import static ru.lct.heat.support.TestGeoms.c;
import static ru.lct.heat.support.TestGeoms.restriction;

class LatticeRectifierTest {

    private static final DiameterSpec DN100 = DiameterTable.byDiameter(100).orElseThrow();

    private static NetEdge edge(NewNetwork net, InputModel in, Coordinate... cs) {
        ObstacleMap map = ObstacleMap.build(in, DN100, 0.5, 20);
        NetNode a = net.addNode("ch", cs[0], NetNode.Kind.BRANCH_CHAMBER);
        NetNode b = net.addNode("oks", cs[cs.length - 1], NetNode.Kind.OKS_POINT);
        List<ru.lct.heat.geometry.SegmentCheck> checks = new ArrayList<>();
        for (int i = 0; i + 1 < cs.length; i++) {
            checks.add(map.check(cs[i], cs[i + 1], Collections.emptySet()));
        }
        NetEdge e = net.addEdge(a, b, new ArrayList<>(Arrays.asList(cs)), checks);
        e.setDiameter(DN100);
        return e;
    }

    private static void assertStandard(NetEdge e, double thetaDeg) {
        List<Coordinate> cs = e.getCoords();
        for (int i = 0; i + 1 < cs.size(); i++) {
            double dir = Math.toDegrees(Math.atan2(cs.get(i + 1).y - cs.get(i).y, cs.get(i + 1).x - cs.get(i).x));
            double k = (dir - thetaDeg) / 45;
            assertEquals(Math.round(k), k, 1e-3, "направление отрезка " + i + " = " + dir + "°");
        }
    }

    @Test
    void obliqueSegmentBecomesTwoStandardSegments() {
        // свободное поле, без застройки: θ = направление самого длинного отрезка (0°); излом 33.7° → 45°/0°
        InputModel in = InputModel.builder().build();
        NewNetwork net = new NewNetwork();
        NetEdge e = edge(net, in, c(0, 0), c(50, 0), c(80, 20));
        RoutingParams params = new RoutingParams();
        // по умолчанию выпрямление не должно стоить длины (length весит 0.3 в score); здесь излом 33.7°
        // приводится к 45°/0° ценой 1.5 % длины — допускаем это явно
        params.setRectifyMaxExtraFrac(0.05);
        int done = new LatticeRectifier(new MapCache(in, params), new ApproachPlanner(in), params, in,
                RuleProfile.heat()).rectify(net);
        assertEquals(1, done);
        assertStandard(e, 0);
        assertTrue(e.getCoords().size() <= 3, "лишние повороты: " + e.getCoords());
    }

    @Test
    void alignsToBuildingOrientationAndKeepsClearance() {
        // здание, повернутое на 20°, рядом с трассой: направления трассы — семейство 20° + k·45°
        InputModel in = InputModel.builder()
                .restriction(restriction("b", RestrictionRules.OKS,
                        org.locationtech.jts.geom.util.AffineTransformation.rotationInstance(Math.toRadians(20))
                                .transform(box(20, 15, 60, 35))))
                .build();
        NewNetwork net = new NewNetwork();
        // исходная ломаная с двумя нестандартными изломами; выпрямление не должно добавлять поворотов
        NetEdge e = edge(net, in, c(0, 0), c(35, -12), c(62, -11), c(90, 5));
        RoutingParams params = new RoutingParams();
        params.setRectifyMaxExtraFrac(0.15);
        int done = new LatticeRectifier(new MapCache(in, params), new ApproachPlanner(in), params, in,
                RuleProfile.heat()).rectify(net);
        assertEquals(1, done);
        assertStandard(e, 20.0);
        ObstacleMap map = ObstacleMap.build(in, DN100, 0.5, 20);
        List<Coordinate> cs = e.getCoords();
        for (int i = 0; i + 1 < cs.size(); i++) {
            assertTrue(map.check(cs.get(i), cs.get(i + 1), Collections.emptySet()).isFree());
        }
        assertTrue(cs.size() <= 4, "поворотов не больше исходных двух: " + cs);
        double old = c(0, 0).distance(c(35, -12)) + c(35, -12).distance(c(62, -11)) + c(62, -11).distance(c(90, 5));
        assertTrue(GeomUtil.line(cs).getLength() <= 1.15 * old + 2);
    }
}
