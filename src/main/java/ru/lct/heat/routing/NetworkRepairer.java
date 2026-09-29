package ru.lct.heat.routing;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.ObstacleMap;
import ru.lct.heat.geometry.SegmentCheck;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ref.DiameterSpec;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Перепрокладка рёбер, нарушающих ограничения после финального подбора ДУ (габарит и отступ от ОКС
 * растут с ДУ, а маршрут искался при меньшем). Ребро ищется заново между его узлами по карте
 * итогового ДУ, остальные рёбра — динамические препятствия. Несколько итераций, пока есть что чинить.
 */
@Slf4j
public final class NetworkRepairer {

    private final MapCache maps;
    private final ApproachPlanner approach;
    private final NetworkChecker checker;
    private final RoutingParams params;
    private final Map<Object, ConnectionPoint> pointsById;

    public NetworkRepairer(MapCache maps, ApproachPlanner approach, RoutingParams params,
                           Map<Object, ConnectionPoint> pointsById) {
        this.maps = maps;
        this.approach = approach;
        this.checker = new NetworkChecker(maps, approach);
        this.params = params;
        this.pointsById = pointsById;
    }

    /** @return число оставшихся нарушений */
    public int repair(NewNetwork net, int maxIterations) {
        List<NetworkChecker.Violation> violations = checker.check(net);
        for (int iter = 0; iter < maxIterations && !violations.isEmpty(); iter++) {
            Set<NetEdge> bad = new LinkedHashSet<>();
            for (NetworkChecker.Violation v : violations) {
                if (v.getEdge() != null) {
                    bad.add(v.getEdge());
                }
            }
            if (bad.isEmpty()) {
                break;
            }
            int fixed = 0;
            for (NetEdge e : bad) {
                if (reroute(net, e)) {
                    fixed++;
                }
            }
            log.info("Ремонт, итерация {}: перепроложено {} из {} рёбер", iter + 1, fixed, bad.size());
            if (fixed == 0) {
                break;
            }
            violations = checker.check(net);
        }
        return violations.size();
    }

    /**
     * Последняя линия обороны после ремонта: ветку, которая всё ещё нарушает правила, снимаем с сети.
     * Её точки уходят в неподключённые со штрафом §2.5 — именно этот случай правило и описывает
     * («только если допустимый маршрут не найден»). Честный штраф лучше трассы с нарушением: выдача
     * обязана быть корректной по правилам, а не выглядеть подключённой.
     *
     * @return узлы точек ОКС, снятые с сети (их стоит попробовать подключить заново другим маршрутом)
     */
    public List<NetNode> dropUnfixable(NewNetwork net) {
        List<NetNode> dropped = new ArrayList<>();
        List<NetworkChecker.Violation> violations = checker.check(net);
        for (int guard = 0; guard < 64 && !violations.isEmpty(); guard++) {
            NetEdge bad = null;
            for (NetworkChecker.Violation v : violations) {
                if (v.getEdge() != null) {
                    bad = v.getEdge();
                    break;
                }
            }
            if (bad == null) {
                break;
            }
            List<NetNode> leaves = new ArrayList<>();
            collectOksLeaves(net, bad.getTo(), leaves);
            if (leaves.isEmpty()) {
                break; // нарушение не на ветке к точке — снимать нечего
            }
            log.warn("Снимаем ветку с нарушением ({}): точек {}", violations.get(0).getMessage(), leaves.size());
            for (NetNode leaf : leaves) {
                dropped.add(leaf);
                LocalSearch.detachLeaf(net, leaf);
            }
            violations = checker.check(net);
        }
        return dropped;
    }

    private static void collectOksLeaves(NewNetwork net, NetNode n, List<NetNode> out) {
        if (n.getKind() == NetNode.Kind.OKS_POINT) {
            out.add(n);
        }
        for (NetEdge e : net.outgoing(n)) {
            collectOksLeaves(net, e.getTo(), out);
        }
    }

    private boolean reroute(NewNetwork net, NetEdge e) {
        if (reroute(net, e, maps.routingMap(e.getDiameter()), maps.finder(e.getDiameter()))) {
            return true;
        }
        // групповая карта берёт габарит наибольшего ДУ группы: в узком коридоре (между зданиями,
        // вдоль дороги) маршрут по ней не находится, хотя по точному ДУ он допустим — пробуем ещё раз
        PathFinder exactFinder = maps.exactFinder(e.getDiameter());
        return exactFinder != null && reroute(net, e, maps.map(e.getDiameter()), exactFinder);
    }

    private boolean reroute(NewNetwork net, NetEdge e, ObstacleMap map, PathFinder finder) {
        DiameterSpec dn = e.getDiameter();
        // узел в зоне отступа запретного препятствия перепрокладкой не вылечить — не тратим время
        ObstacleMap exact = maps.map(dn);
        if (!exact.isPointFreeOfForbidden(e.getFrom().getCoord())
                || (e.getTo().getKind() != NetNode.Kind.OKS_POINT && !exact.isPointFreeOfForbidden(e.getTo().getCoord()))) {
            log.warn("Ремонт {}→{}: узел внутри зоны отступа для ДУ{}, перепрокладка невозможна",
                    e.getFrom(), e.getTo(), dn.getDiameter());
            return false;
        }

        List<PathFinder.Start> starts = new ArrayList<>();
        ApproachPlanner.Approach chosenApproach = null;
        List<ApproachPlanner.Approach> approaches = Collections.emptyList();
        NetNode leaf = e.getTo();
        if (leaf.getKind() == NetNode.Kind.OKS_POINT) {
            ConnectionPoint cp = pointsById.get(leaf.getId());
            approaches = approach.planAll(cp, map, params.getVertexClearanceEpsM(), params.getMaxApproaches());
            for (ApproachPlanner.Approach a : approaches) {
                starts.add(new PathFinder.Start(a.getEntry(),
                        dn.getCostPerMeter() * a.getEntry().distance(a.getTarget()),
                        a.getEntry().equals2D(a.getTarget()) ? null : a.getTarget()));
            }
        } else {
            starts.add(new PathFinder.Start(leaf.getCoord(), 0));
        }
        if (starts.isEmpty()) {
            log.warn("Ремонт {}→{}: нет подхода к точке", e.getFrom(), e.getTo());
            return false;
        }
        List<Goal> goals = Collections.singletonList(new Goal(e.getFrom().getCoord(), 0, e.getFrom()));

        List<LineString> others = new ArrayList<>();
        for (NetEdge o : net.getEdges()) {
            if (o != e) {
                others.add(GeomUtil.line(o.getCoords()));
            }
        }
        finder.setDynamicObstacles(others, params.getNewPipeClearanceM() + dn.getWidthM());
        finder.setExpansionLimit(params.getRepairMaxExpansions());
        Optional<Path> path;
        try {
            path = params.isNearestEntryOnly() ? finder.findPreferFirst(starts, goals, params.getNearestEntryMaxDetourFactor(), params.getNearestEntryMinDetourM()) : finder.find(starts, goals);
        } finally {
            finder.setDynamicObstacles(Collections.emptyList(), 0);
            finder.setExpansionLimit(0);
        }
        if (path.isEmpty()) {
            log.warn("Ремонт {}→{}: маршрут ДУ{} не найден", e.getFrom(), e.getTo(), dn.getDiameter());
            return false;
        }
        Path p = path.get();
        List<Coordinate> coords = new ArrayList<>(p.getCoords());
        Collections.reverse(coords);
        List<SegmentCheck> checks = new ArrayList<>(p.getChecks());
        Collections.reverse(checks);
        if (leaf.getKind() == NetNode.Kind.OKS_POINT) {
            for (ApproachPlanner.Approach a : approaches) {
                if (a.getEntry().equals2D(p.getCoords().get(0))) {
                    chosenApproach = a;
                }
            }
            leaf.setApproachNote(finder.getLastEntryNote() != null ? finder.getLastEntryNote()
                    : chosenApproach == null ? null : chosenApproach.getNearestRejected());
            if (chosenApproach != null && !chosenApproach.getEntry().equals2D(chosenApproach.getTarget())) {
                coords.add(chosenApproach.getTarget());
                checks.add(map.check(chosenApproach.getEntry(), chosenApproach.getTarget(), chosenApproach.exemptIds(), true));
            }
        }
        net.removeEdge(e);
        NetEdge ne = net.addEdge(e.getFrom(), e.getTo(), coords, checks);
        ne.setFlowTph(e.getFlowTph());
        ne.setDiameter(dn);
        ne.setCreatorPointId(e.getCreatorPointId());
        log.info("Ремонт {}→{}: ДУ{} {} м → {} м", e.getFrom(), e.getTo(), dn.getDiameter(),
                Math.round(e.length()), Math.round(ne.length()));
        return true;
    }
}
