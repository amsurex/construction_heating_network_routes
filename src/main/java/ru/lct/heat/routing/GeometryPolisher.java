package ru.lct.heat.routing;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.ObstacleMap;
import ru.lct.heat.geometry.SegmentCheck;
import ru.lct.heat.model.ref.CostConstants;
import ru.lct.heat.model.ref.DiameterTable;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Полировка геометрии готовой сети (описание кейса §2.3, §8 «качество трассировки»):
 * <ol>
 *   <li>удаление лишних вершин — если отрезок между соседями вершины допустим, вершина убирается
 *       (меньше поворотов, нет микроизломов);</li>
 *   <li>приведение углов поворота к 45°/90° — вершина сдвигается вдоль входящего (или исходящего)
 *       направления так, чтобы угол стал стандартным, если оба новых отрезка допустимы и трасса
 *       удлиняется не более чем на {@code maxExtraM}.</li>
 * </ol>
 * Точка входа в здание (последняя вершина перед точкой ОКС) не двигается — финальный участок
 * фиксирован правилом §2.2. Все проверки — по карте итогового ДУ ребра, плюс остальные рёбра сети.
 */
@Slf4j
public final class GeometryPolisher {

    private static final double[] SNAP_ANGLES = {45, 90};
    private static final double SNAP_TOL_DEG = 12.0;
    private static final double MIN_SEGMENT_M = 1.0;
    /** Сжатие вершины поворота: минимальный сдвиг (м), итераций бинарного поиска, проходов по ребру. */
    private static final double SHRINK_MIN_M = 0.05;
    private static final int SHRINK_BISECTIONS = 14;
    private static final int SHRINK_PASSES = 3;

    private final MapCache maps;
    private final ApproachPlanner approach;
    private final RoutingParams params;

    public GeometryPolisher(MapCache maps, ApproachPlanner approach, RoutingParams params) {
        this.maps = maps;
        this.approach = approach;
        this.params = params;
    }

    public void polish(NewNetwork net) {
        int removed = 0;
        int snapped = 0;
        double shrunk = 0;
        for (NetEdge e : net.getEdges()) {
            Others others = new Others(net, e);
            removed += removeRedundantVertices(e, others);
            shrunk += shrinkVertices(e, others);
            removed += removeRedundantVertices(e, others);
            snapped += snapAngles(e, others);
        }
        if (removed + snapped > 0 || shrunk > 0.5) {
            log.info("Полировка геометрии: удалено вершин {}, сжатие углов {} м, углов приведено к 45°/90°: {}",
                    removed, Math.round(shrunk), snapped);
        }
    }

    /**
     * Сжатие трассы в точках поворота: A* ставит вершины в узлах графа видимости, отодвинутых от зоны
     * отступа на запас (vertex-clearance-eps + допуск упрощения контура). Здесь вершина подтягивается к
     * прямой между соседями настолько, насколько позволяют точные проверки, — трасса прижимается к
     * границе зоны и становится короче. Проверяем с запасом на одну номенклатуру ДУ: после полировки
     * расход пересчитывается, ДУ (и габарит) может вырасти.
     *
     * @return суммарное сокращение длины ребра, м
     */
    private double shrinkVertices(NetEdge e, Others others) {
        ObstacleMap map = maps.map(e.getDiameter());
        ObstacleMap safe = maps.map(DiameterTable.next(e.getDiameter()).orElse(e.getDiameter()));
        List<Coordinate> cs = e.getCoords();
        double gain = 0;
        for (int pass = 0; pass < SHRINK_PASSES; pass++) {
            double before = gain;
            for (int i = 1; i < cs.size() - fixedTailVertices(e); i++) {
                Coordinate a = cs.get(i - 1);
                Coordinate v = cs.get(i);
                Coordinate c = cs.get(i + 1);
                Coordinate target = new LineSegment(a, c).closestPoint(v);
                if (v.distance(target) < SHRINK_MIN_M) {
                    continue;
                }
                double lo = 0;
                double hi = 1;
                Coordinate best = null;
                for (int it = 0; it < SHRINK_BISECTIONS; it++) {
                    double mid = (lo + hi) / 2;
                    Coordinate cand = new Coordinate(v.x + (target.x - v.x) * mid, v.y + (target.y - v.y) * mid);
                    if (cand.distance(a) < MIN_SEGMENT_M || cand.distance(c) < MIN_SEGMENT_M
                            || !acceptable(safe, e, i, a, cand, c, others)
                            || !acceptable(map, e, i, a, cand, c, others)) {
                        hi = mid;
                    } else {
                        lo = mid;
                        best = cand;
                    }
                }
                if (best == null) {
                    continue;
                }
                double base = a.distance(v) + v.distance(c);
                double after = a.distance(best) + best.distance(c);
                if (after > base - 0.01) {
                    continue;
                }
                cs.set(i, best);
                e.getChecks().set(i - 1, map.check(a, best, exemptFor(e, i - 1)));
                e.getChecks().set(i, map.check(best, c, exemptFor(e, i)));
                gain += base - after;
            }
            if (gain - before < 0.01) {
                break;
            }
        }
        return gain;
    }

    /** Остальные рёбра сети как препятствия (новые участки не пересекаются, §2.1). */
    private final class Others {
        final STRtree index = new STRtree();
        final double clearance;

        Others(NewNetwork net, NetEdge self) {
            clearance = params.getNewPipeClearanceM() + self.getDiameter().getWidthM();
            for (NetEdge o : net.getEdges()) {
                if (o == self) {
                    continue;
                }
                LineString l = GeomUtil.line(o.getCoords());
                Geometry buf = l.buffer(clearance);
                index.insert(buf.getEnvelopeInternal(), new Object[]{l, PreparedGeometryFactory.prepare(buf)});
            }
            index.build();
        }

        @SuppressWarnings("unchecked")
        boolean blocked(Coordinate a, Coordinate b) {
            LineString seg = GeomUtil.segment(a, b);
            for (Object[] o : (List<Object[]>) index.query(seg.getEnvelopeInternal())) {
                LineString line = (LineString) o[0];
                PreparedGeometry buf = (PreparedGeometry) o[1];
                if (!buf.intersects(seg)) {
                    continue;
                }
                Geometry inter = line.intersection(seg);
                boolean junction = inter.getDimension() == 0 && inter.getNumGeometries() == 1
                        && (inter.getCoordinate().distance(a) < 1e-6 || inter.getCoordinate().distance(b) < 1e-6);
                if (!junction || seg.intersection(buf.getGeometry()).getLength() > clearance * 4) {
                    return true;
                }
            }
            return false;
        }
    }

    private int fixedTailVertices(NetEdge e) {
        // у ребра-листа с подходом к зданию последние две вершины (E и T) фиксированы
        return e.getTo().getKind() == NetNode.Kind.OKS_POINT
                && approach.ownPolygonId(e.getTo().getId()).isPresent() ? 2 : 1;
    }

    private Set<Object> exemptFor(NetEdge e, int segIdx) {
        boolean last = segIdx == e.getCoords().size() - 2;
        if (last && e.getTo().getKind() == NetNode.Kind.OKS_POINT) {
            return approach.exemptIds(e.getTo().getId());
        }
        return Collections.emptySet();
    }

    private int removeRedundantVertices(NetEdge e, Others others) {
        ObstacleMap map = maps.map(e.getDiameter());
        List<Coordinate> cs = e.getCoords();
        List<SegmentCheck> ch = e.getChecks();
        int removed = 0;
        int i = 1;
        while (i < cs.size() - fixedTailVertices(e)) {
            Coordinate a = cs.get(i - 1);
            Coordinate c = cs.get(i + 1);
            SegmentCheck chk = map.check(a, c, exemptFor(e, i));
            boolean turnOk = i - 2 < 0 || GeomUtil.turnAngleDeg(cs.get(i - 2), a, c) <= CostConstants.MAX_TURN_ANGLE_DEG
                    + PathFinder.ANGLE_TOL_DEG;
            boolean turnOk2 = i + 2 >= cs.size() || GeomUtil.turnAngleDeg(a, c, cs.get(i + 2))
                    <= CostConstants.MAX_TURN_ANGLE_DEG + PathFinder.ANGLE_TOL_DEG;
            if (chk.isFree() && turnOk && turnOk2 && !others.blocked(a, c)
                    && sameCrossings(chk, ch.get(i - 1), ch.get(i))) {
                cs.remove(i);
                ch.remove(i);
                ch.set(i - 1, chk);
                removed++;
            } else {
                i++;
            }
        }
        return removed;
    }

    /** Не сливаем отрезки, если при этом меняется набор пересекаемых спецзон (спецпроход — прямой участок). */
    private static boolean sameCrossings(SegmentCheck merged, SegmentCheck s1, SegmentCheck s2) {
        return merged.getCrossings().size() == s1.getCrossings().size() + s2.getCrossings().size();
    }

    private int snapAngles(NetEdge e, Others others) {
        ObstacleMap map = maps.map(e.getDiameter());
        List<Coordinate> cs = e.getCoords();
        List<SegmentCheck> ch = e.getChecks();
        int snapped = 0;
        for (int i = 1; i < cs.size() - fixedTailVertices(e); i++) {
            Coordinate a = cs.get(i - 1);
            Coordinate b = cs.get(i);
            Coordinate c = cs.get(i + 1);
            double turn = GeomUtil.turnAngleDeg(a, b, c);
            double target = nearestSnap(turn);
            if (target < 0 || Math.abs(turn - target) < 0.01) {
                continue;
            }
            Coordinate best = null;
            double bestExtra = params.getSnapMaxExtraM();
            // вариант 1: сохраняем направление a→b, сдвигаем b вдоль него; вариант 2: сохраняем b→c
            for (Coordinate cand : new Coordinate[]{slideAlong(a, b, c, target), slideAlong(c, b, a, target)}) {
                if (cand == null || cand.distance(a) < MIN_SEGMENT_M || cand.distance(c) < MIN_SEGMENT_M) {
                    continue;
                }
                double extra = a.distance(cand) + cand.distance(c) - a.distance(b) - b.distance(c);
                if (extra > bestExtra) {
                    continue;
                }
                if (!acceptable(map, e, i, a, cand, c, others)) {
                    continue;
                }
                best = cand;
                bestExtra = extra;
            }
            if (best != null) {
                cs.set(i, best);
                ch.set(i - 1, map.check(a, best, exemptFor(e, i - 1)));
                ch.set(i, map.check(best, c, exemptFor(e, i)));
                snapped++;
            }
        }
        return snapped;
    }

    private static double nearestSnap(double turn) {
        for (double s : SNAP_ANGLES) {
            if (Math.abs(turn - s) <= SNAP_TOL_DEG) {
                return s;
            }
        }
        return -1;
    }

    /**
     * Точка b' на луче a→b такая, что поворот a→b'→c равен target (градусы). Решается перебором
     * вдоль луча: угол монотонно меняется при движении b' по лучу.
     */
    static Coordinate slideAlong(Coordinate a, Coordinate b, Coordinate c, double target) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double len = Math.hypot(dx, dy);
        if (len < 1e-9) {
            return null;
        }
        dx /= len;
        dy /= len;
        // бинарный поиск t в [0.2·len, 3·len]: f(t) = turn(a, a+t·d, c) − target
        double lo = 0.2 * len;
        double hi = 3.0 * len;
        double fLo = turnAt(a, dx, dy, lo, c) - target;
        double fHi = turnAt(a, dx, dy, hi, c) - target;
        if (fLo * fHi > 0) {
            return null;
        }
        for (int k = 0; k < 40; k++) {
            double mid = (lo + hi) / 2;
            double fMid = turnAt(a, dx, dy, mid, c) - target;
            if (fMid * fLo <= 0) {
                hi = mid;
                fHi = fMid;
            } else {
                lo = mid;
                fLo = fMid;
            }
        }
        double t = (lo + hi) / 2;
        return new Coordinate(a.x + dx * t, a.y + dy * t);
    }

    private static double turnAt(Coordinate a, double dx, double dy, double t, Coordinate c) {
        Coordinate b = new Coordinate(a.x + dx * t, a.y + dy * t);
        return GeomUtil.turnAngleDeg(a, b, c);
    }

    private boolean acceptable(ObstacleMap map, NetEdge e, int i, Coordinate a, Coordinate b, Coordinate c,
                               Others others) {
        List<Coordinate> cs = e.getCoords();
        if (!map.check(a, b, exemptFor(e, i - 1)).isFree() || !map.check(b, c, exemptFor(e, i)).isFree()) {
            return false;
        }
        if (!map.isPointFree(b, Collections.emptySet())) {
            return false;
        }
        if (others.blocked(a, b) || others.blocked(b, c)) {
            return false;
        }
        double tol = CostConstants.MAX_TURN_ANGLE_DEG + PathFinder.ANGLE_TOL_DEG;
        if (i - 2 >= 0 && GeomUtil.turnAngleDeg(cs.get(i - 2), a, b) > tol) {
            return false;
        }
        if (i + 2 < cs.size() && GeomUtil.turnAngleDeg(b, c, cs.get(i + 2)) > tol) {
            return false;
        }
        // набор спецпересечений не должен измениться (иначе — другая топология спецучастков)
        SegmentCheck old1 = e.getChecks().get(i - 1);
        SegmentCheck old2 = e.getChecks().get(i);
        SegmentCheck new1 = map.check(a, b, exemptFor(e, i - 1));
        SegmentCheck new2 = map.check(b, c, exemptFor(e, i));
        return new1.getCrossings().size() == old1.getCrossings().size()
                && new2.getCrossings().size() == old2.getCrossings().size();
    }
}
