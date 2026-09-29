package ru.lct.heat.routing;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.ObstacleMap;
import ru.lct.heat.geometry.SegmentCheck;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ref.ChamberCostTable;
import ru.lct.heat.model.ref.CostConstants;
import ru.lct.heat.model.ref.DiameterSpec;
import ru.lct.heat.model.ref.DiameterTable;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;
import ru.lct.heat.variants.Strategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Жадное построение дерева новой сети с общими магистралями (Steiner-эвристика).
 * Точки подключаются по очереди; для каждой A* ищет самый дешёвый путь до
 * <ul>
 *   <li>существующей сети (врезка / новая камера на участке), либо</li>
 *   <li>уже построенного дерева: в узел с запасом примыканий или в точку на ребре (там создаётся
 *       камера-разветвление, Техприложение §2.1). В стоимость цели входит камера и удорожание
 *       магистрали выше по потоку из-за роста ДУ.</li>
 * </ul>
 * Новые участки не пересекают друг друга (динамические препятствия в {@link PathFinder}).
 */
@Slf4j
public final class TreeBuilder {

    /** Точка на ребре дерева как цель: ребро, индекс отрезка и координата. */
    @Value
    static class EdgeJoin {
        NetEdge edge;
        int segIdx;
        Coordinate point;
    }

    private final MapCache maps;
    private final TieInCandidates tieIns;
    private final ApproachPlanner approach;
    private final RoutingParams params;
    private final Strategy strategy;
    /** Причина неподключения по id точки (заполняется в build). */
    @lombok.Getter
    private final Map<Object, String> unconnectedReasons = new java.util.LinkedHashMap<>();

    public TreeBuilder(MapCache maps, TieInCandidates tieIns, ApproachPlanner approach, RoutingParams params,
                       Strategy strategy) {
        this.maps = maps;
        this.tieIns = tieIns;
        this.approach = approach;
        this.params = params;
        this.strategy = strategy;
    }

    /** Строит сеть по стратегии; неподключённые — в unconnectedOut. */
    public NewNetwork build(List<ConnectionPoint> points, List<ConnectionPoint> unconnectedOut) {
        long t0 = System.currentTimeMillis();
        NewNetwork net = new NewNetwork();
        for (ConnectionPoint cp : strategy.sorted(points)) {
            if (!connect(cp, net)) {
                unconnectedOut.add(cp);
            }
        }
        log.info("Стратегия '{}': дерево построено за {} мс; A*: {}", strategy.getId(),
                System.currentTimeMillis() - t0, maps.stats());
        return net;
    }

    /** Подключает одну точку к сети/дереву (используется и локальной оптимизацией). */
    public boolean connect(ConnectionPoint cp, NewNetwork net) {
        DiameterSpec dn = DiameterTable.minByFlow(cp.getFlowTph()).orElse(DiameterTable.rows().get(0));
        if (strategy.getDnHints() != null) {
            DiameterSpec hint = strategy.getDnHints().get(cp.getId());
            if (hint != null && hint.getDiameter() > dn.getDiameter()) {
                dn = hint;
            }
        }
        ObstacleMap map = maps.routingMap(dn);
        // подход к зданию — по ДУ самой точки: подсказка магистрального ДУ на финальный участок не распространяется
        DiameterSpec leafDn = DiameterTable.minByFlow(cp.getFlowTph()).orElse(dn);
        ObstacleMap leafMap = maps.routingMap(leafDn);
        List<ApproachPlanner.Approach> approaches = approach.planAll(cp, leafMap, params.getVertexClearanceEpsM(),
                params.getMaxApproaches());
        if (approaches.isEmpty()) {
            log.warn("Точка {}: нет подхода к точке подключения", cp.getId());
            unconnectedReasons.put(cp.getId(), "нет допустимого подхода к точке: все точки входа в здание в зонах отступа");
            return false;
        }
        List<PathFinder.Start> starts = new ArrayList<>();
        for (ApproachPlanner.Approach a : approaches) {
            starts.add(new PathFinder.Start(a.getEntry(), dn.getCostPerMeter() * a.getEntry().distance(a.getTarget()),
                    a.getEntry().equals2D(a.getTarget()) ? null : a.getTarget()));
        }
        List<Goal> goals = new ArrayList<>();
        addTieInGoals(goals, net, dn);
        if (strategy.isTreeJoins()) {
            // цели на дереве — только в разумном радиусе от точки: далёкое присоединение к дереву не может
            // быть дешевле врезки в сеть настолько, чтобы окупить лишнюю длину; иначе тысячи целей
            // «обнуляют» эвристику A* (ближайшая цель всегда рядом, но за зданием)
            double dMin = Double.MAX_VALUE;
            for (Goal g : goals) {
                for (PathFinder.Start st : starts) {
                    dMin = Math.min(dMin, st.getPoint().distance(g.getPoint()));
                }
            }
            double radius = dMin == Double.MAX_VALUE ? Double.MAX_VALUE
                    : dMin * params.getTreeJoinRadiusFactor() + params.getTreeJoinRadiusExtraM();
            addTreeGoals(goals, net, cp, dn, starts, radius);
        }
        dropDegenerateGoals(goals, starts);
        if (goals.isEmpty()) {
            log.warn("Точка {}: нет доступных точек присоединения", cp.getId());
            unconnectedReasons.put(cp.getId(), "нет доступных точек присоединения к существующей сети");
            return false;
        }
        log.debug("Точка {}: целей {} (врезок {}), стартов {}", cp.getId(), goals.size(),
                goals.stream().filter(g -> g.getRef() instanceof TieInCandidates.Candidate).count(), approaches.size());

        PathFinder finder = maps.finder(dn);
        List<LineString> built = new ArrayList<>();
        for (NetEdge e : net.getEdges()) {
            built.add(GeomUtil.line(e.getCoords()));
        }
        finder.setDynamicObstacles(built, params.getNewPipeClearanceM() + dn.getWidthM());
        finder.setStartMap(leafMap == map ? null : leafMap);
        Optional<Path> path;
        try {
            path = params.isNearestEntryOnly() ? finder.findPreferFirst(starts, goals, params.getNearestEntryMaxDetourFactor(), params.getNearestEntryMinDetourM()) : finder.find(starts, goals);
        } finally {
            finder.setDynamicObstacles(Collections.emptyList(), 0);
            finder.setStartMap(null);
        }
        if (path.isEmpty()) {
            path = fallback(cp, net, dn, starts, goals, approaches);
        }
        if (path.isEmpty()) {
            if (log.isWarnEnabled()) {
                StringBuilder sb = new StringBuilder();
                for (PathFinder.Start st : starts) {
                    sb.append(String.format(java.util.Locale.ROOT, " (%.1f %.1f)", st.getPoint().x, st.getPoint().y));
                }
                log.warn("Точка {}: маршрут не найден; точки входа:{}", cp.getId(), sb);
            }
            unconnectedReasons.put(cp.getId(), "маршрут до существующей сети с соблюдением отступов не найден");
            return false;
        }
        Path p = path.get();
        ApproachPlanner.Approach a = approaches.stream()
                .filter(x -> x.getEntry().equals2D(p.getCoords().get(0)))
                .findFirst().orElseThrow();

        NetNode parent = attachPoint(p.getGoal().getRef(), net, dn);
        NetNode leaf = net.addNode(cp.getId(), cp.getGeometry().getCoordinate(), NetNode.Kind.OKS_POINT);
        leaf.setApproachNote(finder.getLastEntryNote() != null ? finder.getLastEntryNote() : a.getNearestRejected());

        List<Coordinate> coords = new ArrayList<>(p.getCoords());
        Collections.reverse(coords);
        List<SegmentCheck> checks = new ArrayList<>(p.getChecks());
        Collections.reverse(checks);
        if (!a.getEntry().equals2D(a.getTarget())) {
            coords.add(a.getTarget());
            checks.add(leafMap.check(a.getEntry(), a.getTarget(), a.exemptIds(), true));
        }
        NetEdge e = net.addEdge(parent, leaf, coords, checks);
        e.setFlowTph(cp.getFlowTph());
        e.setDiameter(DiameterTable.minByFlow(cp.getFlowTph()).orElse(dn));
        e.setCreatorPointId(cp.getId());
        // расход и ДУ вверх по дереву — для оценки удорожания следующими точками
        propagateFlow(net, parent, cp.getFlowTph());

        log.info("Точка {} ({} т/ч, ДУ{}): {} м, {} поворотов → {}", cp.getId(), cp.getFlowTph(),
                dn.getDiameter(), Math.round(p.getLength()), p.turns(), describe(p.getGoal().getRef()));
        return true;
    }

    /**
     * Присоединяет существующий узел дерева (начало поддерева с расходом flowTph) к существующей сети
     * другим маршрутом — ход локальной оптимизации «другая врезка магистрали». Узел должен быть без
     * входящего ребра. Дерево при этом не меняется, только маршрут до сети.
     */
    public boolean connectSubtree(NetNode top, double flowTph, NewNetwork net) {
        return connectSubtree(top, flowTph, net, null);
    }

    /**
     * То же, но с возможностью присоединить поддерево к остальному дереву: excludeNodes — узлы самого
     * поддерева (к ним присоединяться нельзя — цикл); null — только к существующей сети.
     */
    public boolean connectSubtree(NetNode top, double flowTph, NewNetwork net, java.util.Set<NetNode> excludeNodes) {
        DiameterSpec dn = DiameterTable.minByFlow(flowTph).orElse(DiameterTable.rows().get(0));
        ObstacleMap map = maps.routingMap(dn);
        List<Goal> goals = new ArrayList<>();
        addTieInGoals(goals, net, dn);
        if (excludeNodes != null && strategy.isTreeJoins()) {
            double dMin = Double.MAX_VALUE;
            for (Goal g : goals) {
                dMin = Math.min(dMin, top.getCoord().distance(g.getPoint()));
            }
            double radius = dMin == Double.MAX_VALUE ? Double.MAX_VALUE
                    : dMin * params.getTreeJoinRadiusFactor() + params.getTreeJoinRadiusExtraM();
            List<Goal> tree = new ArrayList<>();
            List<PathFinder.Start> starts = Collections.singletonList(new PathFinder.Start(top.getCoord(), 0));
            addTreeGoals(tree, net, new ConnectionPoint(top.getId(), GeomUtil.point(top.getCoord()), flowTph),
                    dn, starts, radius);
            for (Goal g : tree) {
                Object ref = g.getRef();
                NetNode n = ref instanceof NetNode ? (NetNode) ref
                        : ref instanceof EdgeJoin ? ((EdgeJoin) ref).getEdge().getTo() : null;
                NetNode from = ref instanceof EdgeJoin ? ((EdgeJoin) ref).getEdge().getFrom() : n;
                if (n != null && (excludeNodes.contains(n) || excludeNodes.contains(from))) {
                    continue;
                }
                goals.add(g);
            }
        }
        dropDegenerateGoals(goals, Collections.singletonList(new PathFinder.Start(top.getCoord(), 0)));
        if (goals.isEmpty()) {
            return false;
        }
        PathFinder finder = maps.finder(dn);
        List<LineString> built = new ArrayList<>();
        for (NetEdge e : net.getEdges()) {
            built.add(GeomUtil.line(e.getCoords()));
        }
        finder.setDynamicObstacles(built, params.getNewPipeClearanceM() + dn.getWidthM());
        Optional<Path> path;
        try {
            path = finder.find(Collections.singletonList(new PathFinder.Start(top.getCoord(), 0)), goals);
        } finally {
            finder.setDynamicObstacles(Collections.emptyList(), 0);
        }
        if (path.isEmpty()) {
            return false;
        }
        Path p = path.get();
        NetNode parent = attachPoint(p.getGoal().getRef(), net, dn);
        List<Coordinate> coords = new ArrayList<>(p.getCoords());
        Collections.reverse(coords);
        List<SegmentCheck> checks = new ArrayList<>(p.getChecks());
        Collections.reverse(checks);
        NetEdge e = net.addEdge(parent, top, coords, checks);
        e.setFlowTph(flowTph);
        e.setDiameter(dn);
        return true;
    }

    /**
     * Резервный поиск по мелкому растру: граф видимости не находит путь в стеснённой застройке
     * (коридор 1–2 м между зонами отступа), хотя по правилам он есть. Запускается только после неудачи
     * основного поиска, поэтому на качество обычных трасс не влияет.
     */
    private Optional<Path> fallback(ConnectionPoint cp, NewNetwork net, DiameterSpec dn,
                                    List<PathFinder.Start> starts, List<Goal> goals,
                                    List<ApproachPlanner.Approach> approaches) {
        if (params.getFallbackGridStepM() <= 0) {
            return Optional.empty();
        }
        ObstacleMap exact = maps.map(dn);
        FallbackGridFinder grid = new FallbackGridFinder(exact, params);
        List<Coordinate> goalPoints = new ArrayList<>();
        for (Goal g : goals) {
            goalPoints.add(g.getPoint());
        }
        for (PathFinder.Start st : starts) {
            Set<Object> exempt = approaches.stream()
                    .filter(a -> a.getEntry().equals2D(st.getPoint()))
                    .findFirst().map(ApproachPlanner.Approach::exemptIds).orElse(Collections.emptySet());
            List<Coordinate> coords = grid.find(st.getPoint(), goalPoints, exempt);
            if (coords == null) {
                continue;
            }
            Coordinate end = coords.get(coords.size() - 1);
            Goal goal = goals.stream().filter(g -> g.getPoint().equals2D(end)).findFirst().orElse(null);
            if (goal == null) {
                continue;
            }
            List<SegmentCheck> checks = new ArrayList<>();
            double length = 0;
            for (int i = 0; i + 1 < coords.size(); i++) {
                checks.add(exact.check(coords.get(i), coords.get(i + 1), exempt));
                length += coords.get(i).distance(coords.get(i + 1));
            }
            double cost = st.getInitialCost() + length * dn.getCostPerMeter() + goal.getTerminalCost();
            log.info("Точка {}: путь найден резервным растровым поиском, {} м", cp.getId(), Math.round(length));
            return Optional.of(new Path(coords, cost, length, goal, checks));
        }
        return Optional.empty();
    }

    private void addTieInGoals(List<Goal> goals, NewNetwork net, DiameterSpec dn) {
        List<NetNode> newTieIns = new ArrayList<>();
        for (NetNode n : net.nodeList()) {
            if (n.getKind() == NetNode.Kind.TIE_IN_CHAMBER) {
                newTieIns.add(n);
            }
        }
        boolean rootsExhausted = strategy.getMaxRoots() > 0 && net.roots().size() >= strategy.getMaxRoots();
        for (TieInCandidates.Candidate c : tieIns.all()) {
            if (strategy.getTieInFilter() != null && !strategy.getTieInFilter().test(c.getPoint())) {
                continue;
            }
            if (rootsExhausted) {
                // лимит врезок исчерпан: присоединяемся только к уже существующим корням
                NetNode root = c.isChamber() ? net.node(c.getChamber().getId()) : null;
                boolean isExistingRoot = root != null && root.degree() > 0;
                if (!isExistingRoot) {
                    boolean atNewTieIn = false;
                    for (NetNode n : newTieIns) {
                        if (n.getCoord().distance(c.getPoint()) < CostConstants.EXISTING_CHAMBER_SNAP_M) {
                            atNewTieIn = true;
                            break;
                        }
                    }
                    if (!atNewTieIn) {
                        continue;
                    }
                }
            }
            if (!c.isChamber()) {
                // рядом с уже созданной новой камерой на сети вторую не ставим — присоединяемся к ней
                boolean nearNew = false;
                for (NetNode n : newTieIns) {
                    if (n.getCoord().distance(c.getPoint()) < CostConstants.EXISTING_CHAMBER_SNAP_M) {
                        nearNew = true;
                        break;
                    }
                }
                if (nearNew) {
                    continue;
                }
            } else {
                NetNode existing = net.node(c.getChamber().getId());
                int used = tieIns.chamberDegree(c.getChamber()) + (existing == null ? 0 : existing.degree());
                if (used >= CostConstants.MAX_CHAMBER_CONNECTIONS) {
                    continue;
                }
            }
            goals.add(new Goal(c.getPoint(), c.terminalCost(dn.getDiameter()), c));
        }
    }

    private void addTreeGoals(List<Goal> goals, NewNetwork net, ConnectionPoint cp, DiameterSpec dn,
                              List<PathFinder.Start> starts, double radius) {
        double step = params.getTreeJoinSampleStepM();
        // камера-разветвление не может стоять внутри спецзоны (спецпроход — один прямой участок) или зоны отступа
        ObstacleMap map = maps.routingMap(dn);
        java.util.Set<Object> none = Collections.emptySet();
        for (NetNode n : net.nodeList()) {
            if (n.getKind() == NetNode.Kind.OKS_POINT || n.getKind() == NetNode.Kind.EXISTING_CHAMBER) {
                continue; // существующие камеры уже среди целей врезки; в точку ОКС не ветвимся
            }
            // камера на существующем участке уже занята двумя частями этого участка
            int used = n.degree() + (n.getKind() == NetNode.Kind.TIE_IN_CHAMBER ? 2 : 0);
            if (used >= CostConstants.MAX_CHAMBER_CONNECTIONS) {
                continue;
            }
            if (!within(n.getCoord(), starts, radius)) {
                continue;
            }
            double upstream = upstreamDeltaCost(net, n, cp.getFlowTph());
            double chamber = chamberDelta(net, n, dn, cp.getFlowTph());
            goals.add(new Goal(n.getCoord(), upstream + chamber, n));
        }
        for (NetEdge e : net.getEdges()) {
            List<Coordinate> cs = e.getCoords();
            int segCount = cs.size() - 1;
            // финальный отрезок к точке ОКС внутри её здания (зона отступа не действует только на
            // сам отрезок) — камеру-разветвление там ставить нельзя
            boolean exemptFinal = e.getTo().getKind() == NetNode.Kind.OKS_POINT
                    && approach.ownPolygonId(e.getTo().getId()).isPresent();
            double upstream = upstreamDeltaCost(net, e.getFrom(), cp.getFlowTph())
                    + edgeDeltaCost(e, cp.getFlowTph());
            double chamber = ChamberCostTable.newChamberCost(Math.max(dn.getDiameter(),
                    DiameterTable.minByFlow(e.getFlowTph() + cp.getFlowTph()).map(DiameterSpec::getDiameter).orElse(1400)));
            for (int i = 0; i < segCount; i++) {
                Coordinate a = cs.get(i);
                Coordinate b = cs.get(i + 1);
                if (i > 0 && within(a, starts, radius) && map.isPointFree(a, none)) {
                    goals.add(new Goal(a, upstream + chamber, new EdgeJoin(e, i, a)));
                }
                if (exemptFinal && i == segCount - 1) {
                    continue;
                }
                double len = a.distance(b);
                for (double d = step; d < len - step / 2; d += step) {
                    Coordinate j = GeomUtil.along(a, b, d);
                    if (within(j, starts, radius) && map.isPointFree(j, none)) {
                        goals.add(new Goal(j, upstream + chamber, new EdgeJoin(e, i, j)));
                    }
                }
            }
        }
    }

    /**
     * Убирает цели, совпадающие с точкой старта: иначе получается участок нулевой длины (точка ОКС
     * лежит ровно на существующей трубе или на уже построенном ребре). LineString нулевой длины —
     * невалидная геометрия в выдаче, поэтому такую цель просто не рассматриваем: трасса пойдёт в
     * другое место присоединения либо точка останется неподключённой с указанием причины.
     */
    private static void dropDegenerateGoals(List<Goal> goals, List<PathFinder.Start> starts) {
        goals.removeIf(g -> {
            for (PathFinder.Start st : starts) {
                if (st.getPoint().distance(g.getPoint()) < MIN_NEW_EDGE_M) {
                    return true;
                }
            }
            return false;
        });
    }

    /** Минимальная длина нового участка, м: короче — вырожденная геометрия. */
    private static final double MIN_NEW_EDGE_M = 0.5;

    private static boolean within(Coordinate c, List<PathFinder.Start> starts, double radius) {
        for (PathFinder.Start st : starts) {
            if (st.getPoint().distance(c) <= radius) {
                return true;
            }
        }
        return false;
    }

    /** Удорожание рёбер от узла n до корня при добавлении расхода extraFlow (рост ДУ). */
    private static double upstreamDeltaCost(NewNetwork net, NetNode n, double extraFlow) {
        double delta = 0;
        NetEdge in;
        NetNode cur = n;
        while ((in = net.incoming(cur)) != null) {
            delta += edgeDeltaCost(in, extraFlow);
            cur = in.getFrom();
        }
        return delta;
    }

    private static double edgeDeltaCost(NetEdge e, double extraFlow) {
        DiameterSpec now = DiameterTable.minByFlow(e.getFlowTph()).orElse(e.getDiameter());
        DiameterSpec then = DiameterTable.minByFlow(e.getFlowTph() + extraFlow).orElse(now);
        return e.length() * (then.getCostPerMeter() - now.getCostPerMeter());
    }

    /** Разница стоимости камеры в узле при добавлении ветки ДУ dn (0, если узел не камера или ДУ не растёт). */
    private static double chamberDelta(NewNetwork net, NetNode n, DiameterSpec dn, double extraFlow) {
        int maxNow = n.getExistingMaxDiameter();
        for (NetEdge e : n.getEdges()) {
            maxNow = Math.max(maxNow, e.getDiameter().getDiameter());
        }
        int maxThen = Math.max(maxNow, dn.getDiameter());
        NetEdge in = net.incoming(n);
        if (in != null) {
            maxThen = Math.max(maxThen, DiameterTable.minByFlow(in.getFlowTph() + extraFlow)
                    .map(DiameterSpec::getDiameter).orElse(maxThen));
        }
        if (n.getKind() == NetNode.Kind.TECH_NODE) {
            return ChamberCostTable.newChamberCost(maxThen); // техузел становится камерой
        }
        return ChamberCostTable.newChamberCost(maxThen) - ChamberCostTable.newChamberCost(maxNow);
    }

    /** Узел, к которому примыкает новая ветка: существующая сеть, узел дерева или деление ребра. */
    private NetNode attachPoint(Object ref, NewNetwork net, DiameterSpec dn) {
        if (ref instanceof TieInCandidates.Candidate) {
            TieInCandidates.Candidate cand = (TieInCandidates.Candidate) ref;
            if (cand.isChamber()) {
                Object id = cand.getChamber().getId();
                NetNode n = net.node(id);
                if (n == null) {
                    n = net.addNode(id, cand.getPoint(), NetNode.Kind.EXISTING_CHAMBER);
                    n.setExistingMaxDiameter(cand.getExistingMaxDiameter());
                }
                return n;
            }
            NetNode n = net.addGeneratedNode(cand.getPoint(), NetNode.Kind.TIE_IN_CHAMBER);
            n.setTieInPipeId(cand.getPipe().getId());
            n.setExistingMaxDiameter(cand.getExistingMaxDiameter());
            return n;
        }
        if (ref instanceof NetNode) {
            NetNode n = (NetNode) ref;
            if (n.getKind() == NetNode.Kind.TECH_NODE) {
                n.setKind(NetNode.Kind.BRANCH_CHAMBER);
            }
            return n;
        }
        EdgeJoin j = (EdgeJoin) ref;
        return net.splitEdge(j.getEdge(), j.getSegIdx(), j.getPoint(), NetNode.Kind.BRANCH_CHAMBER);
    }

    private static void propagateFlow(NewNetwork net, NetNode from, double flow) {
        NetEdge in;
        NetNode cur = from;
        while ((in = net.incoming(cur)) != null) {
            in.setFlowTph(in.getFlowTph() + flow);
            DiameterTable.minByFlow(in.getFlowTph()).ifPresent(in::setDiameter);
            cur = in.getFrom();
        }
    }

    private static String describe(Object ref) {
        if (ref instanceof TieInCandidates.Candidate) {
            TieInCandidates.Candidate c = (TieInCandidates.Candidate) ref;
            return c.isChamber() ? "существующая камера " + c.getChamber().getId()
                    : "новая камера на участке " + c.getPipe().getId();
        }
        if (ref instanceof NetNode) {
            return "узел дерева " + ref;
        }
        return "разветвление на ребре " + ((EdgeJoin) ref).getEdge().getFrom() + "→" + ((EdgeJoin) ref).getEdge().getTo();
    }
}
