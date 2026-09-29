package ru.lct.heat.model.ref;

import org.junit.jupiter.api.Test;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ExistingChamber;
import ru.lct.heat.model.ExistingPipe;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.out.NewPipe;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.routing.HeatRoutingEngine;
import ru.lct.heat.routing.RoutingParams;
import ru.lct.heat.routing.SolveOptions;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heat.support.TestGeoms.box;
import static ru.lct.heat.support.TestGeoms.c;
import static ru.lct.heat.support.TestGeoms.line;
import static ru.lct.heat.support.TestGeoms.restriction;

class RuleProfilesTest {

    @Test
    void heatYamlMatchesTechnicalAppendixTable() {
        RuleProfile yaml = RuleProfiles.load("heat");
        for (Map.Entry<String, RestrictionRule> en : RestrictionRules.all().entrySet()) {
            RestrictionRule expected = en.getValue();
            RestrictionRule actual = yaml.forType(en.getKey());
            assertTrue(yaml.isKnown(en.getKey()), en.getKey());
            assertEquals(expected.getKind(), actual.getKind(), en.getKey());
            assertEquals(expected.getMinDistanceM(), actual.getMinDistanceM(), 1e-9, en.getKey());
            assertEquals(expected.getMinCrossingAngleDeg(), actual.getMinCrossingAngleDeg(), 1e-9, en.getKey());
            assertEquals(expected.getSpecialMarginM(), actual.getSpecialMarginM(), 1e-9, en.getKey());
            assertEquals(expected.getKSpecial(), actual.getKSpecial(), 1e-9, en.getKey());
            assertEquals(expected.getOwnDepthM(), actual.getOwnDepthM(), 1e-9, en.getKey());
            assertEquals(expected.getVerticalClearanceM(), actual.getVerticalClearanceM(), 1e-9, en.getKey());
            assertEquals(expected.isCrossUnderOnly(), actual.isCrossUnderOnly(), en.getKey());
        }
    }

    @Test
    void aliasesExtensionsAndUnknownPolicy() {
        RuleProfile heat = RuleProfiles.load("heat");
        assertEquals("social_area", heat.canonical("School"));
        assertTrue(heat.isKnown("kindergarten"));
        assertEquals(1.0, heat.forType("school").getMinDistanceM());
        assertTrue(heat.isKnown("metro"));
        assertTrue(heat.forType("metro").isForbidden());
        assertTrue(!heat.isKnown("spaceport"));
        assertTrue(heat.forType("spaceport").isForbidden());
        assertEquals(1.0, heat.forType("spaceport").getMinDistanceM());
        assertThrows(IllegalArgumentException.class, () -> RuleProfiles.load("plasma"));
    }

    @Test
    void waterProfileChangesRoadClearance() {
        RuleProfile water = RuleProfiles.load("water");
        assertEquals(2.0, water.forType("road").getMinDistanceM());
        assertEquals(1.50, water.forType("road").getKSpecial());
        // тот же вход, другой профиль → другой Kспец у спецпрохода
        InputModel in = InputModel.builder()
                .pipe(new ExistingPipe("p1", line(-100, 0, 100, 0), 300))
                .chamber(new ExistingChamber("ch1", Crs.UTM.createPoint(c(0, 0))))
                .restriction(restriction("road", "road", box(-500, 40, 500, 60)))
                .connectionPoint(new ConnectionPoint("oks1", Crs.UTM.createPoint(c(0, 100)), 20.0))
                .build();
        Variant heatV = new HeatRoutingEngine(new RoutingParams()).solve(in, SolveOptions.defaults()).get(0);
        Variant waterV = new HeatRoutingEngine(new RoutingParams())
                .solve(in, SolveOptions.builder().resource("water").build()).get(0);
        double kHeat = heatV.getPipes().stream().mapToDouble(NewPipe::getKSpecial).max().orElseThrow();
        double kWater = waterV.getPipes().stream().mapToDouble(NewPipe::getKSpecial).max().orElseThrow();
        assertEquals(1.60, kHeat, 1e-9);
        assertEquals(1.50, kWater, 1e-9);
    }
}
