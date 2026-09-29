package ru.lct.heat.cost;

import lombok.extern.slf4j.Slf4j;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.out.NewChamber;
import ru.lct.heat.model.out.NewPipe;
import ru.lct.heat.model.out.TechnicalNode;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.model.out.VariantSummary;
import ru.lct.heat.model.ref.ChamberCostTable;
import ru.lct.heat.model.ref.CostConstants;
import ru.lct.heat.model.ref.DiameterSpec;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сборка выходного варианта из построенной сети: участки, камеры, техузлы, сводка и score
 * (Техприложение §3.2, §6, §7). Ребро сети → один heat_network, узлы → камеры/техузлы.
 */
@Slf4j
public final class VariantAssembler {

    private final Object variantId;
    private final String description;
    private final Map<Object, Object> nodeOutId = new HashMap<>();
    private NewNetwork net;
    private int netSeq = 0;
    private int chamberSeq = 0;
    private int nodeSeq = 0;

    public VariantAssembler(Object variantId, String description) {
        this.variantId = variantId;
        this.description = description;
    }

    public Variant assemble(NewNetwork net, List<ConnectionPoint> unconnected) {
        return assemble(net, unconnected, java.util.Collections.emptyMap());
    }

    /** @param unconnectedReasons причина неподключения по id точки (для объяснения) */
    public Variant assemble(NewNetwork net, List<ConnectionPoint> unconnected, Map<Object, String> unconnectedReasons) {
        Variant.VariantBuilder vb = Variant.builder().variantId(variantId).description(description);
        this.net = net;

        // id узлов: точки ОКС и существующие камеры — входные id; новые — генерируем
        for (NetNode n : net.nodeList()) {
            switch (n.getKind()) {
                case OKS_POINT:
                case EXISTING_CHAMBER:
                    nodeOutId.put(n.getId(), n.getId());
                    break;
                case TIE_IN_CHAMBER:
                case BRANCH_CHAMBER:
                    nodeOutId.put(n.getId(), variantId + "_chamber_" + (++chamberSeq));
                    break;
                case TECH_NODE:
                    nodeOutId.put(n.getId(), variantId + "_node_" + (++nodeSeq));
                    break;
                default:
                    throw new IllegalStateException("unknown kind " + n.getKind());
            }
        }

        double pipesCost = 0;
        double length = 0;
        for (NetEdge e : net.getEdges()) {
            NewPipe p = toPipe(e);
            pipesCost += p.getCost();
            length += p.getLength();
            vb.pipe(p);
        }

        double chambersCost = 0;
        int tieIns = 0;
        for (NetNode n : net.nodeList()) {
            if (n.getKind() == NetNode.Kind.EXISTING_CHAMBER) {
                tieIns += net.outgoing(n).size(); // каждый новый участок в существующей камере — врезка
            } else if (n.getKind() == NetNode.Kind.TIE_IN_CHAMBER || n.getKind() == NetNode.Kind.BRANCH_CHAMBER) {
                int maxDn = n.getExistingMaxDiameter();
                for (NetEdge e : n.getEdges()) {
                    maxDn = Math.max(maxDn, e.getDiameter().getDiameter());
                }
                double cost = ChamberCostTable.newChamberCost(maxDn);
                chambersCost += cost;
                Map<String, Object> extra = new java.util.LinkedHashMap<>();
                extra.put("reason", Explanations.chamberReason(net, n));
                extra.put("connections", n.degree() + (n.getKind() == NetNode.Kind.TIE_IN_CHAMBER ? 2 : 0));
                vb.chamber(NewChamber.builder()
                        .id(nodeOutId.get(n.getId()))
                        .geometry(GeomUtil.point(n.getCoord()))
                        .diameter(maxDn)
                        .cost(cost)
                        .tieInPipeId(n.getTieInPipeId())
                        .extra(extra)
                        .build());
            } else if (n.getKind() == NetNode.Kind.TECH_NODE) {
                vb.technicalNode(new TechnicalNode(nodeOutId.get(n.getId()), GeomUtil.point(n.getCoord()), null));
            }
        }
        double tieInCost = tieIns * ChamberCostTable.TIE_IN_COST;

        double penalty = 0;
        List<Object> unconnectedIds = new ArrayList<>();
        for (ConnectionPoint cp : unconnected) {
            penalty += CostConstants.unconnectedPenalty(cp.getFlowTph());
            unconnectedIds.add(cp.getId());
        }

        double construction = pipesCost + chambersCost + tieInCost;
        double calculated = construction + penalty;
        Map<String, Object> metrics = Explanations.routeMetrics(net);
        List<Explanations.ConnectionPointRef> unRefs = new ArrayList<>();
        for (ConnectionPoint cp : unconnected) {
            unRefs.add(new Explanations.ConnectionPointRef(cp.getId(),
                    unconnectedReasons.getOrDefault(cp.getId(), "маршрут не найден")));
        }
        Map<String, Object> summaryExtra = new java.util.LinkedHashMap<>();
        summaryExtra.put("engine_version", ru.lct.heat.routing.HeatRoutingEngine.VERSION);
        summaryExtra.put("description", description);
        summaryExtra.put("explanation", Explanations.narrative(net, metrics, tieIns, chambersCost, unRefs));
        summaryExtra.put("cost_breakdown", Explanations.costBreakdown(net, chambersCost, tieInCost, tieIns, penalty));
        summaryExtra.put("route_metrics", metrics);
        VariantSummary summary = VariantSummary.builder()
                .id(variantId + "_summary")
                .rank(0)
                .constructionCost(round2(construction))
                .chamberConstructionCost(round2(chambersCost))
                .existingChamberTieInCount(tieIns)
                .existingChamberTieInCost(round2(tieInCost))
                .unconnectedPenalty(round2(penalty))
                .calculatedCost(round2(calculated))
                .newNetworkLength(round2(length))
                .score(round4(CostConstants.score(calculated, length)))
                .unconnectedOksIds(unconnectedIds)
                .extra(summaryExtra)
                .build();
        vb.summary(summary);
        Variant v = vb.build();
        log.info("Вариант {}: {} участков, {} м, стоимость {} млн, штраф {} млн, score {}", variantId,
                v.getPipes().size(), Math.round(length), Math.round(construction / 1e6),
                Math.round(penalty / 1e6), summary.getScore());
        return v;
    }

    /** Ребро → участок. Стоимость: L·c(ДУ)·Kгл·Kспец (Техприложение §6); Kгл = 1 в 2D-режиме. */
    private NewPipe toPipe(NetEdge e) {
        dropDegenerateVertices(e);
        DiameterSpec dn = e.getDiameter();
        double len = e.length();
        // Kгл считаем по тем глубинам, которые уйдут в выдачу (округление до миллиметра): кто пересчитает
        // стоимость из опубликованных depth_start/depth_end, получит ровно наш k_depth и cost
        Double depthStart = e.getDepthStart() == null ? null : round3(e.getDepthStart());
        Double depthEnd = e.getDepthEnd() == null ? null : round3(e.getDepthEnd());
        double kDepth = depthStart == null || depthEnd == null ? e.getKDepth()
                : (CostConstants.depthFactor(depthStart) + CostConstants.depthFactor(depthEnd)) / 2;
        double cost = len * dn.getCostPerMeter() * e.getKSpecial() * kDepth;
        Map<String, Object> extra = new java.util.LinkedHashMap<>();
        extra.put("serves_oks", Explanations.downstreamPoints(net, e));
        extra.put("turns", Explanations.turns(e));
        if (e.getTo().getKind() == NetNode.Kind.OKS_POINT && e.getTo().getApproachNote() != null) {
            extra.put("approach", e.getTo().getApproachNote());
        }
        return NewPipe.builder()
                .id(variantId + "_net_" + (++netSeq))
                .startNodeId(nodeOutId.get(e.getFrom().getId()))
                .endNodeId(nodeOutId.get(e.getTo().getId()))
                .geometry(GeomUtil.line(e.getCoords()))
                .flowTph(round2(e.getFlowTph()))
                .diameter(dn.getDiameter())
                .length(round2(len))
                .layingMethod(e.getLayingMethod())
                .depthStart(depthStart)
                .depthEnd(depthEnd)
                .cost(round2(cost))
                .kSpecial(e.getKSpecial())
                .kDepth(kDepth)
                .crossedRestrictionTypes(e.getCrossedTypes())
                .extra(extra)
                .build();
    }

    /** Убирает совпадающие соседние вершины (страховка: нулевой отрезок ломает угол поворота в §2.1). */
    private static void dropDegenerateVertices(NetEdge e) {
        java.util.List<org.locationtech.jts.geom.Coordinate> cs = e.getCoords();
        for (int i = cs.size() - 1; i > 0 && cs.size() > 2; i--) {
            if (cs.get(i).distance(cs.get(i - 1)) < 1e-6) {
                cs.remove(i);
                if (i - 1 < e.getChecks().size()) {
                    e.getChecks().remove(i - 1);
                }
            }
        }
    }

    static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

}
