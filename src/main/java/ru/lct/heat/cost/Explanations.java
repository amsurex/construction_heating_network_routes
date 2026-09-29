package ru.lct.heat.cost;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.model.LayingMethod;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Объяснения и метрики для выходных объектов (доп. properties, Техприложение §7: допускаются и
 * игнорируются при проверке обязательной части). Нужны экспертам: почему камера здесь, что обслуживает
 * участок, из чего сложилась стоимость, насколько «чистая» трасса.
 */
final class Explanations {

    private Explanations() {
    }

    /** Id точек ОКС ниже по дереву от ребра (что обслуживает участок). */
    static List<Object> downstreamPoints(NewNetwork net, NetEdge e) {
        List<Object> out = new ArrayList<>();
        collect(net, e.getTo(), out);
        return out;
    }

    private static void collect(NewNetwork net, NetNode n, List<Object> out) {
        if (n.getKind() == NetNode.Kind.OKS_POINT) {
            out.add(n.getId());
        }
        for (NetEdge c : net.outgoing(n)) {
            collect(net, c.getTo(), out);
        }
    }

    /** Число поворотов ломаной ребра (внутренние вершины с углом > 0.5°). */
    static int turns(NetEdge e) {
        List<Coordinate> cs = e.getCoords();
        int n = 0;
        for (int i = 1; i + 1 < cs.size(); i++) {
            if (GeomUtil.turnAngleDeg(cs.get(i - 1), cs.get(i), cs.get(i + 1)) > 0.5) {
                n++;
            }
        }
        return n;
    }

    /** Почему здесь камера. */
    static String chamberReason(NewNetwork net, NetNode n) {
        List<NetEdge> out = net.outgoing(n);
        if (n.getKind() == NetNode.Kind.TIE_IN_CHAMBER) {
            return "врезка в существующий участок " + n.getTieInPipeId()
                    + ": существующей камеры с запасом примыканий ближе 10 м нет (§2.4); ветвей: " + out.size();
        }
        List<String> branches = new ArrayList<>();
        for (NetEdge e : out) {
            branches.add("ДУ" + e.getDiameter().getDiameter() + " → точки " + downstreamPoints(net, e));
        }
        return "разветвление (§2.1: только в тепловой камере): " + String.join("; ", branches);
    }

    /** Разбор стоимости варианта по ДУ, спецпроходам, камерам и врезкам. */
    static Map<String, Object> costBreakdown(NewNetwork net, double chambersCost, double tieInCost, int tieIns,
                                             double penalty) {
        Map<Integer, double[]> byDn = new TreeMap<>();
        Map<String, double[]> bySpecial = new TreeMap<>();
        double specialExtra = 0;
        for (NetEdge e : net.getEdges()) {
            double len = e.length();
            double cost = len * e.getDiameter().getCostPerMeter() * e.getKSpecial() * e.getKDepth();
            byDn.computeIfAbsent(e.getDiameter().getDiameter(), k -> new double[2]);
            byDn.get(e.getDiameter().getDiameter())[0] += len;
            byDn.get(e.getDiameter().getDiameter())[1] += cost;
            if (e.getLayingMethod() == LayingMethod.SPECIAL && e.getCrossedTypes() != null) {
                double[] acc = bySpecial.computeIfAbsent(e.getCrossedTypes(), k -> new double[2]);
                acc[0] += len;
                acc[1] += cost;
                specialExtra += cost - len * e.getDiameter().getCostPerMeter() * e.getKDepth();
            }
        }
        Map<String, Object> pipes = new LinkedHashMap<>();
        for (Map.Entry<Integer, double[]> en : byDn.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("length_m", VariantAssembler.round2(en.getValue()[0]));
            row.put("cost", VariantAssembler.round2(en.getValue()[1]));
            pipes.put("DN" + en.getKey(), row);
        }
        Map<String, Object> special = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> en : bySpecial.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("length_m", VariantAssembler.round2(en.getValue()[0]));
            row.put("cost", VariantAssembler.round2(en.getValue()[1]));
            special.put(en.getKey(), row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pipes_by_diameter", pipes);
        out.put("special_sections", special);
        out.put("special_extra_cost", VariantAssembler.round2(specialExtra));
        out.put("new_chambers_cost", VariantAssembler.round2(chambersCost));
        out.put("existing_chamber_tie_ins", tieIns);
        out.put("tie_in_cost", VariantAssembler.round2(tieInCost));
        out.put("unconnected_penalty", VariantAssembler.round2(penalty));
        return out;
    }

    /** Метрики качества трассы: повороты, углы, длины прямых участков, спецпроходы, пути. */
    static Map<String, Object> routeMetrics(NewNetwork net) {
        int turns = 0;
        int standard = 0;
        int segments = 0;
        double straightTotal = 0;
        double minSegment = Double.MAX_VALUE;
        int specials = 0;
        for (NetEdge e : net.getEdges()) {
            List<Coordinate> cs = e.getCoords();
            for (int i = 0; i + 1 < cs.size(); i++) {
                double len = cs.get(i).distance(cs.get(i + 1));
                segments++;
                straightTotal += len;
                minSegment = Math.min(minSegment, len);
            }
            for (int i = 1; i + 1 < cs.size(); i++) {
                double a = GeomUtil.turnAngleDeg(cs.get(i - 1), cs.get(i), cs.get(i + 1));
                if (a > 0.5) {
                    turns++;
                    if (Math.abs(a - 45) < 0.5 || Math.abs(a - 90) < 0.5) {
                        standard++;
                    }
                }
            }
            if (e.getLayingMethod() == LayingMethod.SPECIAL) {
                specials++;
            }
        }
        double longest = 0;
        Object longestLeaf = null;
        for (NetNode leaf : net.leaves()) {
            double d = 0;
            NetNode n = leaf;
            NetEdge in;
            while ((in = net.incoming(n)) != null) {
                d += in.length();
                n = in.getFrom();
            }
            if (d > longest) {
                longest = d;
                longestLeaf = leaf.getId();
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turns", turns);
        m.put("turns_45_or_90", standard);
        m.put("straight_segments", segments);
        m.put("avg_segment_m", segments == 0 ? 0 : VariantAssembler.round2(straightTotal / segments));
        m.put("min_segment_m", segments == 0 ? 0 : VariantAssembler.round2(minSegment));
        m.put("special_sections", specials);
        m.put("tie_in_points", net.roots().size());
        m.put("branch_chambers", (int) net.nodeList().stream()
                .filter(n -> n.getKind() == NetNode.Kind.BRANCH_CHAMBER).count());
        m.put("longest_path_m", VariantAssembler.round2(longest));
        m.put("longest_path_to", longestLeaf);
        return m;
    }

    /** Короткая инженерная записка по варианту. */
    static String narrative(NewNetwork net, Map<String, Object> metrics, int tieIns, double chambersCost,
                            List<ConnectionPointRef> unconnected) {
        int maxDn = 0;
        for (NetEdge e : net.getEdges()) {
            maxDn = Math.max(maxDn, e.getDiameter().getDiameter());
        }
        List<String> roots = new ArrayList<>();
        for (NetNode r : net.roots()) {
            double flow = 0;
            for (NetEdge e : net.outgoing(r)) {
                flow += e.getFlowTph();
            }
            roots.add((r.getKind() == NetNode.Kind.EXISTING_CHAMBER ? "камера " + r.getId()
                    : "новая камера на участке " + r.getTieInPipeId()) + " (" + Math.round(flow) + " т/ч)");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Магистраль до ДУ").append(maxDn).append("; присоединений к существующей сети: ")
                .append(net.roots().size()).append(" — ").append(String.join(", ", roots))
                .append("; врезок в существующие камеры: ").append(tieIns)
                .append("; камер-разветвлений: ").append(metrics.get("branch_chambers"))
                .append("; спецпроходов: ").append(metrics.get("special_sections"))
                .append("; поворотов: ").append(metrics.get("turns"))
                .append("; самая длинная трасса ").append(metrics.get("longest_path_m")).append(" м до точки ")
                .append(metrics.get("longest_path_to")).append(".");
        if (!unconnected.isEmpty()) {
            sb.append(" Не подключены (маршрут по правилам не найден): ");
            List<String> ids = new ArrayList<>();
            for (ConnectionPointRef u : unconnected) {
                ids.add(String.valueOf(u.id) + " (" + u.reason + ")");
            }
            sb.append(String.join(", ", ids)).append('.');
        }
        return sb.toString();
    }

    /** Неподключённая точка с причиной. */
    static final class ConnectionPointRef {
        final Object id;
        final String reason;

        ConnectionPointRef(Object id, String reason) {
            this.id = id;
            this.reason = reason;
        }
    }
}
