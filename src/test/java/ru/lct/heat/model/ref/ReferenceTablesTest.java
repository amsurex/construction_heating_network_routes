package ru.lct.heat.model.ref;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceTablesTest {

    @Test
    void specExampleCostAndScore() {
        // §7.3: 100 м ДУ100 → 8 974 800; + врезка 5 000 000 → 13 974 800; score 0.6913
        DiameterSpec d100 = DiameterTable.byDiameter(100).orElseThrow();
        double pipeCost = 100 * d100.getCostPerMeter();
        assertEquals(8_974_800, pipeCost, 1e-6);
        double total = pipeCost + ChamberCostTable.TIE_IN_COST;
        assertEquals(13_974_800, total, 1e-6);
        assertEquals(0.6913, CostConstants.score(total, 100.0), 1e-4);
    }

    @Test
    void minDiameterByFlowAndLength() {
        // 20 т/ч → ДУ100 по расходу (22.3), но при длине 500 м нужен ДУ125 (554 м)
        assertEquals(100, DiameterTable.minByFlow(20).orElseThrow().getDiameter());
        assertEquals(125, DiameterTable.minByFlowAndLength(20, 500).orElseThrow().getDiameter());
        assertEquals(50, DiameterTable.minByFlow(3.5).orElseThrow().getDiameter());
        assertTrue(DiameterTable.minByFlow(30_000).isEmpty());
    }

    @Test
    void oksOffsetDependsOnDiameter() {
        RestrictionRule oks = RestrictionRules.forType(RestrictionRules.OKS);
        assertEquals(5.0, oks.minDistance(400));
        assertEquals(7.0, oks.minDistance(500));
        assertEquals(7.0, oks.minDistance(800));
        assertEquals(9.0, oks.minDistance(900));
        assertTrue(RestrictionRules.forType("metro").isForbidden());
        assertEquals(1.60, RestrictionRules.forType(RestrictionRules.ROAD).getKSpecial());
    }

    @Test
    void chamberCostBands() {
        assertEquals(3_000_000, ChamberCostTable.newChamberCost(200));
        assertEquals(5_000_000, ChamberCostTable.newChamberCost(250));
        assertEquals(8_000_000, ChamberCostTable.newChamberCost(1000));
        assertEquals(12_000_000, ChamberCostTable.newChamberCost(1200));
    }

    @Test
    void penaltyAndDepthFactor() {
        assertEquals(112_000_000, CostConstants.unconnectedPenalty(24.0));
        assertEquals(1.0, CostConstants.depthFactor(3.0));
        assertEquals(1.2, CostConstants.depthFactor(5.0), 1e-9);
    }
}
