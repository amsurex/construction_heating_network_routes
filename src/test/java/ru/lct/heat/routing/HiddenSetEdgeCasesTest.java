package ru.lct.heat.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ExistingChamber;
import ru.lct.heat.model.ExistingPipe;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.out.NewPipe;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.model.ref.RestrictionRules;
import ru.lct.heat.validation.OutputValidator;
import ru.lct.heat.io.OutputGeoJsonReader;
import ru.lct.heat.validation.ValidationReport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heat.support.TestGeoms.box;
import static ru.lct.heat.support.TestGeoms.c;
import static ru.lct.heat.support.TestGeoms.line;
import static ru.lct.heat.support.TestGeoms.restriction;

/**
 * Краевые случаи, которых нет в конкурсном датасете, но которые может содержать проверочный набор
 * «той же структуры»: результат должен проходить независимый валидатор.
 */
class HiddenSetEdgeCasesTest {

    private static final OutputValidator VALIDATOR = new OutputValidator(new OutputGeoJsonReader());

    private static List<Variant> solve(InputModel in) {
        return new HeatRoutingEngine(new RoutingParams()).solve(in, SolveOptions.defaults());
    }

    private static void assertValid(InputModel in, List<Variant> variants) {
        ValidationReport report = VALIDATOR.validate(in, variants, SolveOptions.Mode.PLAN_2D);
        assertTrue(report.isValid(), report.getDiagnostics().toString());
    }

    private static InputModel.InputModelBuilder base() {
        return InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-300, 0, 300, 0), 400))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))));
    }

    @Test
    void severalPointsInOneBuildingEachGetsItsOwnFinalSegment() {
        // §2.2: несколько точек в одном полигоне — каждая самостоятельная цель со своим финальным участком
        InputModel in = base()
                .restriction(restriction("b", RestrictionRules.OKS, box(-60, 100, 60, 180)))
                .connectionPoint(new ConnectionPoint("a", Crs.UTM.createPoint(c(-40, 110)), 10.0))
                .connectionPoint(new ConnectionPoint("b1", Crs.UTM.createPoint(c(40, 110)), 10.0))
                .connectionPoint(new ConnectionPoint("c1", Crs.UTM.createPoint(c(0, 170)), 10.0))
                .build();
        List<Variant> variants = solve(in);
        Variant v = variants.get(0);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty(), v.getSummary().getUnconnectedOksIds().toString());
        for (Object id : List.of("a", "b1", "c1")) {
            assertEquals(1, v.getPipes().stream().filter(p -> id.equals(p.getEndNodeId())).count(),
                    "ровно один финальный участок к точке " + id);
        }
        assertValid(in, variants);
    }

    @Test
    void buildingWithCourtyardHoleIsNotEnteredThroughTheHole() {
        // здание-каре с внутренним двором (полигон с дыркой): точка во дворе — двор проходим,
        // здание нет; трасса должна попасть во двор через разрыв, а не сквозь корпус
        Geometry ring = box(-80, 80, 80, 240).difference(box(-40, 120, 40, 200));
        Geometry withGap = ring.difference(box(-10, 200, 10, 260)); // проезд во двор с севера
        InputModel in = base()
                .restriction(restriction("ring", RestrictionRules.OKS, withGap))
                .connectionPoint(new ConnectionPoint("yard", Crs.UTM.createPoint(c(0, 160)), 15.0))
                .build();
        List<Variant> variants = solve(in);
        assertValid(in, variants);
    }

    @Test
    void forbiddenRestrictionWithLineGeometryIsRespected() {
        // запретное ограничение линейной геометрии (в датасете все полигоны, но тип допускает LineString)
        InputModel in = base()
                .restriction(restriction("fence", RestrictionRules.PROHIBITED_SITE, line(-200, 60, 200, 60)))
                .connectionPoint(new ConnectionPoint("t", Crs.UTM.createPoint(c(0, 140)), 15.0))
                .build();
        List<Variant> variants = solve(in);
        Variant v = variants.get(0);
        if (v.getSummary().getUnconnectedOksIds().isEmpty()) {
            for (NewPipe p : v.getPipes()) {
                assertTrue(p.getGeometry().distance(in.getRestrictions().get(0).getGeometry()) > 0.5,
                        "участок " + p.getId() + " пересекает линейный запрет");
            }
        }
        assertValid(in, variants);
    }

    @Test
    void flowAboveTheTableGivesPenaltyNotCrash() {
        // расход выше пропускной способности ДУ1400 — движок не должен падать
        InputModel in = base()
                .connectionPoint(new ConnectionPoint("huge", Crs.UTM.createPoint(c(0, 120)), 30_000.0))
                .build();
        List<Variant> variants = solve(in);
        assertTrue(!variants.isEmpty());
        assertEquals(1400, variants.get(0).getPipes().get(0).getDiameter());
    }

    @Test
    void existingNetworkFarAwayStillConnects() {
        // точки в километре от существующей сети: подключение есть, ДУ поднимается по предельной длине
        InputModel in = base()
                .connectionPoint(new ConnectionPoint("far", Crs.UTM.createPoint(c(0, 1200)), 4.0))
                .build();
        List<Variant> variants = solve(in);
        Variant v = variants.get(0);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        assertTrue(v.getPipes().stream().mapToInt(NewPipe::getDiameter).max().orElseThrow() >= 65,
                "по предельной длине ДУ поднят выше минимального по расходу");
        assertValid(in, variants);
    }

    @Test
    void narrowPassageBetweenBuildingsIsUsedOnlyIfItFits() {
        // проход между двумя зданиями шириной 11 м: DN100 (5 + 0.255 + 5 + 0.255 = 10.51) проходит,
        // а при ширине 9 м — нет, трасса обязана обойти
        for (double gap : new double[]{11.0, 9.0}) {
            InputModel in = base()
                    .restriction(restriction("l", RestrictionRules.OKS, box(-200, 60, -gap / 2, 140)))
                    .restriction(restriction("r", RestrictionRules.OKS, box(gap / 2, 60, 200, 140)))
                    .connectionPoint(new ConnectionPoint("t", Crs.UTM.createPoint(c(0, 200)), 15.0))
                    .build();
            List<Variant> variants = solve(in);
            Variant v = variants.get(0);
            assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty(), "проход " + gap + " м: точка не подключена");
            double throughGap = 0;
            for (NewPipe pipe : v.getPipes()) {
                throughGap += pipe.getGeometry().intersection(box(-gap / 2, 60, gap / 2, 140)).getLength();
            }
            if (gap > 10.6) {
                assertTrue(throughGap > 50, "в проход 11 м трасса должна идти напрямую: " + throughGap);
            } else {
                assertEquals(0.0, throughGap, 1e-6, "в проход 9 м трасса не помещается");
            }
            assertValid(in, variants);
        }
    }

    @Test
    void pointInsideRoadCorridorIsReachedAsSpecialCrossing() {
        // точка подключения внутри полосы дороги (киоск/павильон): спецучасток может заканчиваться в точке
        InputModel in = base()
                .restriction(restriction("road", RestrictionRules.ROAD, box(-300, 80, 300, 120)))
                .connectionPoint(new ConnectionPoint("t", Crs.UTM.createPoint(c(0, 100)), 12.0))
                .build();
        List<Variant> variants = solve(in);
        Variant v = variants.get(0);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        assertTrue(v.getPipes().stream().anyMatch(p -> p.getKSpecial() > 1.0), "подход к точке — спецпроход");
        assertValid(in, variants);
    }

    @Test
    void multiLineStringRestrictionIsHandled() {
        // ограничение MultiLineString (в датасете таких нет, но тип допускает)
        Geometry multi = Crs.UTM.createMultiLineString(new org.locationtech.jts.geom.LineString[]{
                line(-200, 70, 200, 70), line(-200, 150, 200, 150)});
        InputModel in = base()
                .restriction(restriction("cables", RestrictionRules.POWER_CABLE, multi))
                .connectionPoint(new ConnectionPoint("t", Crs.UTM.createPoint(c(0, 220)), 12.0))
                .build();
        List<Variant> variants = solve(in);
        Variant v = variants.get(0);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        assertEquals(2, v.getPipes().stream().filter(p -> p.getKSpecial() > 1.0).count(),
                "две нитки кабеля — два спецучастка");
        assertValid(in, variants);
    }
}
