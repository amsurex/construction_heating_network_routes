package ru.lct.heat.routing;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.distance.DistanceOp;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.Obstacle;
import ru.lct.heat.geometry.ObstacleMap;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.Restriction;
import ru.lct.heat.model.ref.RestrictionRules;
import ru.lct.heat.model.ref.RuleProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Подход к точке подключения ОКС (Техприложение §2.2, FAQ п.3).
 *
 * Полигон ОКС, содержащий точку, — непроходим, но допускается один финальный прямой участок
 * от ближайшей к точке границы полигона до самой точки; отступ к собственному полигону на этот
 * участок (включая его часть в зоне отступа перед границей) не действует. Значит, трасса подходит
 * к точке по лучу T→B (B — ближайшая точка границы), а точка входа в граф E лежит на этом луче
 * сразу за зоной отступа. Остальные ограничения на участке E→T проверяются как обычно.
 */
@Slf4j
public final class ApproachPlanner {

    private static final double BOUNDARY_SAMPLE_M = 2.0;
    private static final double ON_BOUNDARY_TOL = 1e-6;
    private static final double ENTRY_DISTINCT_M = 1.0;

    /** Результат: точка входа E и прямой финальный участок E→T. */
    @Value
    public static class Approach {
        Coordinate entry;
        Coordinate target;
        /** Id собственного полигона ОКС (для исключения отступа), либо null если точка вне полигонов. */
        Object ownPolygonId;
        /** Все полигоны, отступ к которым не действует на финальном участке (здание + содержащие территории). */
        Set<Object> exempt;
        /** Почему вход не через ближайшую точку границы (null — это и есть ближайшая, либо она допустима). */
        String nearestRejected;

        public Set<Object> exemptIds() {
            return exempt == null ? Collections.emptySet() : exempt;
        }
    }

    /** Индекс всех запретных полигонов (ОКС, территории, парки…) — для поиска содержащих точку. */
    private final STRtree forbiddenIndex = new STRtree();
    private final Map<Object, Set<Object>> exemptByPointId = new HashMap<>();
    private final Map<Object, Geometry> ownGeometryByPointId = new HashMap<>();

    private final RuleProfile profile;

    public ApproachPlanner(InputModel input) {
        this(input, RuleProfile.heat());
    }

    public ApproachPlanner(InputModel input, RuleProfile profile) {
        this.profile = profile;
        for (Restriction r : input.getRestrictions()) {
            if (profile.forType(r.getRestrictionType()).isForbidden() && r.getGeometry().getDimension() == 2) {
                forbiddenIndex.insert(r.getGeometry().getEnvelopeInternal(),
                        new Own(r, PreparedGeometryFactory.prepare(r.getGeometry())));
            }
        }
        forbiddenIndex.build();
        for (ConnectionPoint cp : input.getConnectionPoints()) {
            Set<Object> ids = new java.util.LinkedHashSet<>();
            List<Restriction> covering = covering(cp);
            for (Restriction r : covering) {
                ids.add(r.getId());
            }
            exemptByPointId.put(cp.getId(), ids);
            if (!covering.isEmpty()) {
                ownGeometryByPointId.put(cp.getId(), covering.get(0).getGeometry());
            }
        }
    }

    /** Геометрия собственного полигона точки (здание, содержащее её), если есть. */
    public Optional<Geometry> ownPolygonGeometry(Object pointId) {
        return Optional.ofNullable(ownGeometryByPointId.get(pointId));
    }

    /**
     * Id запретных полигонов, содержащих точку подключения: собственное здание и, если оно стоит внутри
     * территории (школа в social_area, здание в парке), эта территория. Отступ к ним на финальный
     * участок не действует (§2.2, распространённое на содержащие территории).
     */
    public Set<Object> exemptIds(Object pointId) {
        return exemptByPointId.getOrDefault(pointId, Collections.emptySet());
    }

    /** Id собственного полигона ОКС для точки подключения по её id (совместимость). */
    public Optional<Object> ownPolygonId(Object pointId) {
        Set<Object> ids = exemptIds(pointId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.iterator().next());
    }

    @Value
    private static class Own {
        Restriction restriction;
        PreparedGeometry prepared;
    }

    /**
     * Запретные полигоны, содержащие точку (сначала ОКС, затем по возрастанию площади), плюс полигоны,
     * перекрывающие само здание точки (здание, частично наложенное на территорию/парк — шум данных).
     */
    @SuppressWarnings("unchecked")
    public List<Restriction> covering(ConnectionPoint cp) {
        Point p = cp.getGeometry();
        List<Restriction> out = new ArrayList<>();
        Restriction building = null;
        for (Own o : (List<Own>) forbiddenIndex.query(p.getEnvelopeInternal())) {
            if (o.prepared.covers(p)) {
                out.add(o.restriction);
                if (building == null && isOks(o.restriction)) {
                    building = o.restriction;
                }
            }
        }
        out.sort(Comparator.comparingInt((Restriction r) -> isOks(r) ? 0 : 1)
                .thenComparingDouble(r -> r.getGeometry().getArea()));
        if (building != null) {
            Geometry b = building.getGeometry();
            Envelope env = new Envelope(b.getEnvelopeInternal());
            env.expandBy(ADJACENT_TOL_M);
            for (Own o : (List<Own>) forbiddenIndex.query(env)) {
                if (out.contains(o.restriction) || o.restriction == building) {
                    continue;
                }
                Geometry g = o.restriction.getGeometry();
                // наложенный (дубль контура, здание поверх территории) или пристроенный корпус: отступ 5 м к нему
                // на финальном участке невыполним, исключаем; сквозь его интерьер финальный участок всё равно нельзя
                if (g.intersection(b).getArea() > 1.0 || (isOks(o.restriction) && g.distance(b) <= ADJACENT_TOL_M)) {
                    out.add(o.restriction); // после содержащих: собственный полигон — первый в списке
                }
            }
        }
        return out;
    }

    private boolean isOks(Restriction r) {
        return RestrictionRules.OKS.equals(profile.canonical(r.getRestrictionType()));
    }

    /** Собственный полигон точки (ОКС, содержащий её, либо наименьшая содержащая территория), если есть. */
    public Optional<Restriction> ownPolygon(ConnectionPoint cp) {
        List<Restriction> c = covering(cp);
        return c.isEmpty() ? Optional.empty() : Optional.of(c.get(0));
    }

    /** Первый допустимый подход (для тестов); в движке используется {@link #planAll}. */
    public Optional<Approach> plan(ConnectionPoint cp, ObstacleMap map, double vertexEps) {
        List<Approach> all = planAll(cp, map, vertexEps, 1);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Все геометрически допустимые подходы к точке (не более maxCount, отсортированы по длине
     * финального участка, точки входа различаются хотя бы на 1 м). Достижимость точки входа
     * снаружи здесь не проверяется — ближайшая граница может выходить в закрытый двор, поэтому
     * A* запускается сразу от всех точек входа и выбирает лучшую.
     */
    public List<Approach> planAll(ConnectionPoint cp, ObstacleMap map, double vertexEps, int maxCount) {
        Coordinate t = cp.getGeometry().getCoordinate();
        Optional<Restriction> own = ownPolygon(cp);
        List<Approach> out = new ArrayList<>();
        if (own.isEmpty()) {
            // точка вне зданий — входим прямо в неё; в спецзоне (например, внутри дороги) можно:
            // спецучасток может заканчиваться в точке ОКС; в зоне отступа запретного объекта — нельзя
            if (map.isPointFreeOfForbidden(t)) {
                out.add(new Approach(t, t, null, Collections.emptySet(), null));
            }
            return out;
        }
        Restriction poly = own.get();
        List<Restriction> coveringAll = covering(cp);
        Set<Object> exempt = new java.util.LinkedHashSet<>();
        Geometry clearanceUnion = null;
        for (Restriction r : coveringAll) {
            exempt.add(r.getId());
            Obstacle o = map.obstacle(r.getId());
            if (o != null) {
                clearanceUnion = clearanceUnion == null ? o.getClearance() : clearanceUnion.union(o.getClearance());
            }
        }
        Geometry polygon = complex(poly, coveringAll, cp.getGeometry());
        Geometry boundary = polygon.getBoundary();

        List<Coordinate> candidates = new ArrayList<>();
        candidates.add(DistanceOp.nearestPoints(boundary, cp.getGeometry())[0]);
        for (Coordinate c : Densifier.densify(boundary, BOUNDARY_SAMPLE_M).getCoordinates()) {
            candidates.add(c);
        }
        candidates.sort(Comparator.comparingDouble(t::distance));

        String nearestRejected = null;
        for (Coordinate b : candidates) {
            String rejected = null;
            Coordinate dir = direction(t, b, polygon);
            Coordinate e = dir == null ? null : exitPoint(t, dir, clearanceUnion, vertexEps);
            if (e == null) {
                // E — точка выхода луча T→B из зон отступа всех содержащих полигонов (+ зазор)
                rejected = "луч из точки через неё не выходит из зоны отступа";
            } else if (GeomUtil.segment(e, t).intersection(polygon).getLength() > t.distance(b) + 1e-3) {
                // финальный отрезок E→T внутри здания — только его часть B→T (сквозь другие крылья нельзя)
                rejected = "финальный участок прошёл бы сквозь другое крыло здания";
            } else if (map.pointStatus(e, Collections.emptySet()) == 1
                    && (e = pushOutOfSpecialZone(e, dir, map)) == null) {
                // точка входа в спецзоне (дорога вплотную к зданию): финальный участок — сразу прямой спецпроход
                // до точки ОКС (спецучасток может заканчиваться в точке ОКС), точка входа — за спецзоной
                rejected = "точка входа в спецзоне, выйти из неё вдоль луча не удалось";
            } else if (!map.isPointFree(e, Collections.emptySet())) {
                rejected = "точка входа в зоне отступа/спецзоне другого объекта";
            } else if (!map.check(e, t, exempt, true).isFree()) {
                rejected = "финальный участок нарушает отступ от другого объекта";
            } else if (!clearOfOtherParts(e, t, coveringAll, map)) {
                rejected = "финальный участок ближе отступа к другому корпусу";
            } else if (crossesExemptInterior(e, t, poly, coveringAll)) {
                rejected = "финальный участок прошёл бы сквозь соседний корпус";
            }
            if (rejected != null) {
                if (nearestRejected == null && out.isEmpty()) {
                    nearestRejected = rejected;
                }
                continue;
            }
            boolean duplicate = false;
            for (Approach a : out) {
                if (a.getEntry().distance(e) < ENTRY_DISTINCT_M) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                out.add(new Approach(e, t, poly.getId(), exempt, nearestRejected == null ? null
                        : "вход не через ближайшую границу: " + nearestRejected + " (§2.2, §3.1)"));
                if (out.size() >= maxCount) {
                    break;
                }
            }
        }
        if (out.isEmpty()) {
            log.warn("Точка {} : не найден допустимый подход к полигону {}", cp.getId(), poly.getId());
        }
        return out;
    }

    /** Сдвиг точки входа вдоль луча наружу, пока она не выйдет из всех спецзон (не дальше PUSH_OUT_MAX_M). */
    private static Coordinate pushOutOfSpecialZone(Coordinate e, Coordinate dir, ObstacleMap map) {
        double len = Math.hypot(dir.x, dir.y);
        double ux = dir.x / len;
        double uy = dir.y / len;
        for (double d = PUSH_OUT_STEP_M; d <= PUSH_OUT_MAX_M; d += PUSH_OUT_STEP_M) {
            Coordinate c = new Coordinate(e.x + ux * d, e.y + uy * d);
            int st = map.pointStatus(c, Collections.emptySet());
            if (st == 2) {
                return null;
            }
            if (st == 0) {
                return c;
            }
        }
        return null;
    }

    /**
     * Комплекс зданий вокруг точки: объединение всех полигонов ОКС, содержащих её. Контуры зданий в
     * реальных данных перекрываются (корпус внутри корпуса, дубль контура), и тогда «ближайшая граница
     * полигона» из §2.2 у каждого своя: выход через границу меньшего здания оставил бы финальный участок
     * на десятки метров внутри большего. Граница комплекса — единственная трактовка, при которой
     * финальный участок действительно выходит наружу; территории (парк, social_area) в комплекс не
     * входят — здание внутри парка выходит через свою стену.
     */
    private Geometry complex(Restriction own, List<Restriction> covering, Point target) {
        Geometry g = own.getGeometry();
        for (Restriction r : covering) {
            if (r == own || !isOks(r) || !r.getGeometry().covers(target)) {
                continue;
            }
            g = g.union(r.getGeometry());
        }
        return g;
    }

    /** Финальный участок не должен проходить внутри исключённых из отступа соседних полигонов-зданий. */
    private boolean crossesExemptInterior(Coordinate e, Coordinate t, Restriction own, List<Restriction> covering) {
        LineString seg = GeomUtil.segment(e, t);
        Point target = GeomUtil.point(t);
        for (Restriction r : covering) {
            if (r == own || !isOks(r) || r.getGeometry().covers(target)) {
                continue;
            }
            if (seg.intersection(r.getGeometry()).getLength() > 1e-3) {
                return true;
            }
        }
        return false;
    }

    /**
     * Исключение отступа действует только на ту часть MultiPolygon, что содержит точку: другие части того же
     * ограничения (соседние корпуса под одним id) — обычные запретные объекты, отступ к ним обязателен.
     */
    private static final double PUSH_OUT_STEP_M = 0.5;
    private static final double PUSH_OUT_MAX_M = 60.0;

    /** Здание ближе этого расстояния к зданию точки считается пристроенным корпусом, м. */
    static final double ADJACENT_TOL_M = 0.5;

    /** Часть MultiPolygon меньше этой площади не считается отдельным корпусом, м². */
    static final double OTHER_PART_MIN_AREA_M2 = 10.0;

    private static boolean clearOfOtherParts(Coordinate e, Coordinate t, List<Restriction> covering, ObstacleMap map) {
        LineString seg = GeomUtil.segment(e, t);
        Point target = GeomUtil.point(t);
        for (Restriction r : covering) {
            Geometry g = r.getGeometry();
            Obstacle o = map.obstacle(r.getId());
            if (g.getNumGeometries() <= 1 || o == null) {
                continue;
            }
            for (int i = 0; i < g.getNumGeometries(); i++) {
                Geometry part = g.getGeometryN(i);
                if (part.covers(target) || part.getArea() < OTHER_PART_MIN_AREA_M2) {
                    continue; // мелкие части (пристройки, шум оцифровки вдоль контура) — не отдельные корпуса
                }
                if (part.distance(seg) < o.getClearanceDistM() - 1e-9) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Первая точка, где луч из t в направлении dir выходит из зоны clearance; null если не выходит. */
    private static Coordinate exitPoint(Coordinate t, Coordinate dir, Geometry clearance, double eps) {
        double len = Math.max(50.0, clearance.getEnvelopeInternal().maxExtent() * 2);
        Coordinate far = new Coordinate(t.x + dir.x * len, t.y + dir.y * len);
        Geometry outside = GeomUtil.segment(t, far).difference(clearance);
        double best = Double.MAX_VALUE;
        for (Coordinate c : outside.getCoordinates()) {
            double along = (c.x - t.x) * dir.x + (c.y - t.y) * dir.y;
            if (along > 0 && along < best) {
                best = along;
            }
        }
        if (best == Double.MAX_VALUE) {
            return null;
        }
        best += eps;
        return new Coordinate(t.x + dir.x * best, t.y + dir.y * best);
    }

    /** Единичный вектор наружу из полигона в точке границы b по лучу t→b (или нормаль, если t == b). */
    private static Coordinate direction(Coordinate t, Coordinate b, Geometry polygon) {
        double dx = b.x - t.x;
        double dy = b.y - t.y;
        double len = Math.hypot(dx, dy);
        if (len > ON_BOUNDARY_TOL) {
            return new Coordinate(dx / len, dy / len);
        }
        // точка на границе: берём нормаль к ближайшему сегменту, направленную наружу
        Coordinate[] cs = polygon.getBoundary().getCoordinates();
        Coordinate best = null;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i + 1 < cs.length; i++) {
            double d = org.locationtech.jts.algorithm.Distance.pointToSegment(b, cs[i], cs[i + 1]);
            if (d < bestD) {
                bestD = d;
                double sx = cs[i + 1].x - cs[i].x;
                double sy = cs[i + 1].y - cs[i].y;
                double sl = Math.hypot(sx, sy);
                best = sl == 0 ? null : new Coordinate(-sy / sl, sx / sl);
            }
        }
        if (best == null) {
            return null;
        }
        Coordinate probe = new Coordinate(b.x + best.x * 0.5, b.y + best.y * 0.5);
        if (polygon.contains(GeomUtil.point(probe))) {
            return new Coordinate(-best.x, -best.y);
        }
        return best;
    }
}
