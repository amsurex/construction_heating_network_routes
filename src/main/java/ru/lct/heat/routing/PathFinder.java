package ru.lct.heat.routing;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.ObstacleMap;
import ru.lct.heat.geometry.SegmentCheck;
import ru.lct.heat.model.ref.CostConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Поиск пути A* по графу видимости. Узлы — старт, вершины-кандидаты карты препятствий и цели;
 * рёбра — прямые отрезки, проходящие проверку {@link ObstacleMap#check}. Угол поворота в узле
 * ограничен (≤ 90°, Техприложение §2.1) и штрафуется.
 *
 * Стоимость ребра, руб.: c(ДУ) · (L + Σ(Kспец−1)·L_зоны + штраф_поворота).
 * Экземпляр привязан к одной карте (одному ДУ) и кэширует проверки видимости между вершинами.
 */
@Slf4j
public final class PathFinder {

    /** Допуск на угол поворота, градусы (плавающая точка при ровно 90°). */
    public static final double ANGLE_TOL_DEG = 1e-3;

    private final ObstacleMap map;
    private final RoutingParams params;
    private final Set<Object> exemptIds;
    private final List<Coordinate> vertices;
    private final STRtree vertexIndex;
    /** Переопределение лимита раскрытий (0 — из params). */
    private int expansionLimit = 0;
    /**
     * Карта для рёбер, выходящих из стартовых точек (подход к зданию несёт только расход самой точки,
     * а маршрут ищется по карте магистрального ДУ); null — та же карта.
     */
    private ObstacleMap startMap = null;

    public void setExpansionLimit(int limit) {
        this.expansionLimit = limit;
    }

    public void setStartMap(ObstacleMap startMap) {
        this.startMap = startMap;
    }

    /** Накопленная статистика поиска (для логов производительности). */
    private long totalExpansions = 0;
    private long totalQueries = 0;
    private long totalMillis = 0;
    /** Динамические препятствия — уже построенные новые участки (пересекать нельзя, примыкать концом можно). */
    private STRtree dynamicIndex = null;
    private double dynamicClearance = 0;

    /** Буфер вокруг уже построенного участка + сама линия. */
    private static final class Dynamic {
        final LineString line;
        final PreparedGeometry buffer;

        Dynamic(LineString line, PreparedGeometry buffer) {
            this.line = line;
            this.buffer = buffer;
        }
    }

    public PathFinder(ObstacleMap map, RoutingParams params) {
        this(map, params, Collections.emptySet());
    }

    public PathFinder(ObstacleMap map, RoutingParams params, Set<Object> exemptIds) {
        this.map = map;
        this.params = params;
        this.exemptIds = exemptIds;
        this.vertices = map.getVertices();
        this.vertexIndex = new STRtree();
        for (int i = 0; i < vertices.size(); i++) {
            vertexIndex.insert(new Envelope(vertices.get(i)), i);
        }
        vertexIndex.build();
    }

    /**
     * Задаёт уже построенные новые участки как препятствия: новые участки не должны пересекаться
     * между собой вне общего узла (Техприложение §2.1). Отрезок может только заканчиваться на таком
     * участке (присоединение в камере-разветвлении). clearance — минимальный зазор между осями, м.
     */
    public void setDynamicObstacles(List<LineString> lines, double clearance) {
        if (lines.isEmpty()) {
            dynamicIndex = null;
            return;
        }
        dynamicIndex = new STRtree();
        dynamicClearance = clearance;
        for (LineString l : lines) {
            Geometry buf = l.buffer(clearance);
            dynamicIndex.insert(buf.getEnvelopeInternal(), new Dynamic(l, PreparedGeometryFactory.prepare(buf)));
        }
        dynamicIndex.build();
    }

    /**
     * Отрезок a→b против уже построенных участков. Касание чужого участка допустимо только в точке,
     * которая является стартом (aTerminal) или целью (bTerminal) поиска — т.е. настоящим примыканием,
     * а не случайной общей вершиной графа.
     */
    @SuppressWarnings("unchecked")
    private boolean dynamicBlocked(Coordinate a, Coordinate b, boolean aTerminal, boolean bTerminal) {
        if (dynamicIndex == null) {
            return false;
        }
        LineString seg = GeomUtil.segment(a, b);
        List<Dynamic> near = dynamicIndex.query(seg.getEnvelopeInternal());
        for (Dynamic d : near) {
            if (!d.buffer.intersects(seg)) {
                continue;
            }
            Geometry inter = d.line.intersection(seg);
            if (inter.getDimension() != 0 || inter.getNumGeometries() != 1) {
                return true;
            }
            Coordinate p = inter.getCoordinate();
            boolean junction = (bTerminal && p.distance(b) < 1e-6) || (aTerminal && p.distance(a) < 1e-6);
            if (!junction) {
                return true;
            }
            if (seg.intersection(d.buffer.getGeometry()).getLength() > dynamicClearance * 4) {
                return true;
            }
        }
        return false;
    }

    /** Состояние поиска: узел + откуда пришли (для проверки угла поворота). */
    private static final class State implements Comparable<State> {
        final int cur;
        final double g;
        final double f;
        final State parent;
        /** Ребро parent→cur проверено на видимость (ленивый A*). */
        final boolean verified;
        final double turnDeg;

        State(int cur, double g, double f, State parent, boolean verified, double turnDeg) {
            this.cur = cur;
            this.g = g;
            this.f = f;
            this.parent = parent;
            this.verified = verified;
            this.turnDeg = turnDeg;
        }

        @Override
        public int compareTo(State o) {
            return Double.compare(f, o.f);
        }
    }

    /**
     * Ищет самый дешёвый путь от start до одной из целей. Пустой результат — маршрута нет.
     * Состояние A* — узел; угол поворота проверяется относительно родителя лучшего пути в узел
     * (не строго оптимально при ограничении угла, но на порядок быстрее состояний (prev, cur)).
     */
    public Optional<Path> find(Coordinate start, List<Goal> goals) {
        return find(Collections.singletonList(new Start(start, 0)), goals);
    }

    /**
     * Старт поиска: точка, начальная стоимость (например, длина подхода к точке ОКС) и точка,
     * из которой трасса «приходит» в старт (для проверки угла поворота в старте; null — без проверки).
     */
    @lombok.Value
    public static class Start {
        Coordinate point;
        double initialCost;
        Coordinate before;

        public Start(Coordinate point, double initialCost) {
            this(point, initialCost, null);
        }

        public Start(Coordinate point, double initialCost, Coordinate before) {
            this.point = point;
            this.initialCost = initialCost;
            this.before = before;
        }
    }

    /**
     * Поиск с приоритетом первого старта (Техприложение §2.2: финальный участок — от ближайшей к точке
     * границы полигона): сначала только первый старт, остальные — лишь если от него пути нет.
     */
    public Optional<Path> findPreferFirst(List<Start> starts, List<Goal> goals) {
        return findPreferFirst(starts, goals, 0);
    }

    /**
     * @param maxDetourFactor если > 1: путь через ближайшую границу принимается, только если он не длиннее
     *                        лучшего пути через другие точки входа более чем в это число раз — иначе это
     *                        «необоснованный обход» (Техприложение §2.1) и берётся альтернатива
     */
    public Optional<Path> findPreferFirst(List<Start> starts, List<Goal> goals, double maxDetourFactor) {
        return findPreferFirst(starts, goals, maxDetourFactor, 0);
    }

    /**
     * @param minDetourM обход считается необоснованным, только если, помимо превышения фактора, трасса через
     *                   ближайшую границу длиннее альтернативы не менее чем на это число метров (полная
     *                   длина трубы вместе с финальным участком до точки)
     */
    public Optional<Path> findPreferFirst(List<Start> starts, List<Goal> goals, double maxDetourFactor, double minDetourM) {
        lastEntryNote = null;
        if (starts.size() <= 1) {
            return find(starts, goals);
        }
        Optional<Path> first = find(starts.subList(0, 1), goals);
        if (first.isPresent() && maxDetourFactor <= 1) {
            return first;
        }
        Optional<Path> rest = find(starts.subList(1, starts.size()), goals);
        if (first.isEmpty()) {
            if (rest.isPresent()) {
                lastEntryNote = "вход не через ближайшую границу: от неё нет допустимого маршрута"
                        + " (тупик/двор, отступы), взята ближайшая достижимая точка входа";
            }
            return rest;
        }
        if (rest.isPresent()) {
            double viaFirst = pipeLength(first.get(), starts);
            double viaRest = pipeLength(rest.get(), starts);
            if (viaFirst > viaRest * maxDetourFactor && viaFirst - viaRest >= minDetourM) {
                log.debug("Вход через ближайшую границу — обход {} м против {} м, берём альтернативу",
                        Math.round(viaFirst), Math.round(viaRest));
                lastEntryNote = String.format(Locale.ROOT,
                        "вход не через ближайшую границу: трасса через неё %d м против %d м — необоснованный обход (§2.1)",
                        Math.round(viaFirst), Math.round(viaRest));
                return rest;
            }
        }
        return first;
    }

    /** Пояснение к последнему {@link #findPreferFirst}, если выбран вход не через ближайшую границу; иначе null. */
    public String getLastEntryNote() {
        return lastEntryNote;
    }

    private String lastEntryNote;

    /** Полная длина трубы до точки: маршрут плюс финальный участок от точки входа (старта) до точки ОКС. */
    private static double pipeLength(Path path, List<Start> starts) {
        Coordinate entry = path.getCoords().get(0);
        for (Start s : starts) {
            if (s.getPoint().equals2D(entry)) {
                return path.getLength() + (s.getBefore() == null ? 0 : entry.distance(s.getBefore()));
            }
        }
        return path.getLength();
    }

    /**
     * Ищет самый дешёвый путь от одного из стартов до одной из целей. В {@link Path#getCost()} входит
     * initialCost выбранного старта; {@link Path#getCoords()} начинается с точки старта.
     */
    public Optional<Path> find(List<Start> starts, List<Goal> goals) {
        if (goals.isEmpty() || starts.isEmpty()) {
            return Optional.empty();
        }
        long t0 = System.currentTimeMillis();
        double c = map.getDiameter().getCostPerMeter();
        int nV = vertices.size();
        int startBase = nV;
        int goalBase = nV + starts.size();
        // узлы: [0..nV) вершины, [startBase..goalBase) старты, [goalBase..) — цели
        Coordinate[] node = new Coordinate[nV + starts.size() + goals.size()];
        for (int i = 0; i < nV; i++) {
            node[i] = vertices.get(i);
        }
        for (int i = 0; i < starts.size(); i++) {
            node[startBase + i] = starts.get(i).getPoint();
        }
        for (int i = 0; i < goals.size(); i++) {
            node[goalBase + i] = goals.get(i).getPoint();
        }
        double minTerminal = goals.stream().mapToDouble(Goal::getTerminalCost).min().orElse(0);
        STRtree goalIndex = new STRtree();
        for (int i = 0; i < goals.size(); i++) {
            goalIndex.insert(new Envelope(goals.get(i).getPoint()), goalBase + i);
        }
        goalIndex.build();
        double[] hCache = new double[node.length];
        java.util.Arrays.fill(hCache, -1);

        PriorityQueue<State> open = new PriorityQueue<>();
        boolean[] closed = new boolean[node.length];
        for (int i = 0; i < starts.size(); i++) {
            Start st = starts.get(i);
            double g = st.getInitialCost();
            open.add(new State(startBase + i, g, g + heuristic(startBase + i, node, goalIndex, hCache, c) + minTerminal,
                    null, true, 0));
        }

        // Ленивый A*: соседи кладутся в очередь с оптимистичной стоимостью (без проверки видимости),
        // проверка выполняется при извлечении — только для перспективных рёбер.
        int expansions = 0;
        int pops = 0;
        int popLimit = params.getMaxChecksPerSearch();
        List<Integer> candidates = new ArrayList<>();
        while (!open.isEmpty()) {
            State s = open.poll();
            if (closed[s.cur]) {
                continue;
            }
            if (++pops > popLimit) {
                log.debug("A*: превышен лимит проверок {} — цели недостижимы или слишком далеко", popLimit);
                break;
            }
            if (!s.verified) {
                State par = s.parent;
                Coordinate a = node[par.cur];
                Coordinate b = node[s.cur];
                boolean parIsStart = par.cur >= startBase && par.cur < goalBase;
                // старт без подхода (before == null) — сама точка ОКС: может лежать в спецзоне
                boolean startIsOks = parIsStart && starts.get(par.cur - startBase).getBefore() == null;
                SegmentCheck chk = parIsStart
                        ? (startMap != null ? startMap : map).check(a, b, exemptIds, startIsOks, false)
                        : checkCached(par.cur, s.cur, a, b);
                if (!chk.isFree() || dynamicBlocked(a, b, parIsStart, s.cur >= goalBase)) {
                    continue;
                }
                double extra = c * chk.extraLengthEquivalent();
                if (extra > 1e-9) {
                    // уточнённая стоимость выше оптимистичной — возвращаем в очередь с честным g
                    double g = s.g + extra;
                    double h = s.cur >= goalBase ? 0 : heuristic(s.cur, node, goalIndex, hCache, c) + minTerminal;
                    open.add(new State(s.cur, g, g + h, par, true, s.turnDeg));
                    continue;
                }
                s = new State(s.cur, s.g, s.f, par, true, s.turnDeg);
            }
            closed[s.cur] = true;
            if (s.cur >= goalBase) {
                totalQueries++;
                totalExpansions += expansions;
                totalMillis += System.currentTimeMillis() - t0;
                State root = s;
                while (root.parent != null) {
                    root = root.parent;
                }
                boolean startIsOks = starts.get(root.cur - startBase).getBefore() == null;
                Path p = reconstruct(s, node, goals.get(s.cur - goalBase), c, startIsOks);
                log.debug("Путь найден: {} вершин, {} м, {} руб., {} раскрытий, {} мс",
                        p.getCoords().size(), Math.round(p.getLength()), Math.round(p.getCost()),
                        expansions, System.currentTimeMillis() - t0);
                return Optional.of(p);
            }
            int limit = expansionLimit > 0 ? expansionLimit : params.getMaxExpansions();
            if (++expansions > limit) {
                log.debug("A*: превышен лимит раскрытий {}", limit);
                break;
            }
            Coordinate cur = node[s.cur];
            Coordinate prev = s.parent != null ? node[s.parent.cur]
                    : (s.cur >= startBase && s.cur < goalBase ? starts.get(s.cur - startBase).getBefore() : null);
            collectCandidates(cur, s.cur, node, goalIndex, hCache, c, candidates);
            for (int next : candidates) {
                if (closed[next] || (next >= startBase && next < goalBase)) {
                    continue;
                }
                Coordinate nc = node[next];
                double turnDeg = 0;
                if (prev != null) {
                    turnDeg = GeomUtil.turnAngleDeg(prev, cur, nc);
                    if (turnDeg > CostConstants.MAX_TURN_ANGLE_DEG + ANGLE_TOL_DEG) {
                        continue;
                    }
                }
                double len = cur.distance(nc);
                double g = s.g + c * (len + params.getTurnPenaltyMPer45Deg() * turnDeg / 45.0);
                double h;
                if (next >= goalBase) {
                    g += goals.get(next - goalBase).getTerminalCost();
                    h = 0;
                } else {
                    h = heuristic(next, node, goalIndex, hCache, c) + minTerminal;
                }
                open.add(new State(next, g, g + h, s, false, turnDeg));
            }
        }
        totalQueries++;
        totalExpansions += expansions;
        totalMillis += System.currentTimeMillis() - t0;
        log.debug("Путь не найден: {} раскрытий, {} мс", expansions, System.currentTimeMillis() - t0);
        return Optional.empty();
    }

    /** Соседи-кандидаты: вершины и цели в радиусе max(R, расстояние до ближайшей цели). */
    @SuppressWarnings("unchecked")
    private void collectCandidates(Coordinate cur, int curIdx, Coordinate[] node, STRtree goalIndex,
                                   double[] hCache, double c, List<Integer> out) {
        out.clear();
        double nearestGoal = heuristic(curIdx, node, goalIndex, hCache, c) / c;
        double r = Math.max(params.getNeighborRadiusM(), nearestGoal * 1.05 + 1.0);
        Envelope env = new Envelope(cur.x - r, cur.x + r, cur.y - r, cur.y + r);
        out.addAll(vertexIndex.query(env));
        out.addAll(goalIndex.query(env));
    }

    /** Эвристика: c · расстояние до ближайшей цели (кэш по узлу). */
    private static double heuristic(int idx, Coordinate[] node, STRtree goalIndex, double[] hCache, double c) {
        if (hCache[idx] >= 0) {
            return hCache[idx];
        }
        Coordinate from = node[idx];
        Envelope env = new Envelope(from);
        Object nearest = goalIndex.nearestNeighbour(env, idx, (a, b) -> {
            Coordinate ca = node[(Integer) a.getItem()];
            Coordinate cb = node[(Integer) b.getItem()];
            return ca.distance(cb);
        });
        double h = nearest == null ? 0 : c * from.distance(node[(Integer) nearest]);
        hCache[idx] = h;
        return h;
    }

    private SegmentCheck checkCached(int i, int j, Coordinate a, Coordinate b) {
        int nV = vertices.size();
        if (i < nV && j < nV && exemptIds.isEmpty()) {
            return map.checkVertices(i, j);
        }
        return map.check(a, b, exemptIds);
    }

    public String stats() {
        return "запросов " + totalQueries + ", раскрытий " + totalExpansions + ", " + totalMillis + " мс; карта: "
                + map.stats();
    }

    private Path reconstruct(State end, Coordinate[] node, Goal goal, double c, boolean startIsOks) {
        List<Coordinate> coords = new ArrayList<>();
        for (State s = end; s != null; s = s.parent) {
            coords.add(node[s.cur]);
        }
        Collections.reverse(coords);
        List<SegmentCheck> checks = new ArrayList<>(coords.size() - 1);
        double len = 0;
        for (int i = 0; i + 1 < coords.size(); i++) {
            ObstacleMap m = i == 0 && startMap != null ? startMap : map;
            boolean aOks = i == 0 && startIsOks;
            checks.add(m.check(coords.get(i), coords.get(i + 1), exemptIds, aOks, false));
            len += coords.get(i).distance(coords.get(i + 1));
        }
        return new Path(coords, end.g, len, goal, checks);
    }
}
