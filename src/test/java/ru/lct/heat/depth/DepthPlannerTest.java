package ru.lct.heat.depth;

import org.junit.jupiter.api.Test;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ExistingChamber;
import ru.lct.heat.model.ExistingPipe;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.out.NewPipe;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.model.ref.RestrictionRules;
import ru.lct.heat.routing.HeatRoutingEngine;
import ru.lct.heat.routing.RoutingParams;
import ru.lct.heat.routing.SolveOptions;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heat.support.TestGeoms.box;
import static ru.lct.heat.support.TestGeoms.c;
import static ru.lct.heat.support.TestGeoms.line;
import static ru.lct.heat.support.TestGeoms.restriction;

class DepthPlannerTest {

    private static SolveOptions depth3d() {
        return SolveOptions.builder().mode(SolveOptions.Mode.DEPTH_3D).maxVariants(1).build();
    }

    @Test
    void envelopeRampsAtMaxSlopeAndMergesCloseCrossings() {
        // §5: два требования «выше» h ≤ 2.4 на [50,54] и [60,64]; между ними возврата на 3.0 нет
        List<DepthPlanner.Requirement> reqs = new ArrayList<>();
        reqs.add(DepthPlanner.Requirement.fixed(0, 3.0));
        reqs.add(new DepthPlanner.Requirement(50, 54, 2.4, 0, "gas"));
        reqs.add(new DepthPlanner.Requirement(60, 64, 2.4, 0, "gas"));
        assertEquals(3.0, DepthPlanner.profile(0, reqs), 1e-9);
        assertEquals(3.0, DepthPlanner.profile(40, reqs), 1e-9);
        assertEquals(2.5, DepthPlanner.profile(49, reqs), 1e-9);   // подъём с уклоном 0.1 начался на 44 м
        assertEquals(2.4, DepthPlanner.profile(52, reqs), 1e-9);
        assertEquals(2.6, DepthPlanner.profile(56, reqs), 1e-9);   // спуск не успевает вернуться на 3.0
        assertEquals(2.7, DepthPlanner.profile(57, reqs), 1e-9);
        assertEquals(2.4, DepthPlanner.profile(62, reqs), 1e-9);
        assertEquals(3.0, DepthPlanner.profile(80, reqs), 1e-9);
        List<Double> breaks = DepthPlanner.breakpoints(reqs, 200);
        // 44 (начало подъёма), 50, 54, 57 (встреча спуска и подъёма), 60, 64, 70 (конец спуска)
        assertEquals(List.of(44.0, 50.0, 54.0, 57.0, 60.0, 64.0, 70.0), breaks);
    }

    @Test
    void gasCrossingGoesAboveWithTechNodesAndNoExtraCost() {
        // газопровод поперёк трассы: верх газа 2.8, просвет 0.2, ДУ100 высота 0.18 → h ≤ 2.42 в спецзоне ±2 м
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-100, 0, 100, 0), 300))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))))
                .restriction(restriction("gas", RestrictionRules.GAS_PIPELINE, line(-500, 50, 500, 50)))
                .connectionPoint(new ConnectionPoint("oks1", Crs.UTM.createPoint(c(0, 100)), 20.0))
                .build();
        RoutingParams params = new RoutingParams();
        Variant v3d = new HeatRoutingEngine(params).solve(in, depth3d()).get(0);
        Variant v2d = new HeatRoutingEngine(params).solve(in, SolveOptions.defaults()).get(0);

        double total3d = v3d.getPipes().stream().mapToDouble(NewPipe::getCost).sum();
        double total2d = v2d.getPipes().stream().mapToDouble(NewPipe::getCost).sum();
        assertEquals(total2d, total3d, 1.0, "проход выше: Kгл = 1, стоимость как в 2D");
        assertNull(v2d.getPipes().get(0).getDepthStart());

        NewPipe special = v3d.getPipes().stream().filter(p -> p.getKSpecial() > 1).findFirst().orElseThrow();
        // 2.8 − 0.2 − 0.18 (высота DN100) − запас 0.01 на округление
        assertEquals(2.41, special.getDepthStart(), 1e-6);
        assertEquals(2.41, special.getDepthEnd(), 1e-6);
        NewPipe first = v3d.getPipes().stream().filter(p -> "ch1".equals(p.getStartNodeId())).findFirst().orElseThrow();
        assertEquals(3.0, first.getDepthStart(), 1e-6);
        assertEquals(3.0, first.getDepthEnd(), 1e-6);
        NewPipe last = v3d.getPipes().stream().filter(p -> "oks1".equals(p.getEndNodeId())).findFirst().orElseThrow();
        assertEquals(3.0, last.getDepthEnd(), 1e-6);
        // подъём на 0.59 м при уклоне 0.1 → 5.9 м; участки: base 3.0 | подъём | special 2.41 | спуск | base 3.0
        NewPipe ramp = v3d.getPipes().stream().filter(p -> p.getDepthStart() != null
                && Math.abs(p.getDepthStart() - p.getDepthEnd()) > 0.01).findFirst().orElseThrow();
        assertEquals(5.9, ramp.getLength(), 0.05);
        assertEquals(5, v3d.getPipes().size());
        assertEquals(4, v3d.getTechnicalNodes().size());
        for (NewPipe p : v3d.getPipes()) {
            assertNotNull(p.getDepthStart());
            assertTrue(p.getDepthStart() >= 0.7 && p.getDepthStart() <= 3.0);
            assertEquals(1.0, p.getKDepth(), 1e-9);
        }
    }

    @Test
    void roadCrossingKeepsBaseDepth() {
        // дорога: под объектом, верх ≥ 1.0 м — обычные 3.0 м подходят, профиль не меняется
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-100, 0, 100, 0), 300))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))))
                .restriction(restriction("road", RestrictionRules.ROAD, box(-500, 40, 500, 60)))
                .connectionPoint(new ConnectionPoint("oks1", Crs.UTM.createPoint(c(0, 100)), 20.0))
                .build();
        Variant v = new HeatRoutingEngine(new RoutingParams()).solve(in, depth3d()).get(0);
        assertEquals(3, v.getPipes().size());
        for (NewPipe p : v.getPipes()) {
            assertEquals(3.0, p.getDepthStart(), 1e-9);
            assertEquals(3.0, p.getDepthEnd(), 1e-9);
        }
    }

    @Test
    void twoTramTracksInOneSectionDoNotCrash() {
        // два параллельных трамвайных пути (два объекта одного типа) в одном спецучастке — планировщик глубины
        // не должен падать на дубликате типа; профиль под трамваем — обычные 3.0 м (верх ≥ 1.2 м)
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-100, 0, 100, 0), 300))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))))
                .restriction(restriction("t1", RestrictionRules.TRAM_TRACKS, box(-500, 40, 500, 43)))
                .restriction(restriction("t2", RestrictionRules.TRAM_TRACKS, box(-500, 46, 500, 49)))
                .connectionPoint(new ConnectionPoint("oks1", Crs.UTM.createPoint(c(0, 100)), 20.0))
                .build();
        Variant v = new HeatRoutingEngine(new RoutingParams()).solve(in, depth3d()).get(0);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        assertTrue(v.getPipes().stream().anyMatch(p -> p.getLayingMethod() == ru.lct.heat.model.LayingMethod.SPECIAL));
        for (NewPipe p : v.getPipes()) {
            assertEquals(3.0, p.getDepthStart(), 1e-9);
            assertEquals(3.0, p.getDepthEnd(), 1e-9);
        }
    }

    @Test
    void thickPipeGoesUnderTheCableAndPaysDepthFactor() {
        // §5: ДУ1400 (высота 1.6 м, расход 18 000 т/ч) над кабелем не помещается (2.7 − 0.5 − 1.6 = 0.6 < 0.7 м),
        // значит проходим ниже: верх габарита 2.7 + 0.2 + 0.5 + запас = 3.41 м → Kгл = 1 + 0.1·0.41
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-200, 0, 200, 0), 1400))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))))
                .restriction(restriction("cable", "power_cable", line(-200, 50, 200, 50)))
                .connectionPoint(new ConnectionPoint("oks1", Crs.UTM.createPoint(c(0, 100)), 18_000.0))
                .build();
        Variant v = new HeatRoutingEngine(new RoutingParams()).solve(in, depth3d()).get(0);
        NewPipe under = v.getPipes().stream().filter(p -> p.getKSpecial() > 1).findFirst().orElseThrow();
        assertEquals(3.41, under.getDepthStart(), 0.02);
        assertEquals(1.0 + 0.1 * 0.41, under.getKDepth(), 0.005);
        assertTrue(v.getPipes().stream().anyMatch(p -> p.getKDepth() > 1.0), "есть удорожание по глубине");
    }
}
