package ru.lct.heat.cost;

import ru.lct.heat.geometry.SegmentCheck;
import ru.lct.heat.model.ref.ChamberCostTable;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;

import java.util.List;

/**
 * Быстрая оценка стоимости строительства сети (без деления на спецучастки): участки по отрезкам
 * с учётом Kспец на длине внутри спецзон, новые камеры по наибольшему ДУ, врезки в существующие камеры.
 * Совпадает с {@link VariantAssembler} с точностью до способа учёта спецзон; используется для сравнения
 * альтернатив в локальной оптимизации.
 */
public final class NetworkCost {

    private NetworkCost() {
    }

    public static double estimate(NewNetwork net) {
        double total = 0;
        for (NetEdge e : net.getEdges()) {
            double c = e.getDiameter().getCostPerMeter();
            List<SegmentCheck> checks = e.getChecks();
            for (int i = 0; i + 1 < e.getCoords().size(); i++) {
                double len = e.getCoords().get(i).distance(e.getCoords().get(i + 1));
                double extra = i < checks.size() ? checks.get(i).extraLengthEquivalent() : 0;
                total += c * (len + extra) * e.getKDepth();
            }
        }
        for (NetNode n : net.nodeList()) {
            if (n.getKind() == NetNode.Kind.EXISTING_CHAMBER) {
                total += net.outgoing(n).size() * ChamberCostTable.TIE_IN_COST;
            } else if (n.getKind() == NetNode.Kind.TIE_IN_CHAMBER || n.getKind() == NetNode.Kind.BRANCH_CHAMBER) {
                int maxDn = n.getExistingMaxDiameter();
                for (NetEdge e : n.getEdges()) {
                    maxDn = Math.max(maxDn, e.getDiameter().getDiameter());
                }
                total += ChamberCostTable.newChamberCost(maxDn);
            }
        }
        return total;
    }

    /** Суммарная длина новых участков, м. */
    public static double length(NewNetwork net) {
        return net.totalLength();
    }
}
