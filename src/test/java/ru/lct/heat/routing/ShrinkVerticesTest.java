package ru.lct.heat.routing;

import org.junit.jupiter.api.Test;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ExistingChamber;
import ru.lct.heat.model.ExistingPipe;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.out.NewPipe;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.model.ref.DiameterTable;
import ru.lct.heat.model.ref.RestrictionRules;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heat.support.TestGeoms.box;
import static ru.lct.heat.support.TestGeoms.c;
import static ru.lct.heat.support.TestGeoms.line;
import static ru.lct.heat.support.TestGeoms.restriction;

/**
 * Сжатие трассы в точках поворота: A* ставит вершины в узлах графа видимости (с запасом от зоны отступа),
 * полировка выбирает этот запас, не нарушая отступ (§3.1).
 */
class ShrinkVerticesTest {

    @Test
    void routeHugsTheClearanceZoneAroundBuildingCorners() {
        // здание между сетью и точкой: обход должен идти по зоне отступа (5 м + половина габарита),
        // а не по вершинам графа с запасом
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-300, 0, 300, 0), 400))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(-100, 0))))
                .restriction(restriction("b", RestrictionRules.OKS, box(-60, 60, 60, 140)))
                .connectionPoint(new ConnectionPoint("t", Crs.UTM.createPoint(c(0, 220)), 12.0))
                .build();
        List<Variant> variants = new HeatRoutingEngine(new RoutingParams()).solve(in, SolveOptions.defaults());
        Variant v = variants.get(0);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());

        double clearance = 5.0 + DiameterTable.byDiameter(v.getPipes().get(0).getDiameter()).orElseThrow().halfWidth();
        double nearest = Double.MAX_VALUE;
        for (NewPipe p : v.getPipes()) {
            nearest = Math.min(nearest, p.getGeometry().distance(in.getRestrictions().get(0).getGeometry()));
        }
        assertTrue(nearest >= clearance - 0.02, "отступ нарушен: " + nearest + " < " + clearance);
        // прижались к зоне: не дальше 1.5 м от её границы (иначе запас графа видимости остался невыбранным)
        assertTrue(nearest <= clearance + 1.5, "трасса не прижата к зоне отступа: " + nearest);
    }
}
