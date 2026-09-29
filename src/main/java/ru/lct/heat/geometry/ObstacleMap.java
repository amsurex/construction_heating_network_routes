package ru.lct.heat.geometry;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heat.model.ExistingPipe;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.Restriction;
import ru.lct.heat.model.ref.DiameterSpec;
import ru.lct.heat.model.ref.DiameterTable;
import ru.lct.heat.model.ref.RestrictionRule;
import ru.lct.heat.model.ref.RestrictionRules;
import ru.lct.heat.model.ref.RuleProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Карта препятствий для новой сети заданного ДУ: буферы ограничений и существующих трубопроводов,
 * пространственный индекс, вершины-кандидаты для графа видимости и проверка отрезков.
 *
 * Зависимость от ДУ: половина ширины габарита (таблица 1) и отступ от ОКС (5/7/9 м).
 */
@Slf4j
@Getter
public final class ObstacleMap {

    /** Допустимое отношение длины прохода внутри полигона к его поперечной ширине (√2 при 45° + запас). */
    private static final double ALONG_FACTOR = 1.6;

    private static final double TOUCH_TOL = 1e-6;

    private final DiameterSpec diameter;
    private final List<Obstacle> obstacles;
    private final STRtree index;
    /** Вершины-кандидаты для поворотов трассы, уже отфильтрованные (вне всех запретных зон). */
    private final List<Coordinate> vertices;
    private final Map<Object, Obstacle> byId = new HashMap<>();
    /** Кэш проверок вершина↔вершина графа видимости (не зависит от запроса), ключ = (min << 32) | max. */
    private final Map<Long, SegmentCheck> vertexCache = new ConcurrentHashMap<>();
    /** Статистика: всего проверок отрезков и попаданий в кэш вершин. */
    // LongAdder, а не AtomicLong: счётчик общий для всех потоков и инкрементируется на каждой проверке
    // отрезка — на 16 ядрах одна кэш-линия становится узким местом
    private final LongAdder checks = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder gridRejects = new LongAdder();
    /** Растровый предфильтр (лениво: нужен только картам, по которым ищут пути). */
    private volatile BlockGrid grid;
    /** Максимум клеток растра; при большей области клетка укрупняется. */
    private static final int MAX_GRID_CELLS = 4_000_000;

    private ObstacleMap(DiameterSpec diameter, List<Obstacle> obstacles) {
        this.diameter = diameter;
        this.obstacles = Collections.unmodifiableList(obstacles);
        this.index = new STRtree();
        for (Obstacle o : obstacles) {
            index.insert(o.getEnvelope(), o);
            byId.put(o.getId(), o);
        }
        index.build();
        List<Coordinate> vs = new ArrayList<>();
        for (Obstacle o : obstacles) {
            for (Coordinate c : o.getVertices()) {
                if (isPointFree(c, Collections.emptySet())) {
                    vs.add(c);
                }
            }
        }
        this.vertices = Collections.unmodifiableList(vs);
    }

    /**
     * Строит карту для ДУ {@code diameter}. Существующие трубопроводы включаются как спецпроходные
     * препятствия типа heat_network (Техприложение табл. 2, FAQ п.10) с собственным габаритом по их ДУ.
     */
    public static ObstacleMap build(InputModel input, DiameterSpec diameter, double vertexEps, double specialStepM) {
        return build(input, diameter, vertexEps, specialStepM, RuleProfile.heat());
    }

    public static ObstacleMap build(InputModel input, DiameterSpec diameter, double vertexEps, double specialStepM,
                                    RuleProfile profile) {
        long t0 = System.currentTimeMillis();
        double halfWidth = diameter.halfWidth();
        List<Obstacle> list = new ArrayList<>();
        java.util.Set<String> warned = new java.util.HashSet<>();
        for (Restriction r : input.getRestrictions()) {
            RestrictionRule rule = profile.forType(r.getRestrictionType());
            if (!profile.isKnown(r.getRestrictionType()) && warned.add(String.valueOf(r.getRestrictionType()))) {
                log.warn("Неизвестный restriction_type='{}' (например id={}), профиль '{}': {} с отступом {} м",
                        r.getRestrictionType(), r.getId(), profile.getName(),
                        rule.isForbidden() ? "запрет" : "спецпроход", rule.getMinDistanceM());
            }
            list.add(Obstacle.forDiameter(r.getId(), profile.canonical(r.getRestrictionType()), rule, r.getGeometry(),
                    diameter.getDiameter(), halfWidth, vertexEps, specialStepM));
        }
        RestrictionRule pipeRule = profile.forType(RestrictionRules.HEAT_NETWORK);
        for (ExistingPipe p : input.getPipes()) {
            DiameterSpec own = DiameterTable.byDiameter(p.getDiameter()).orElse(null);
            RestrictionRule rule = pipeRule.toBuilder()
                    .ownWidthM(own == null ? 0 : own.getWidthM())
                    .ownHeightM(own == null ? 0 : own.getHeightM())
                    .build();
            list.add(new Obstacle(p.getId(), RestrictionRules.HEAT_NETWORK, rule, p.getGeometry(),
                    halfWidth, vertexEps, specialStepM));
        }
        ObstacleMap map = new ObstacleMap(diameter, list);
        log.info("ObstacleMap DN{}: {} препятствий, {} вершин, {} мс", diameter.getDiameter(),
                list.size(), map.vertices.size(), System.currentTimeMillis() - t0);
        return map;
    }

    /** Проверка отрезка между вершинами графа i и j (индексы в {@link #getVertices()}) с общим кэшем. */
    public SegmentCheck checkVertices(int i, int j) {
        long key = ((long) Math.min(i, j) << 32) | Math.max(i, j);
        SegmentCheck chk = vertexCache.get(key);
        if (chk != null) {
            cacheHits.increment();
            return chk;
        }
        chk = check(vertices.get(i), vertices.get(j), Collections.emptySet());
        vertexCache.put(key, chk);
        return chk;
    }

    public String stats() {
        return "проверок " + checks.sum() + " (растром отклонено " + gridRejects.sum() + "), кэш вершин "
                + cacheHits.sum() + "/" + vertexCache.size();
    }

    private BlockGrid grid() {
        BlockGrid g = grid;
        if (g == null) {
            synchronized (this) {
                if (grid == null) {
                    Envelope env = new Envelope();
                    for (Obstacle o : obstacles) {
                        if (o.isForbidden()) {
                            env.expandToInclude(o.getEnvelope());
                        }
                    }
                    double cell = 2.0;
                    while (!env.isNull() && (env.getWidth() / cell) * (env.getHeight() / cell) > MAX_GRID_CELLS) {
                        cell *= 1.5;
                    }
                    long t0 = System.currentTimeMillis();
                    grid = new BlockGrid(obstacles, cell);
                    log.debug("BlockGrid DN{}: клетка {} м, {} клеток, {} мс", diameter.getDiameter(), cell,
                            grid.cells(), System.currentTimeMillis() - t0);
                }
                g = grid;
            }
        }
        return g;
    }

    /**
     * Отрезок идёт внутри полигона спецпрохода вдоль него: длина части внутри полигона больше поперечной
     * ширины полигона в этом месте более чем в ALONG_FACTOR раз (пересечение под 45° даёт √2).
     */
    private static boolean runsAlong(Obstacle o, LineString seg) {
        Geometry inside = seg.intersection(o.getSource());
        double insideLen = inside.getLength();
        if (insideLen < 1e-6) {
            return false;
        }
        Coordinate a = seg.getCoordinateN(0);
        Coordinate b = seg.getCoordinateN(1);
        double len = a.distance(b);
        double ux = (b.x - a.x) / len;
        double uy = (b.y - a.y) / len;
        double maxChord = 0;
        for (int g = 0; g < inside.getNumGeometries(); g++) {
            Geometry part = inside.getGeometryN(g);
            if (part.getLength() < 1e-6) {
                continue;
            }
            Coordinate mid = part.getCoordinates()[0];
            Coordinate end = part.getCoordinates()[part.getCoordinates().length - 1];
            Coordinate m = new Coordinate((mid.x + end.x) / 2, (mid.y + end.y) / 2);
            double reach = Math.max(50.0, insideLen);
            LineString perpendicular = GeomUtil.segment(new Coordinate(m.x - uy * reach, m.y + ux * reach),
                    new Coordinate(m.x + uy * reach, m.y - ux * reach));
            Geometry chord = perpendicular.intersection(o.getSource());
            for (int k = 0; k < chord.getNumGeometries(); k++) {
                Geometry c = chord.getGeometryN(k);
                if (c.distance(GeomUtil.point(m)) < 1e-6) {
                    maxChord = Math.max(maxChord, c.getLength());
                }
            }
        }
        return maxChord > 0 && insideLen > maxChord * ALONG_FACTOR + 0.5;
    }

    /** Точка лежит на существующем участке тепловой сети (точка врезки / существующая камера). */
    @SuppressWarnings("unchecked")
    public boolean onExistingNetwork(Coordinate c) {
        Point p = GeomUtil.point(c);
        for (Obstacle o : (List<Obstacle>) index.query(new Envelope(c))) {
            if (RestrictionRules.HEAT_NETWORK.equals(o.getType()) && o.getSource().distance(p) < TOUCH_TOL) {
                return true;
            }
        }
        return false;
    }

    /** Число выполненных проверок отрезков (детерминированная мера работы). */
    public long checkCount() {
        return checks.sum();
    }

    /** Препятствие по id ограничения / существующего участка. */
    public Obstacle obstacle(Object id) {
        return byId.get(id);
    }

    /** Точка вне зон отступа запретных препятствий (спецпроходные не учитываются — узел может лежать на трубе). */
    @SuppressWarnings("unchecked")
    public boolean isPointFreeOfForbidden(Coordinate c) {
        Point p = GeomUtil.point(c);
        for (Obstacle o : (List<Obstacle>) index.query(new Envelope(c))) {
            if (o.isForbidden() && o.getClearancePrep().intersects(p)) {
                return false;
            }
        }
        return true;
    }

    /** Статус точки для решётчатой трассировки: 0 — свободна, 1 — в зоне спецпрохода (только прямо), 2 — запрет. */
    @SuppressWarnings("unchecked")
    public int pointStatus(Coordinate c, Set<Object> exemptIds) {
        Point p = GeomUtil.point(c);
        int status = 0;
        for (Obstacle o : (List<Obstacle>) index.query(new Envelope(c))) {
            if (exemptIds.contains(o.getId())) {
                continue;
            }
            boolean inClearance = o.getClearancePrep().intersects(p);
            if (o.isForbidden()) {
                if (inClearance) {
                    return 2;
                }
            } else if (inClearance || (o.getSpecialZonePrep() != null && o.getSpecialZonePrep().intersects(p))) {
                status = 1;
            }
        }
        return status;
    }

    /** Точка не внутри запретной зоны и не внутри спецзоны (там нельзя поворачивать). */
    @SuppressWarnings("unchecked")
    public boolean isPointFree(Coordinate c, Set<Object> exemptIds) {
        Point p = GeomUtil.point(c);
        List<Obstacle> near = index.query(new Envelope(c));
        for (Obstacle o : near) {
            if (exemptIds.contains(o.getId())) {
                continue;
            }
            if (o.getClearancePrep().intersects(p)) {
                return false;
            }
            if (o.getSpecialZonePrep() != null && o.getSpecialZonePrep().intersects(p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Проверка прямого отрезка a→b. Правила (Техприложение §4, §3.1):
     * <ul>
     *   <li>запретные препятствия: отрезок не должен заходить в clearance (кроме exemptIds);</li>
     *   <li>спецпроходные: заходить в clearance можно только пересекая сам объект; при пересечении
     *       оба конца отрезка должны быть вне спецзоны (проход — один прямой участок), угол ≥ min;</li>
     *   <li>конец отрезка, лежащий на самом объекте (врезка в существующую сеть), — допустим.</li>
     * </ul>
     */
    public SegmentCheck check(Coordinate a, Coordinate b, Set<Object> exemptIds) {
        return check(a, b, exemptIds, false);
    }

    /**
     * @param bIsOksPoint конец b — точка подключения ОКС: спецучасток может заканчиваться в ней
     *                    (Техприложение §4), поэтому конец внутри спецзоны допустим
     */
    public SegmentCheck check(Coordinate a, Coordinate b, Set<Object> exemptIds, boolean bIsOksPoint) {
        return check(a, b, exemptIds, false, bIsOksPoint);
    }

    /** @param aIsOksPoint начало a — точка подключения ОКС (трасса начинается в самой точке) */
    @SuppressWarnings("unchecked")
    public SegmentCheck check(Coordinate a, Coordinate b, Set<Object> exemptIds, boolean aIsOksPoint,
                              boolean bIsOksPoint) {
        checks.increment();
        int hard = grid().blockedBy(a, b);
        if (hard >= 0 && !exemptIds.contains(obstacles.get(hard).getId())) {
            gridRejects.increment();
            return SegmentCheck.blocked(obstacles.get(hard), "заход в зону отступа " + obstacles.get(hard).getType());
        }
        LineString seg = GeomUtil.segment(a, b);
        List<Obstacle> near = index.query(seg.getEnvelopeInternal());
        List<SegmentCheck.Crossing> crossings = null;
        for (Obstacle o : near) {
            if (exemptIds.contains(o.getId())) {
                continue;
            }
            if (!o.getClearancePrep().intersects(seg)) {
                continue;
            }
            if (o.isForbidden()) {
                return SegmentCheck.blocked(o, "заход в зону отступа " + o.getType());
            }
            // спецпроходное препятствие
            if (o.getSource().getDimension() == 1) {
                // начало/конец на линейном объекте (существующая сеть) — врезка, не пересечение
                boolean aOn = o.getSource().distance(GeomUtil.point(a)) < TOUCH_TOL;
                boolean bOn = o.getSource().distance(GeomUtil.point(b)) < TOUCH_TOL;
                if (aOn || bOn) {
                    // отход от врезки должен сразу выйти из зоны отступа, а не идти вдоль существующей трубы
                    if (seg.intersection(o.getClearance()).getLength() > 2 * o.getClearanceDistM() + TOUCH_TOL) {
                        return SegmentCheck.blocked(o, "отход от врезки вдоль " + o.getType() + " ближе минимального расстояния");
                    }
                    continue;
                }
            }
            if (!o.getSourcePrep().intersects(seg)) {
                return SegmentCheck.blocked(o, "проход вдоль " + o.getType() + " ближе минимального расстояния");
            }
            Geometry zone = o.getSpecialZone();
            // конец отрезка внутри спецзоны допустим только если это врезка в существующую сеть
            // (спецучасток может заканчиваться в камере), иначе — поворот внутри спецзоны
            if ((o.getSpecialZonePrep().intersects(GeomUtil.point(a)) && !aIsOksPoint && !onExistingNetwork(a))
                    || (o.getSpecialZonePrep().intersects(GeomUtil.point(b)) && !bIsOksPoint && !onExistingNetwork(b))) {
                return SegmentCheck.blocked(o, "поворот внутри спецзоны " + o.getType());
            }
            double minAngle = o.getRule().getMinCrossingAngleDeg();
            if (minAngle > 0 && o.minCrossingAngleDeg(a, b) < minAngle - 1e-6) {
                return SegmentCheck.blocked(o, "угол пересечения " + o.getType() + " меньше " + minAngle + "°");
            }
            if (o.getSource().getDimension() == 2 && runsAlong(o, seg)) {
                // проход внутри полигона вдоль него (дорога/трамвай по длине) — не пересечение
                return SegmentCheck.blocked(o, "проход вдоль " + o.getType() + " внутри полигона");
            }
            double zoneLen = seg.intersection(zone).getLength();
            if (crossings == null) {
                crossings = new ArrayList<>(2);
            }
            crossings.add(new SegmentCheck.Crossing(o, zoneLen));
        }
        return SegmentCheck.ok(crossings == null ? Collections.emptyList() : crossings);
    }
}
