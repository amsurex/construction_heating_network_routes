package ru.lct.heat.routing;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.lct.heat.cost.NetworkCost;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.SegmentCheck;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ref.CostConstants;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;
import ru.lct.heat.sizing.FlowSizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Локальная оптимизация после жадного построения: каждую точку по очереди пробуем переподключить —
 * удаляем её ветку (вместе со ставшими лишними камерами), ищем присоединение заново к оставшемуся
 * дереву/сети, пересчитываем расходы и ДУ по всей сети и сравниваем итоговый показатель
 * (score = 0.7·C/25 млн + 0.3·L/100). Улучшение принимаем, иначе откатываем. Несколько проходов,
 * пока есть улучшения. Жадный порядок подключения перестаёт быть решающим.
 */
@Slf4j
public final class LocalSearch {

    /** Итераций Вейсфельда при поиске взвешенной точки Ферма. */
    private static final int FERMAT_ITERATIONS = 40;
    /** Минимальный выигрыш по стоимости, ради которого двигаем камеру, руб. */
    private static final double MOVE_MIN_GAIN = 1_000.0;
    /** Минимальная длина отрезка после сдвига камеры, м. */
    private static final double MIN_SEGMENT_M = 1.0;

    private final TreeBuilder builder;
    private final FlowSizer sizer;
    private final Map<Object, ConnectionPoint> pointsById;
    private final MapCache maps;
    private final RoutingParams params;
    private final ApproachPlanner approach;

    public LocalSearch(TreeBuilder builder, FlowSizer sizer, Map<Object, ConnectionPoint> pointsById) {
        this(builder, sizer, pointsById, null, null, null);
    }

    /** @param maps карты препятствий — включает ход «сдвиг камеры разветвления» (взвешенная точка Ферма) */
    public LocalSearch(TreeBuilder builder, FlowSizer sizer, Map<Object, ConnectionPoint> pointsById,
                       MapCache maps, RoutingParams params, ApproachPlanner approach) {
        this.builder = builder;
        this.sizer = sizer;
        this.pointsById = pointsById;
        this.maps = maps;
        this.params = params;
        this.approach = approach;
    }

    /** @return улучшенная сеть (та же ссылка, если улучшений нет) */
    public NewNetwork improve(NewNetwork net, int maxPasses) {
        sizer.size(net);
        double best = objective(net);
        double start = best;
        int accepted = 0;
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean improved = false;
            List<Object> leafIds = new ArrayList<>();
            for (NetNode leaf : net.leaves()) {
                leafIds.add(leaf.getId());
            }
            for (Object leafId : leafIds) {
                ConnectionPoint cp = pointsById.get(leafId);
                NetNode leaf = net.node(leafId);
                if (cp == null || leaf == null) {
                    continue;
                }
                NewNetwork snapshot = net.copy();
                detachLeaf(net, leaf);
                sizer.size(net);
                boolean ok = builder.connect(cp, net);
                if (ok) {
                    sizer.size(net);
                    double val = objective(net);
                    if (val < best - 1e-6) {
                        log.debug("Локальный поиск: точка {} переподключена, score {} → {}", leafId, best, val);
                        best = val;
                        improved = true;
                        accepted++;
                        continue;
                    }
                }
                net = snapshot;
            }
            // ход «другая врезка магистрали»: поддерево каждого корня переподключаем к сети заново
            List<Object> rootIds = new ArrayList<>();
            for (NetNode r : net.roots()) {
                rootIds.add(r.getId());
            }
            for (Object rootId : rootIds) {
                NetNode root = net.node(rootId);
                if (root == null) {
                    continue;
                }
                for (NetEdge e : new ArrayList<>(net.outgoing(root))) {
                    NewNetwork snapshot = net.copy();
                    NetNode top = e.getTo();
                    double flow = e.getFlowTph();
                    Object topId = top.getId();
                    net.removeEdge(e);
                    if (root.getEdges().isEmpty()) {
                        net.removeNode(root);
                    }
                    NetNode topNow = net.node(topId);
                    boolean ok = topNow != null && topNow.getKind() != NetNode.Kind.TECH_NODE
                            && builder.connectSubtree(topNow, flow, net);
                    if (ok) {
                        sizer.size(net);
                        double val = objective(net);
                        if (val < best - 1e-6) {
                            log.debug("Локальный поиск: магистраль от {} переподключена к сети, score {} → {}", topId, best, val);
                            best = val;
                            improved = true;
                            accepted++;
                            break; // корень изменился — остальные рёбра этого корня смотрим на следующем проходе
                        }
                    }
                    net = snapshot;
                }
            }
            // ход «перенос поддерева»: ветку с камерой-разветвлением целиком присоединяем в другое место
            List<Object> chamberIds = new ArrayList<>();
            for (NetNode n : net.nodeList()) {
                if (n.getKind() == NetNode.Kind.BRANCH_CHAMBER) {
                    chamberIds.add(n.getId());
                }
            }
            for (Object chId : chamberIds) {
                NetNode ch = net.node(chId);
                if (ch == null || net.incoming(ch) == null) {
                    continue;
                }
                NewNetwork snapshot = net.copy();
                NetEdge in = net.incoming(ch);
                double flow = in.getFlowTph();
                NetNode parent = in.getFrom();
                net.removeEdge(in);
                cleanupAfterDetach(net, parent);
                NetNode top = net.node(chId);
                java.util.Set<NetNode> subtree = new java.util.HashSet<>();
                if (top != null) {
                    collectSubtree(net, top, subtree);
                }
                boolean ok = top != null && builder.connectSubtree(top, flow, net, subtree);
                if (ok) {
                    sizer.size(net);
                    double val = objective(net);
                    if (val < best - 1e-6) {
                        log.debug("Локальный поиск: поддерево {} перенесено, score {} → {}", chId, best, val);
                        best = val;
                        improved = true;
                        accepted++;
                        continue;
                    }
                }
                net = snapshot;
            }
            if (moveChambers(net)) {
                sizer.size(net);
                double val = objective(net);
                if (val < best - 1e-6) {
                    log.debug("Локальный поиск: камеры разветвления сдвинуты, score {} -> {}", best, val);
                    best = val;
                    improved = true;
                    accepted++;
                }
            }
            if (!improved) {
                break;
            }
        }
        sizer.size(net);
        log.info("Локальный поиск: принято {} переподключений, score {} → {}", accepted,
                Math.round(start * 1000) / 1000.0, Math.round(best * 1000) / 1000.0);
        return net;
    }

    /**
     * Ход «сдвиг камеры разветвления»: камера переносится во взвешенную точку Ферма своих соседей
     * (веса — стоимость метра примыкающих участков), если все новые крайние отрезки допустимы. Сокращает
     * длину дерева там, где жадное построение поставило разветвление не в оптимальной точке.
     *
     * @return true, если хоть одна камера сдвинута
     */
    private boolean moveChambers(NewNetwork net) {
        if (maps == null) {
            return false;
        }
        boolean moved = false;
        for (NetNode n : new ArrayList<>(net.nodeList())) {
            if (n.getKind() != NetNode.Kind.BRANCH_CHAMBER || n.degree() < 3) {
                continue;
            }
            List<NetEdge> edges = new ArrayList<>(n.getEdges());
            // камера стоит ровно в точке входа в здание (§2.2): вставляем эту точку как вершину участка,
            // чтобы сдвиг камеры не менял финальный подход
            List<NetEdge> pinned = pinApproachEntries(n, edges);
            List<Coordinate> anchors = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            for (NetEdge e : edges) {
                List<Coordinate> cs = e.getCoords();
                anchors.add(e.getFrom() == n ? cs.get(1) : cs.get(cs.size() - 2));
                weights.add(e.getDiameter().getCostPerMeter());
            }
            Coordinate target = fermat(anchors, weights, n.getCoord());
            double gain = weighted(anchors, weights, n.getCoord()) - weighted(anchors, weights, target);
            double nearest = Double.MAX_VALUE;
            for (Coordinate a : anchors) {
                nearest = Math.min(nearest, a.distance(target));
            }
            if (gain < MOVE_MIN_GAIN || target.distance(n.getCoord()) < 0.05 || nearest < MIN_SEGMENT_M) {
                unpinApproachEntries(pinned);
                continue;
            }
            Coordinate old = n.getCoord();
            net.moveNode(n, target);
            boolean ok = true;
            for (NetEdge e : edges) {
                if (!recheckEnd(net, e, n)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                ok = anglesOk(net, n);
            }
            if (!ok) {
                net.moveNode(n, old);
                unpinApproachEntries(pinned);
                for (NetEdge e : edges) {
                    recheckEnd(net, e, n);
                }
                continue;
            }
            moved = true;
        }
        return moved;
    }

    /**
     * Рёбра, где камера сама является точкой входа в здание (§2.2): в них вставляется вершина в текущей
     * позиции камеры, поэтому финальный прямой участок E→точка сохраняется при сдвиге камеры.
     *
     * @return рёбра, в которые вершина была вставлена (для отката)
     */
    private List<NetEdge> pinApproachEntries(NetNode n, List<NetEdge> edges) {
        List<NetEdge> pinned = new ArrayList<>();
        for (NetEdge e : edges) {
            if (e.getTo().getKind() != NetNode.Kind.OKS_POINT || e.getCoords().size() != 2 || e.getFrom() != n
                    || approach == null || !approach.ownPolygonId(e.getTo().getId()).isPresent()) {
                continue;
            }
            e.getCoords().add(1, new Coordinate(n.getCoord()));
            e.getChecks().add(0, e.getChecks().get(0));
            pinned.add(e);
        }
        return pinned;
    }

    /** Откат {@link #pinApproachEntries}: убирает вставленную вершину. */
    private static void unpinApproachEntries(List<NetEdge> pinned) {
        for (NetEdge e : pinned) {
            if (e.getCoords().size() > 2 && e.getCoords().get(0).equals2D(e.getCoords().get(1))) {
                e.getCoords().remove(1);
                e.getChecks().remove(0);
            }
        }
    }

    /** Взвешенная точка Ферма (алгоритм Вейсфельда) для якорей с весами; start — начальное приближение. */
    static Coordinate fermat(List<Coordinate> anchors, List<Double> weights, Coordinate start) {
        double x = start.x;
        double y = start.y;
        for (int it = 0; it < FERMAT_ITERATIONS; it++) {
            double sx = 0;
            double sy = 0;
            double sw = 0;
            for (int i = 0; i < anchors.size(); i++) {
                Coordinate a = anchors.get(i);
                double d = Math.hypot(a.x - x, a.y - y);
                if (d < 1e-6) {
                    return new Coordinate(a.x, a.y);
                }
                double w = weights.get(i) / d;
                sx += a.x * w;
                sy += a.y * w;
                sw += w;
            }
            double nx = sx / sw;
            double ny = sy / sw;
            boolean done = Math.hypot(nx - x, ny - y) < 1e-4;
            x = nx;
            y = ny;
            if (done) {
                break;
            }
        }
        return new Coordinate(x, y);
    }

    private static double weighted(List<Coordinate> anchors, List<Double> weights, Coordinate p) {
        double sum = 0;
        for (int i = 0; i < anchors.size(); i++) {
            sum += weights.get(i) * anchors.get(i).distance(p);
        }
        return sum;
    }

    /** Пересчитывает проверку крайнего отрезка ребра у узла n; false — отрезок недопустим. */
    private boolean recheckEnd(NewNetwork net, NetEdge e, NetNode n) {
        List<Coordinate> cs = e.getCoords();
        int idx = e.getFrom() == n ? 0 : cs.size() - 2;
        Coordinate a = cs.get(idx);
        Coordinate b = cs.get(idx + 1);
        boolean toOks = e.getTo().getKind() == NetNode.Kind.OKS_POINT && idx == cs.size() - 2;
        java.util.Set<Object> exempt = toOks ? approach.exemptIds(e.getTo().getId()) : Collections.emptySet();
        SegmentCheck chk = maps.map(e.getDiameter()).check(a, b, exempt, false, toOks);
        e.getChecks().set(idx, chk);
        if (!chk.isFree()) {
            return false;
        }
        LineString seg = GeomUtil.segment(a, b);
        double clearance = params.getNewPipeClearanceM() + e.getDiameter().getWidthM();
        for (NetEdge o : net.getEdges()) {
            if (o == e) {
                continue;
            }
            LineString other = GeomUtil.line(o.getCoords());
            if (shares(o, e)) {
                // смежное ребро: касание допустимо только в общем узле (§2.1)
                org.locationtech.jts.geom.Geometry inter = other.intersection(seg);
                if (inter.getDimension() > 0 || inter.getNumGeometries() > 1) {
                    return false;
                }
                continue;
            }
            if (other.distance(seg) < clearance) {
                return false;
            }
        }
        return true;
    }

    private static boolean shares(NetEdge a, NetEdge b) {
        return a.getFrom() == b.getFrom() || a.getFrom() == b.getTo()
                || a.getTo() == b.getFrom() || a.getTo() == b.getTo();
    }

    /** Углы поворота в узле между входящим и каждым исходящим участком ≤ 90° (§2.1). */
    private static boolean anglesOk(NewNetwork net, NetNode n) {
        NetEdge in = net.incoming(n);
        if (in == null) {
            return true;
        }
        List<Coordinate> cs = in.getCoords();
        Coordinate before = cs.get(cs.size() - 2);
        for (NetEdge out : net.outgoing(n)) {
            Coordinate after = out.getCoords().get(1);
            if (GeomUtil.turnAngleDeg(before, n.getCoord(), after)
                    > CostConstants.MAX_TURN_ANGLE_DEG + PathFinder.ANGLE_TOL_DEG) {
                return false;
            }
        }
        return true;
    }

    private static double objective(NewNetwork net) {
        return CostConstants.score(NetworkCost.estimate(net), NetworkCost.length(net));
    }

    /**
     * Удаляет ветку листа вверх до первого узла, который остаётся нужным: корень с другими ветвями,
     * камера с другими ветвями. Камера, у которой остаётся одна ветвь, сливается с проходящим участком
     * (если угол в ней ≤ 90°), иначе остаётся камерой.
     */
    static void detachLeaf(NewNetwork net, NetNode leaf) {
        NetNode node = leaf;
        while (true) {
            NetEdge in = net.incoming(node);
            if (in == null) {
                net.removeNode(node);
                return;
            }
            NetNode parent = in.getFrom();
            net.removeNode(node);
            List<NetEdge> rest = net.outgoing(parent);
            if (parent.getKind() == NetNode.Kind.EXISTING_CHAMBER) {
                if (rest.isEmpty()) {
                    net.removeNode(parent);
                }
                return;
            }
            if (rest.isEmpty()) {
                // камера/техузел без ветвей — удаляем и идём выше
                node = parent;
                continue;
            }
            if (parent.getKind() == NetNode.Kind.BRANCH_CHAMBER && rest.size() == 1 && net.incoming(parent) != null) {
                mergeThrough(net, parent);
            }
            return;
        }
    }

    /** После снятия ветки с узла parent: пустая камера/техузел удаляется (и так вверх), камера с одной ветвью сливается. */
    private static void cleanupAfterDetach(NewNetwork net, NetNode parent) {
        NetNode node = parent;
        while (node != null) {
            List<NetEdge> rest = net.outgoing(node);
            if (node.getKind() == NetNode.Kind.EXISTING_CHAMBER) {
                if (rest.isEmpty()) {
                    net.removeNode(node);
                }
                return;
            }
            if (rest.isEmpty()) {
                NetEdge in = net.incoming(node);
                NetNode up = in == null ? null : in.getFrom();
                net.removeNode(node);
                node = up;
                continue;
            }
            if (node.getKind() == NetNode.Kind.BRANCH_CHAMBER && rest.size() == 1 && net.incoming(node) != null) {
                mergeThrough(net, node);
            }
            return;
        }
    }

    private static void collectSubtree(NewNetwork net, NetNode n, java.util.Set<NetNode> out) {
        out.add(n);
        for (NetEdge e : net.outgoing(n)) {
            collectSubtree(net, e.getTo(), out);
        }
    }

    /** Сливает входящее и единственное исходящее ребро узла в одно, если поворот в узле допустим. */
    private static void mergeThrough(NewNetwork net, NetNode n) {
        NetEdge in = net.incoming(n);
        NetEdge out = net.outgoing(n).get(0);
        List<Coordinate> ci = in.getCoords();
        List<Coordinate> co = out.getCoords();
        if (ci.size() >= 2 && co.size() >= 2) {
            double turn = GeomUtil.turnAngleDeg(ci.get(ci.size() - 2), n.getCoord(), co.get(1));
            if (turn > CostConstants.MAX_TURN_ANGLE_DEG + PathFinder.ANGLE_TOL_DEG) {
                return; // оставляем камеру
            }
        }
        List<Coordinate> coords = new ArrayList<>(ci);
        coords.addAll(co.subList(1, co.size()));
        List<SegmentCheck> checks = new ArrayList<>(in.getChecks());
        checks.addAll(out.getChecks());
        NetNode from = in.getFrom();
        NetNode to = out.getTo();
        Object creator = out.getCreatorPointId();
        net.removeNode(n);
        NetEdge merged = net.addEdge(from, to, coords, checks);
        merged.setCreatorPointId(creator);
        merged.setFlowTph(out.getFlowTph());
        merged.setDiameter(out.getDiameter());
    }
}
