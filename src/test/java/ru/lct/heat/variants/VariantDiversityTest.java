package ru.lct.heat.variants;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heat.support.TestGeoms.box;
import static ru.lct.heat.support.TestGeoms.c;
import static ru.lct.heat.support.TestGeoms.line;
import static ru.lct.heat.support.TestGeoms.restriction;

/** §6: варианты в выдаче должны различаться трассой, а не быть сдвигом одной и той же. */
class VariantDiversityTest {

    private static Geometry shape(Variant v) {
        List<Geometry> lines = new ArrayList<>();
        for (NewPipe p : v.getPipes()) {
            lines.add(p.getGeometry());
        }
        return Crs.UTM.buildGeometry(lines).union();
    }

    private static double overlap(Variant a, Variant b) {
        Geometry ga = shape(a);
        Geometry gb = shape(b);
        return Math.max(gb.intersection(ga.buffer(3)).getLength() / gb.getLength(),
                ga.intersection(gb.buffer(3)).getLength() / ga.getLength());
    }

    @Test
    void variantsDifferByRouteNotByShift() {
        // сеть снизу, четыре точки за рядом зданий: слева и справа есть равноценные коридоры,
        // поэтому содержательно разные варианты существуют — движок обязан их выдать
        InputModel.InputModelBuilder in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-400, 0, 400, 0), 500))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(-200, 0))))
                .chamber(new ExistingChamber("ch2", Crs.UTM.createPoint(c(200, 0))));
        for (int i = 0; i < 3; i++) {
            in.restriction(restriction("b" + i, RestrictionRules.OKS, box(-140 + i * 100, 60, -60 + i * 100, 140)));
        }
        for (int i = 0; i < 4; i++) {
            in.connectionPoint(new ConnectionPoint("t" + i, Crs.UTM.createPoint(c(-150 + i * 100, 200)), 12.0));
        }
        RoutingParams params = new RoutingParams();
        List<Variant> variants = new HeatRoutingEngine(params).solve(in.build(), SolveOptions.defaults());
        assertTrue(variants.size() >= 2, "ожидается несколько вариантов: " + variants.size());
        for (int i = 0; i < variants.size(); i++) {
            for (int j = i + 1; j < variants.size(); j++) {
                double o = overlap(variants.get(i), variants.get(j));
                assertTrue(o <= params.getVariantMaxOverlap() + 1e-9,
                        "варианты " + variants.get(i).getVariantId() + " и " + variants.get(j).getVariantId()
                                + " совпадают на " + Math.round(o * 100) + "%");
            }
        }
        for (Variant v : variants) {
            assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        }
    }
}
