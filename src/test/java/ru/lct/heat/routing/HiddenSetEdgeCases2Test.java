package ru.lct.heat.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.io.OutputGeoJsonReader;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ExistingChamber;
import ru.lct.heat.model.ExistingPipe;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.out.NewPipe;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.model.ref.RestrictionRules;
import ru.lct.heat.validation.OutputValidator;
import ru.lct.heat.validation.ValidationReport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heat.support.TestGeoms.box;
import static ru.lct.heat.support.TestGeoms.c;
import static ru.lct.heat.support.TestGeoms.line;
import static ru.lct.heat.support.TestGeoms.restriction;

/**
 * Вторая партия краевых случаев для проверочного набора «той же структуры»: топология существующей
 * сети (ветвление без камеры, несколько несвязных кусков), точка ОКС прямо на трубе, неизвестный
 * тип ограничения, граница правила 10 м из §2.4. Везде ожидание одно: выдача проходит независимый
 * валидатор, движок не падает, вырожденной геометрии нет.
 */
class HiddenSetEdgeCases2Test {

    private static final OutputValidator VALIDATOR = new OutputValidator(new OutputGeoJsonReader());

    private static List<Variant> solve(InputModel in) {
        return new HeatRoutingEngine(new RoutingParams()).solve(in, SolveOptions.defaults());
    }

    private static void assertValid(InputModel in, List<Variant> variants) {
        ValidationReport report = VALIDATOR.validate(in, variants, SolveOptions.Mode.PLAN_2D);
        assertTrue(report.isValid(), report.getDiagnostics().toString());
        for (Variant v : variants) {
            for (NewPipe p : v.getPipes()) {
                assertTrue(p.getLength() > 0.01, "нулевой участок " + p.getId());
                assertTrue(p.getGeometry().isSimple(), "самопересечение участка " + p.getId());
            }
        }
    }

    private static InputModel.InputModelBuilder base() {
        return InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-300, 0, 300, 0), 400))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))));
    }

    @Test
    void oksPointExactlyOnTheExistingPipeGivesNoZeroLengthSection() {
        // точка подключения задана ровно на существующей трубе. Участка нулевой длины в выдаче быть
        // не должно (невалидная геометрия): либо трасса идёт к другому месту присоединения, либо
        // точка остаётся неподключённой с указанием причины — но выдача обязана быть валидной
        InputModel in = base()
                .connectionPoint(new ConnectionPoint("onPipe", Crs.UTM.createPoint(c(120, 0)), 12.0))
                .build();
        List<Variant> variants = solve(in);
        assertTrue(!variants.isEmpty());
        assertValid(in, variants);
    }

    @Test
    void existingNetworkBranchesWithoutAChamberAtTheJunction() {
        // Y-образная существующая сеть, в точке ветвления камеры нет: новые участки всё равно
        // присоединяются корректно (в §2.4 камера обязательна только для новых разветвлений)
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-300, 0, 0, 0), 400))
                .pipe(new ExistingPipe("p2", line(0, 0, 250, 120), 300))
                .pipe(new ExistingPipe("p3", line(0, 0, 250, -120), 300))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(-300, 0))))
                .connectionPoint(new ConnectionPoint("a", Crs.UTM.createPoint(c(60, 90)), 20.0))
                .connectionPoint(new ConnectionPoint("b", Crs.UTM.createPoint(c(-80, 110)), 15.0))
                .build();
        List<Variant> variants = solve(in);
        assertTrue(variants.get(0).getSummary().getUnconnectedOksIds().isEmpty());
        assertValid(in, variants);
    }

    @Test
    void twoDisconnectedPiecesOfExistingNetworkAreBothUsable() {
        // два несвязных куска существующей сети: каждая точка присоединяется к своему
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("west", line(-600, 0, -300, 0), 300))
                .pipe(new ExistingPipe("east", line(300, 0, 600, 0), 300))
                .chamber(new ExistingChamber("chW", Crs.UTM.createPoint(c(-300, 0))))
                .chamber(new ExistingChamber("chE", Crs.UTM.createPoint(c(300, 0))))
                .connectionPoint(new ConnectionPoint("w", Crs.UTM.createPoint(c(-400, 120)), 10.0))
                .connectionPoint(new ConnectionPoint("e", Crs.UTM.createPoint(c(400, 120)), 10.0))
                .build();
        List<Variant> variants = solve(in);
        Variant v = variants.get(0);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        assertValid(in, variants);
    }

    @Test
    void unknownRestrictionTypeIsTreatedAsForbidden() {
        // тип, которого нет в таблице 2 (метро): консервативно — запрет с отступом 1 м
        Geometry wall = box(-200, 60, 200, 90);
        InputModel in = base()
                .restriction(restriction("metro", "metro_tunnel", wall))
                .connectionPoint(new ConnectionPoint("t", Crs.UTM.createPoint(c(0, 150)), 15.0))
                .build();
        List<Variant> variants = solve(in);
        Variant v = variants.get(0);
        if (v.getSummary().getUnconnectedOksIds().isEmpty()) {
            for (NewPipe p : v.getPipes()) {
                assertTrue(p.getGeometry().distance(wall) > 0.5,
                        "участок " + p.getId() + " заходит в зону неизвестного ограничения");
            }
        }
        assertValid(in, variants);
    }

    @Test
    void chamberExactlyTenMetresFromTheJoinPointIsAtTheRuleBoundary() {
        // §2.4: камера ровно в 10 м от места присоединения — граница правила; выдача обязана быть
        // согласованной (либо врезка в камеру, либо новая камера), без ошибок валидатора
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-300, 0, 300, 0), 400))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(10, 0))))
                .connectionPoint(new ConnectionPoint("t", Crs.UTM.createPoint(c(0, 120)), 12.0))
                .build();
        List<Variant> variants = solve(in);
        assertValid(in, variants);
    }

    @Test
    void tieInAreaCoveredByForbiddenPolygonForcesAnotherJoinPoint() {
        // запретная зона накрывает участок существующей сети рядом с камерой: присоединяться
        // приходится в другом месте, но подключение остаётся
        InputModel in = base()
                .restriction(restriction("zone", RestrictionRules.PROHIBITED_SITE, box(-120, -40, 120, 40)))
                .connectionPoint(new ConnectionPoint("t", Crs.UTM.createPoint(c(0, 150)), 12.0))
                .build();
        List<Variant> variants = solve(in);
        assertValid(in, variants);
    }

    @Test
    void overlappingBuildingsAreOneComplexForTheNearestBoundaryRule() {
        // два перекрывающихся контура ОКС (крест: широкий корпус и узкий, оба содержат точку).
        // §2.2 «ближайшая граница полигона» у каждого контура своя: у узкого — стена в 6 м, но выход
        // через неё оставил бы трассу ещё на 34 м внутри широкого. Граница комплекса (объединения)
        // ближе всего во внутреннем углу креста — 11.7 м, туда трасса и должна выходить.
        Geometry wide = box(-40, -10, 40, 10);
        Geometry tall = box(-6, -30, 6, 30);
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-300, 200, 300, 200), 400))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 200))))
                .restriction(restriction("wide", RestrictionRules.OKS, wide))
                .restriction(restriction("tall", RestrictionRules.OKS, tall))
                .connectionPoint(new ConnectionPoint("inside", Crs.UTM.createPoint(c(0, 0)), 10.0))
                .build();
        List<Variant> variants = solve(in);
        Variant v = variants.get(0);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty(), "точка должна подключиться");
        NewPipe last = v.getPipes().stream().filter(x -> "inside".equals(x.getEndNodeId())).findFirst().orElseThrow();
        Geometry complex = wide.union(tall);
        double inside = last.getGeometry().intersection(complex).getLength();
        double nearest = complex.getBoundary().distance(Crs.UTM.createPoint(c(0, 0)));
        assertTrue(inside <= nearest + 0.2,
                "финальный участок должен выходить через ближайшую границу комплекса (" + nearest
                        + " м), а внутри него оказалось " + inside + " м");
        assertValid(in, variants);
    }

    @Test
    void specialCrossingEndsWhereItsZoneEnds_evenInsideTheTargetBuilding() {
        // дорога вплотную к фасаду: её спецзона дотягивается внутрь здания, а дальше финальный участок
        // пересекает газопровод. §4 отмеряет спецучасток вдоль трассы, поэтому кусок у точки ОКС
        // относится к газу (K=1.25), а не к дороге (K=1.60) — техузел внутри здания этому не мешает.
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-300, 200, 300, 200), 400))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 200))))
                .restriction(restriction("house", RestrictionRules.OKS, box(-30, -40, 30, 0)))
                .restriction(restriction("road", RestrictionRules.ROAD, box(-200, 2, 200, 10)))
                .restriction(restriction("gas", RestrictionRules.GAS_PIPELINE, line(-200, -6, 200, -6)))
                .connectionPoint(new ConnectionPoint("inside", Crs.UTM.createPoint(c(0, -12)), 10.0))
                .build();
        List<Variant> variants = solve(in);
        Variant v = variants.get(0);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty(), "точка должна подключиться");
        NewPipe last = v.getPipes().stream().filter(x -> "inside".equals(x.getEndNodeId())).findFirst().orElseThrow();
        assertTrue(last.getKSpecial() < 1.6 - 1e-9,
                "кусок у точки ОКС не пересекает дорогу, K должен быть не дорожным: " + last.getKSpecial());
        assertTrue(v.getPipes().stream().anyMatch(x -> Math.abs(x.getKSpecial() - 1.6) < 1e-9),
                "пересечение дороги в выдаче должно быть");
        assertValid(in, variants);
    }

    @Test
    void oksPointOnTheExistingPipeIsATieInNotACrossing() {
        // точка ОКС задана ровно на существующей трубе, а подойти к ней можно только через дорогу:
        // последний кусок трассы — спецпроход, и заканчивается он на трубе. Это врезка, а не
        // пересечение теплосети: отступ к этой трубе на таком куске неприменим (§2.4), иначе
        // валидатор потребовал бы ещё один спецучасток в двух метрах от точки.
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-400, 0, 400, 0), 400))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(-400, 0))))
                .restriction(restriction("road", RestrictionRules.ROAD, box(-300, 40, 300, 60)))
                .restriction(restriction("house", RestrictionRules.OKS, box(60, -40, 140, 40)))
                .connectionPoint(new ConnectionPoint("onPipe", Crs.UTM.createPoint(c(100, 0)), 14.0))
                .build();
        List<Variant> variants = solve(in);
        Variant v = variants.get(0);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty(), "точка должна подключиться");
        NewPipe last = v.getPipes().stream().filter(x -> "onPipe".equals(x.getEndNodeId())).findFirst().orElseThrow();
        assertTrue(last.getGeometry().getEndPoint().distance(in.getPipes().get(0).getGeometry()) < 0.02,
                "последний участок должен заканчиваться на существующей трубе");
        assertValid(in, variants);
    }

    @Test
    void mirroredPointsGiveDeterministicOutput() {
        // симметричный вход: два прогона подряд должны дать одинаковую выдачу (детерминизм)
        InputModel in = base()
                .connectionPoint(new ConnectionPoint("l", Crs.UTM.createPoint(c(-100, 120)), 20.0))
                .connectionPoint(new ConnectionPoint("r", Crs.UTM.createPoint(c(100, 120)), 20.0))
                .build();
        List<Variant> a = solve(in);
        List<Variant> b = solve(in);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).getSummary().getScore(), b.get(i).getSummary().getScore(), 1e-9,
                    "вариант " + i + " недетерминирован");
        }
        assertValid(in, a);
    }
}
