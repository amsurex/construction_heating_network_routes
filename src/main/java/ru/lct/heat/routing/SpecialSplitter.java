package ru.lct.heat.routing;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.Obstacle;
import ru.lct.heat.geometry.ObstacleMap;
import ru.lct.heat.geometry.SegmentCheck;
import ru.lct.heat.model.LayingMethod;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.TreeSet;

/**
 * Разбиение рёбер на обычные и специальные участки (Техприложение §4).
 * Спецпроход — прямой отрезок; на границах спецзон (полигон + отступ, либо ±отступ от точки
 * пересечения линейного объекта) участок делится, в точке деления создаётся технический узел.
 * При наложении зон на общем фрагменте Kспец = max, а при изменении набора зон начинается новый участок.
 */
@Slf4j
public final class SpecialSplitter {

    private final ApproachPlanner approach;
    private final MapCache maps;

    public SpecialSplitter() {
        this(null, null);
    }

    /**
     * @param approach планировщик подходов — чтобы спецучасток, доходящий до здания, заканчивался в точке ОКС
     * @param maps     карты препятствий: проверки отрезков пересчитываются по точному ДУ ребра перед разбиением
     *                 (ДУ и геометрия могли измениться после построения — подбор ДУ, полировка, локальный поиск)
     */
    public SpecialSplitter(ApproachPlanner approach, MapCache maps) {
        this.approach = approach;
        this.maps = maps;
    }

    /** Пересчёт проверок отрезков ребра по актуальным ДУ и геометрии. */
    private void refresh(NetEdge e) {
        if (maps == null) {
            return;
        }
        List<Coordinate> cs = e.getCoords();
        ObstacleMap map = maps.map(e.getDiameter());
        boolean toOks = e.getTo().getKind() == NetNode.Kind.OKS_POINT;
        boolean fromOks = e.getFrom().getKind() == NetNode.Kind.OKS_POINT;
        java.util.Set<Object> exempt = Collections.emptySet();
        if (approach != null) {
            if (toOks) {
                exempt = approach.exemptIds(e.getTo().getId());
            } else if (fromOks) {
                exempt = approach.exemptIds(e.getFrom().getId());
            }
        }
        List<SegmentCheck> checks = e.getChecks();
        for (int i = 0; i + 1 < cs.size(); i++) {
            boolean last = i == cs.size() - 2;
            boolean first = i == 0;
            java.util.Set<Object> own = (last && toOks) || (first && fromOks) ? exempt : Collections.emptySet();
            SegmentCheck chk = map.check(cs.get(i), cs.get(i + 1), own, first && fromOks, last && toOks);
            if (i < checks.size()) {
                checks.set(i, chk);
            } else {
                checks.add(chk);
            }
        }
        while (checks.size() > Math.max(0, cs.size() - 1)) {
            checks.remove(checks.size() - 1);
        }
    }

    private static final double EPS = 1e-6;
    /**
     * Минимальная длина куска спецразбиения, м: границы зон ближе — сливаются. Кусок короче ширины трубы
     * не имеет физического смысла, а техузел через 30 см — мусор в выдаче (§2.1 «без мелких изломов»).
     */
    private static final double MIN_PIECE_M = 0.5;

    /** Интервал вдоль отрезка [t0, t1] (метры от начала) и накрывающее его препятствие. */
    private static final class Cover {
        final double t0;
        final double t1;
        final Obstacle obstacle;

        Cover(double t0, double t1, Obstacle obstacle) {
            this.t0 = t0;
            this.t1 = t1;
            this.obstacle = obstacle;
        }
    }

    public void split(NewNetwork net) {
        int created = 0;
        for (NetEdge e : new ArrayList<>(net.getEdges())) {
            refresh(e);
            boolean any = false;
            for (SegmentCheck c : e.getChecks()) {
                if (!c.getCrossings().isEmpty()) {
                    any = true;
                    break;
                }
            }
            if (any) {
                created += splitEdge(net, e);
            }
        }
        if (created > 0) {
            log.info("Спецучастки: создано {} технических узлов", created);
        }
    }

    private int splitEdge(NewNetwork net, NetEdge e) {
        List<Coordinate> cs = e.getCoords();
        // объекты, которые пересекает хотя бы один отрезок ребра: при косом пересечении зона отступа
        // такого объекта дотягивается и до соседнего отрезка, и спецучасток там продолжается
        Map<Object, Obstacle> crossedOnEdge = new LinkedHashMap<>();
        for (SegmentCheck c : e.getChecks()) {
            for (SegmentCheck.Crossing cr : c.getCrossings()) {
                crossedOnEdge.put(cr.getObstacle().getId(), cr.getObstacle());
            }
        }
        // кусочки: (coords, special?, K, types)
        List<List<Coordinate>> pieceCoords = new ArrayList<>();
        List<List<SegmentCheck>> pieceChecks = new ArrayList<>();
        List<Double> pieceK = new ArrayList<>();
        List<String> pieceTypes = new ArrayList<>();

        List<Coordinate> cur = new ArrayList<>();
        List<SegmentCheck> curChecks = new ArrayList<>();
        cur.add(cs.get(0));
        double curK = 1.0;
        String curTypes = null;
        String curIds = null;

        for (int i = 0; i + 1 < cs.size(); i++) {
            Coordinate a = cs.get(i);
            Coordinate b = cs.get(i + 1);
            SegmentCheck chk = e.getChecks().get(i);
            double len = a.distance(b);
            if (curIds != null && cur.size() > 1) {
                // спецпроход — один прямой участок (§4): на вершине ломаной кусок всегда закрывается
                pieceCoords.add(cur);
                pieceChecks.add(curChecks);
                pieceK.add(curK);
                pieceTypes.add(curTypes);
                Coordinate startVertex = cur.get(cur.size() - 1);
                cur = new ArrayList<>();
                cur.add(startVertex);
                curChecks = new ArrayList<>();
                curK = 1.0;
                curTypes = null;
                curIds = null;
            }
            if (chk.getCrossings().isEmpty() || len < EPS) {
                if (curIds != null && cur.size() > 1) {
                    // предыдущий кусок — спецучасток до вершины: обычный отрезок начинает новый кусок
                    pieceCoords.add(cur);
                    pieceChecks.add(curChecks);
                    pieceK.add(curK);
                    pieceTypes.add(curTypes);
                    Coordinate start = cur.get(cur.size() - 1);
                    cur = new ArrayList<>();
                    cur.add(start);
                    curChecks = new ArrayList<>();
                    curK = 1.0;
                    curTypes = null;
                    curIds = null;
                }
                cur.add(b);
                curChecks.add(chk);
                continue;
            }
            // интервалы спецзон вдоль отрезка
            List<Cover> covers = new ArrayList<>();
            TreeSet<Double> cuts = new TreeSet<>();
            cuts.add(0.0);
            cuts.add(len);
            LineString seg = GeomUtil.segment(a, b);
            for (SegmentCheck.Crossing cr : chk.getCrossings()) {
                // границы спецучастка отсчитываются вдоль трассы: для линейного объекта — ±margin от
                // точки пересечения, для полигона — margin за границей полигона с каждой стороны
                Obstacle o = cr.getObstacle();
                double margin = o.getRule().getSpecialMarginM();
                Geometry inter = seg.intersection(o.getSource());
                for (int g = 0; g < inter.getNumGeometries(); g++) {
                    Coordinate[] ic = inter.getGeometryN(g).getCoordinates();
                    if (ic.length == 0) {
                        continue;
                    }
                    double t0 = Double.MAX_VALUE;
                    double t1 = -1;
                    for (Coordinate c : ic) {
                        double t = c.distance(a);
                        t0 = Math.min(t0, t);
                        t1 = Math.max(t1, t);
                    }
                    double tCross0 = t0;
                    double tCross1 = t1;
                    t0 = clamp(t0 - margin, len);
                    t1 = clamp(t1 + margin, len);
                    // при пересечении под острым углом трасса и за ±margin остаётся ближе минимального
                    // расстояния — спецучасток продлеваем до выхода из зоны отступа (вне спецпрохода
                    // отступ обязателен, а внутри не проверяется)
                    // зона отступа — по точному ДУ ребра (проверки могли идти по карте группы ДУ)
                    double clearDist = o.getRule().minDistance(e.getDiameter().getDiameter())
                            + e.getDiameter().halfWidth() + o.getRule().getOwnWidthM() / 2.0;
                    Geometry inClear = seg.intersection(o.getSource().buffer(clearDist));
                    for (int g2 = 0; g2 < inClear.getNumGeometries(); g2++) {
                        Coordinate[] cc = inClear.getGeometryN(g2).getCoordinates();
                        if (cc.length < 2) {
                            continue;
                        }
                        double c0 = Double.MAX_VALUE;
                        double c1 = -1;
                        for (Coordinate c : cc) {
                            double t = c.distance(a);
                            c0 = Math.min(c0, t);
                            c1 = Math.max(c1, t);
                        }
                        if (c0 <= tCross1 + EPS && c1 >= tCross0 - EPS) {
                            t0 = Math.min(t0, clamp(c0, len));
                            t1 = Math.max(t1, clamp(c1, len));
                        } else {
                            // трасса снова заходит в зону отступа того же объекта, уже не пересекая его
                            // (идёт почти вдоль): это отдельный спецучасток — иначе базовый кусок между
                            // ними нарушал бы отступ (§3.1), а спецпроход обязан быть прямым (§4)
                            covers.add(new Cover(clamp(c0, len), clamp(c1, len), o));
                            cuts.add(clamp(c0, len));
                            cuts.add(clamp(c1, len));
                        }
                    }
                    covers.add(new Cover(t0, t1, o));
                    cuts.add(t0);
                    cuts.add(t1);
                }
            }
            carryOverFromNeighbours(e, i, a, b, len, seg, chk, covers, cuts, crossedOnEdge);
            // разрезы строим по границам зон (после корректировок), куски короче MIN_PIECE_M сливаем
            cuts = new TreeSet<>();
            cuts.add(0.0);
            cuts.add(len);
            for (Cover c : covers) {
                cuts.add(c.t0);
                cuts.add(c.t1);
            }
            List<Double> merged = new ArrayList<>();
            for (double t : cuts) {
                if (merged.isEmpty() || t - merged.get(merged.size() - 1) >= MIN_PIECE_M || t >= len - EPS) {
                    if (!merged.isEmpty() && t >= len - EPS && t - merged.get(merged.size() - 1) < MIN_PIECE_M) {
                        merged.remove(merged.size() - 1);
                    }
                    merged.add(t);
                }
            }
            Double[] ts = merged.toArray(new Double[0]);
            for (int k = 0; k + 1 < ts.length; k++) {
                double lo = ts[k];
                double hi = ts[k + 1];
                if (hi - lo < EPS) {
                    continue;
                }
                double mid = (lo + hi) / 2;
                double kSpec = 1.0;
                List<String> types = new ArrayList<>();
                TreeSet<String> ids = new TreeSet<>(); // набор пересекаемых объектов: смена набора — новый участок
                for (Cover c : covers) {
                    if (c.t0 - EPS <= mid && mid <= c.t1 + EPS) {
                        kSpec = Math.max(kSpec, c.obstacle.getRule().getKSpecial());
                        types.add(c.obstacle.getType());
                        ids.add(String.valueOf(c.obstacle.getId()));
                    }
                }
                boolean special = !types.isEmpty();
                String typesStr = special ? String.join(",", types) : null;
                String idsKey = special ? String.join(",", ids) : null;
                Coordinate end = hi >= len - EPS ? b : GeomUtil.along(a, b, hi);
                boolean sameKind = (idsKey == null && curIds == null)
                        || (idsKey != null && idsKey.equals(curIds) && kSpec == curK);
                if (!sameKind && cur.size() > 1) {
                    // закрываем текущий кусок на границе lo
                    pieceCoords.add(cur);
                    pieceChecks.add(curChecks);
                    pieceK.add(curK);
                    pieceTypes.add(curTypes);
                    Coordinate start = cur.get(cur.size() - 1);
                    cur = new ArrayList<>();
                    cur.add(start);
                    curChecks = new ArrayList<>();
                }
                curK = kSpec;
                curTypes = typesStr;
                curIds = idsKey;
                cur.add(end);
                curChecks.add(chk);
            }
        }
        pieceCoords.add(cur);
        pieceChecks.add(curChecks);
        pieceK.add(curK);
        pieceTypes.add(curTypes);

        if (pieceCoords.size() == 1) {
            e.setLayingMethod(pieceTypes.get(0) == null ? LayingMethod.BASE : LayingMethod.SPECIAL);
            e.setKSpecial(pieceK.get(0));
            e.setCrossedTypes(pieceTypes.get(0));
            return 0;
        }
        net.removeEdge(e);
        NetNode from = e.getFrom();
        int created = 0;
        for (int p = 0; p < pieceCoords.size(); p++) {
            boolean last = p == pieceCoords.size() - 1;
            NetNode to;
            if (last) {
                to = e.getTo();
            } else {
                List<Coordinate> pc = pieceCoords.get(p);
                to = net.addGeneratedNode(pc.get(pc.size() - 1), NetNode.Kind.TECH_NODE);
                created++;
            }
            NetEdge ne = net.addEdge(from, to, pieceCoords.get(p), pieceChecks.get(p));
            ne.setFlowTph(e.getFlowTph());
            ne.setDiameter(e.getDiameter());
            ne.setLayingMethod(pieceTypes.get(p) == null ? LayingMethod.BASE : LayingMethod.SPECIAL);
            ne.setKSpecial(pieceK.get(p));
            ne.setCrossedTypes(pieceTypes.get(p));
            ne.setCreatorPointId(e.getCreatorPointId());
            from = to;
        }
        return created;
    }

    /**
     * Спецучасток, начавшийся на соседнем отрезке, продолжается на этом. Косое пересечение дороги
     * оставляет трассу в зоне отступа ещё на десяток метров, а вершина ломаной (пусть даже излом в
     * доли градуса) обрывала бы спецучасток — и кусок за ней оказался бы в зоне отступа вне
     * спецпрохода, что запрещено §3.1. Поэтому для объекта, пересечённого соседним отрезком, берём
     * ту часть этого отрезка, что примыкает к общей вершине и лежит в зоне отступа. Излом на вершине
     * должен быть меньше {@link #STRAIGHT_TOL_DEG}: при настоящем повороте спецпроход обязан
     * закончиться (§4 — один прямой участок), и такую трассу отбракует проверка сети.
     */
    private void carryOverFromNeighbours(NetEdge e, int i, Coordinate a, Coordinate b, double len,
                                         LineString seg, SegmentCheck chk, List<Cover> covers,
                                         TreeSet<Double> cuts, Map<Object, Obstacle> crossedOnEdge) {
        if (crossedOnEdge.size() <= 1) {
            return;
        }
        Set<Object> here = new java.util.HashSet<>();
        for (SegmentCheck.Crossing cr : chk.getCrossings()) {
            here.add(cr.getObstacle().getId());
        }
        List<Coordinate> cs = e.getCoords();
        boolean straightAtA = i > 0
                && GeomUtil.turnAngleDeg(cs.get(i - 1), a, b) <= STRAIGHT_TOL_DEG;
        boolean straightAtB = i + 2 < cs.size()
                && GeomUtil.turnAngleDeg(a, b, cs.get(i + 2)) <= STRAIGHT_TOL_DEG;
        if (!straightAtA && !straightAtB) {
            return;
        }
        for (Obstacle o : crossedOnEdge.values()) {
            if (here.contains(o.getId())) {
                continue;
            }
            double clearDist = o.getRule().minDistance(e.getDiameter().getDiameter())
                    + e.getDiameter().halfWidth() + o.getRule().getOwnWidthM() / 2.0;
            Geometry inClear = seg.intersection(o.getSource().buffer(clearDist));
            for (int g = 0; g < inClear.getNumGeometries(); g++) {
                Coordinate[] cc = inClear.getGeometryN(g).getCoordinates();
                if (cc.length < 2) {
                    continue;
                }
                double c0 = Double.MAX_VALUE;
                double c1 = -1;
                for (Coordinate c : cc) {
                    double t = c.distance(a);
                    c0 = Math.min(c0, t);
                    c1 = Math.max(c1, t);
                }
                boolean touchesA = straightAtA && c0 <= EPS + MIN_PIECE_M;
                boolean touchesB = straightAtB && c1 >= len - EPS - MIN_PIECE_M;
                if (!touchesA && !touchesB) {
                    continue;
                }
                double t0 = clamp(touchesA ? 0 : c0, len);
                double t1 = clamp(touchesB ? len : c1, len);
                if (t1 - t0 < EPS) {
                    continue;
                }
                covers.add(new Cover(t0, t1, o));
                cuts.add(t0);
                cuts.add(t1);
            }
        }
    }

    /** Излом трассы, при котором спецучасток считается всё тем же прямым участком, градусы. */
    private static final double STRAIGHT_TOL_DEG = 1.0;

    private static double clamp(double t, double len) {
        return Math.max(0, Math.min(len, t));
    }

    /** Для тестов: типы спецучастков ребра. */
    static List<String> types(NetEdge e) {
        return e.getCrossedTypes() == null ? Collections.emptyList() : List.of(e.getCrossedTypes().split(","));
    }
}
