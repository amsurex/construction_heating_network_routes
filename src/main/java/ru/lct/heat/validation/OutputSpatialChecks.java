package ru.lct.heat.validation;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.model.*;
import ru.lct.heat.model.out.*;
import ru.lct.heat.model.ref.*;
import ru.lct.heat.routing.SolveOptions;
import java.util.*;
import static ru.lct.heat.validation.InputValidator.key;
import static ru.lct.heat.validation.ValidationChecks.GEOMETRY_EPS;

/** §2.1–2.2, §3–5: независимые геометрические проверки, соседство через STRtree. */
final class OutputSpatialChecks {
    /** Допуск сравнения расстояний до границы при проверке «через ближайшую границу» (§2.2), м. */
    private static final double NEAREST_TOL_M = 0.05;
    /** Минимальный кусок спецразбиения, м (ядро сливает более короткие). */
    private static final double MIN_SPECIAL_PIECE_M = 0.5;
    /** Сколько кусков цепочки спецпрохода просматривать в поисках самого пересечения. */
    private static final int SPECIAL_CHAIN_MAX = 32;
    private final InputModel input;
    private final Variant variant;
    private final SolveOptions.Mode mode;
    private final RuleProfile profile;
    private final ValidationChecks checks;
    private final OutputGraphChecks graph;
    private final STRtree restrictions = new STRtree();
    private final Map<Object, ConnectionPoint> points = new HashMap<>();
    private double searchRadius;

    OutputSpatialChecks(InputModel input, Variant variant, SolveOptions options,
                        ValidationChecks checks, OutputGraphChecks graph) {
        this.input = input; this.variant = variant; this.mode = options.getMode();
        this.profile = RuleProfiles.load(options.getResource()); this.checks = checks; this.graph = graph;
        input.getConnectionPoints().forEach(p -> points.put(key(p.getId()), p));
        for (Restriction r : input.getRestrictions()) {
            if (!options.getExcludeRestrictionIds().contains(String.valueOf(r.getId()))) { add(r); }
        }
        for (ExistingPipe p : input.getPipes()) { add(new Restriction(p.getId(), p.getGeometry(), RestrictionRules.HEAT_NETWORK)); }
        restrictions.build();
    }

    private void add(Restriction r) {
        restrictions.insert(r.getGeometry().getEnvelopeInternal(), r);
        RestrictionRule rule = profile.forType(r.getRestrictionType());
        DiameterSpec largest = DiameterTable.rows().get(DiameterTable.rows().size() - 1);
        searchRadius = Math.max(searchRadius, Math.max(rule.getSpecialMarginM(), 0));
        searchRadius = Math.max(searchRadius, rule.minDistance(largest.getDiameter())
                + largest.halfWidth() + largest.halfWidth() + rule.getOwnWidthM() / 2);
    }

    void check() {
        STRtree pipes = new STRtree();
        Map<NewPipe, Integer> order = new IdentityHashMap<>(); int i = 0;
        for (NewPipe p : variant.getPipes()) { pipes.insert(p.getGeometry().getEnvelopeInternal(), p); order.put(p, i++); }
        pipes.build();
        for (NewPipe p : variant.getPipes()) {
            for (Object item : pipes.query(p.getGeometry().getEnvelopeInternal())) {
                NewPipe other = (NewPipe) item;
                if (order.get(other) <= order.get(p)) { continue; }
                Geometry intersection = p.getGeometry().intersection(other.getGeometry());
                if (intersection.isEmpty()) { continue; }
                boolean allowed = intersection.getDimension() == 0;
                for (Coordinate c : intersection.getCoordinates()) {
                    allowed &= commonNodeAt(p, other, c);
                }
                checks.require(allowed, p.getId(), "§2.1", "Пересечение/наложение вне общего узла с " + other.getId());
            }
            pipe(p);
        }
        depthContinuity();
    }

    private boolean commonNodeAt(NewPipe a, NewPipe b, Coordinate c) {
        for (Object id : List.of(a.getStartNodeId(), a.getEndNodeId())) {
            if (key(id).equals(key(b.getStartNodeId())) || key(id).equals(key(b.getEndNodeId()))) {
                Point node = graph.nodes.get(key(id));
                if (node != null && node.getCoordinate().distance(c) <= GEOMETRY_EPS) { return true; }
            }
        }
        return false;
    }

    private void pipe(NewPipe p) {
        LineString line = p.getGeometry();
        checks.require(line.isSimple() && line.getLength() > GEOMETRY_EPS, p.getId(), "§2.1", "Самопересечение/нулевая длина");
        checks.close(p.getLength(), line.getLength(), 0.0051 + line.getNumPoints() * 0.0002, p.getId(), "§7", "length");
        Optional<DiameterSpec> row = DiameterTable.byDiameter(p.getDiameter());
        checks.require(row.isPresent(), p.getId(), "§3", "ДУ отсутствует в таблице");
        if (row.isEmpty()) { return; }
        DiameterSpec dn = row.get();
        for (int i = 1; i < line.getNumPoints() - 1; i++) {
            Coordinate a = line.getCoordinateN(i - 1), b = line.getCoordinateN(i), c = line.getCoordinateN(i + 1);
            double angle = angle(b.x - a.x, b.y - a.y, c.x - b.x, c.y - b.y);
            checks.require(angle <= CostConstants.MAX_TURN_ANGLE_DEG + 0.01, p.getId(), "§2.1", "Поворот более 90°");
        }
        if (p.getLayingMethod() == LayingMethod.SPECIAL) {
            checks.require(straight(line), p.getId(), "§4", "Спецпроход должен быть прямым");
        }
        double kd = depth(p);
        Envelope envelope = new Envelope(line.getEnvelopeInternal()); envelope.expandBy(searchRadius);
        List<Interval> intervals = new ArrayList<>();
        for (Object item : restrictions.query(envelope)) {
            Restriction r = (Restriction) item;
            RestrictionRule rule = profile.forType(r.getRestrictionType());
            if (rule.isForbidden()) { forbidden(p, r, dn, rule); }
            else if (!tieIn(p, r)) { special(p, r, dn, rule, intervals); }
        }
        double ks = factors(p, intervals);
        checks.close(p.getCost(), line.getLength() * dn.getCostPerMeter() * kd * ks,
                Math.max(1, dn.getCostPerMeter() * line.getNumPoints() * 0.0002 * kd * ks), p.getId(), "§6", "cost");
        if (Double.isFinite(p.getKSpecial())) { checks.close(p.getKSpecial(), ks, 1e-8, p.getId(), "§4", "k_special"); }
        if (Double.isFinite(p.getKDepth())) { checks.close(p.getKDepth(), kd, 1e-8, p.getId(), "§5", "k_depth"); }
    }

    private void forbidden(NewPipe p, Restriction restriction, DiameterSpec dn, RestrictionRule rule) {
        Geometry line = p.getGeometry();
        if (restriction.getGeometry().getDimension() == 2) {
            // финальный прямой участок может быть разбит техузлами (спецпроход дороги перед фасадом):
            // участок, лежащий на прямой цепочке к точке ОКС, целиком под исключением её полигонов
            for (Object id : List.of(p.getStartNodeId(), p.getEndNodeId())) {
                if (points.containsKey(key(id))) { continue; }
                ConnectionPoint chainTarget = straightChainTarget(p, id);
                if (chainTarget != null && (restriction.getGeometry().covers(chainTarget.getGeometry())
                        || overlapsBuildingOf(restriction, chainTarget))) { return; }
            }
            for (Object id : List.of(p.getStartNodeId(), p.getEndNodeId())) {
                ConnectionPoint target = points.get(key(id));
                if (target == null) { continue; }
                boolean covers = restriction.getGeometry().covers(target.getGeometry());
                if (!covers && !overlapsBuildingOf(restriction, target)) { continue; }
                LineString oriented = key(id).equals(key(p.getStartNodeId()))
                        ? (LineString) p.getGeometry().reverse() : p.getGeometry();
                int n = oriented.getNumPoints();
                LineString last = Crs.UTM.createLineString(new Coordinate[]{oriented.getCoordinateN(n - 2), oriented.getCoordinateN(n - 1)});
                Geometry crossing = last.intersection(restriction.getGeometry().getBoundary());
                if (covers && RestrictionRules.OKS.equals(profile.canonical(restriction.getRestrictionType()))
                        && restriction == complexOwner(target)) {
                    // §2.2 проверяется по границе комплекса перекрывающихся зданий, а не каждого контура
                    // по отдельности: у вложенных контуров «ближайшая граница» разная, наружу выводит только
                    // граница объединения (см. ApproachPlanner#complex)
                    Geometry own = complexOf(target);
                    crossing = last.intersection(own.getBoundary());
                    double nearest = own.getBoundary().distance(target.getGeometry());
                    boolean nearestApproach = !crossing.isEmpty();
                    for (Coordinate c : crossing.getCoordinates()) {
                        // допуск: у точки на равном удалении от двух стен ближайших точек границы несколько
                        nearestApproach &= Math.abs(c.distance(target.getGeometry().getCoordinate()) - nearest) <= NEAREST_TOL_M;
                    }
                    if (crossing.isEmpty() && own.covers(last)) {
                        // участок целиком внутри здания (спецпроход разбил финальный участок техузлом):
                        // границу пересекает соседний кусок цепочки, там же и проверяется §2.2
                        nearestApproach = true;
                    }
                    if (!nearestApproach) {
                        String message = "Финальный подход не через ближайшую границу ОКС " + restriction.getId()
                                + "; минимальное расстояние=" + nearest + ", пересечение=" + crossing;
                        if (nearestBoundaryBlocked(restriction, target, dn, rule)) {
                            checks.warn(p.getId(), "§2.2", message + "; ближайшая граница недостижима");
                        } else if (p.getExtra().get("approach") != null) {
                            // Разъяснение оргов п. 15: ближайшую допустимую точку границы рассматриваем
                            // в первую очередь, но если через неё нет допустимого и рационального
                            // маршрута — допустима другая точка внешней границы. Ядро объясняет выбор
                            // в properties.approach; предупреждение, а не ошибка.
                            checks.warn(p.getId(), "§2.2", message + "; " + p.getExtra().get("approach")
                                    + "; допустимо по п. 15 разъяснений (через ближайшую границу маршрут"
                                    + " нерационален)");
                        } else {
                            checks.require(false, p.getId(), "§2.2", message);
                        }
                    }
                }
                if (n == 2) { return; }
                line = Crs.UTM.createLineString(Arrays.copyOf(oriented.getCoordinates(), n - 1));
            }
        }
        double needed = rule.minDistance(p.getDiameter()) + dn.halfWidth() + rule.getOwnWidthM() / 2;
        checks.require(line.distance(restriction.getGeometry()) >= needed - GEOMETRY_EPS, p.getId(), "§3.1/§4",
                "Нарушен отступ от " + restriction.getRestrictionType() + " " + restriction.getId());
    }

    /**
     * Точка ОКС, к которой участок ведёт по прямой через технические узлы (без поворотов), начиная с узла
     * nodeId; null — если цепочка поворачивает, ветвится или кончается не в точке ОКС.
     */
    private ConnectionPoint straightChainTarget(NewPipe p, Object nodeId) {
        NewPipe current = p;
        Object node = nodeId;
        Coordinate prev = key(node).equals(key(p.getEndNodeId()))
                ? p.getGeometry().getCoordinateN(p.getGeometry().getNumPoints() - 2) : p.getGeometry().getCoordinateN(1);
        for (int guard = 0; guard < 64; guard++) {
            Point at = graph.nodes.get(key(node));
            List<NewPipe> next = new ArrayList<>(graph.adjacent.getOrDefault(key(node), List.of()));
            next.remove(current);
            if (at == null || next.size() != 1) { return null; }
            NewPipe n = next.get(0);
            boolean forward = key(n.getStartNodeId()).equals(key(node));
            LineString g = n.getGeometry();
            Coordinate after = forward ? g.getCoordinateN(1) : g.getCoordinateN(g.getNumPoints() - 2);
            Coordinate here = at.getCoordinate();
            double ax = here.x - prev.x, ay = here.y - prev.y, bx = after.x - here.x, by = after.y - here.y;
            if (Math.abs(Math.toDegrees(Math.atan2(ax * by - ay * bx, ax * bx + ay * by))) > 0.5) { return null; }
            if (g.getNumPoints() > 2) { return null; }
            Object far = forward ? n.getEndNodeId() : n.getStartNodeId();
            ConnectionPoint target = points.get(key(far));
            if (target != null) { return target; }
            prev = here;
            node = far;
            current = n;
        }
        return null;
    }

    /** Кэш комплексов зданий по id точки подключения. */
    private final Map<Object, Geometry> complexCache = new HashMap<>();
    private final Map<Object, Restriction> complexOwnerCache = new HashMap<>();

    /** Полигоны ОКС, содержащие точку (перекрывающиеся контуры — один комплекс). */
    @SuppressWarnings("unchecked")
    private List<Restriction> coveringOks(ConnectionPoint target) {
        List<Restriction> out = new ArrayList<>();
        for (Object item : restrictions.query(target.getGeometry().getEnvelopeInternal())) {
            Restriction r = (Restriction) item;
            if (r.getGeometry().getDimension() == 2
                    && RestrictionRules.OKS.equals(profile.canonical(r.getRestrictionType()))
                    && r.getGeometry().covers(target.getGeometry())) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing(r -> String.valueOf(r.getId())));
        return out;
    }

    /**
     * Объединение полигонов ОКС, содержащих точку: перекрывающиеся контуры зданий — один комплекс,
     * и §2.2 «ближайшая граница полигона» считается по его границе (иначе финальный участок мог бы
     * выйти из меньшего контура, оставаясь внутри большего). Совпадает с трактовкой ядра.
     */
    private Geometry complexOf(ConnectionPoint target) {
        return complexCache.computeIfAbsent(key(target.getId()), k -> {
            Geometry g = null;
            for (Restriction r : coveringOks(target)) {
                g = g == null ? r.getGeometry() : g.union(r.getGeometry());
            }
            return g;
        });
    }

    /** Ограничение, от имени которого проверяется §2.2 для точки (чтобы не дублировать диагностику). */
    private Restriction complexOwner(ConnectionPoint target) {
        return complexOwnerCache.computeIfAbsent(key(target.getId()), k -> {
            List<Restriction> all = coveringOks(target);
            return all.isEmpty() ? null : all.get(0);
        });
    }

    /**
     * Полигон, наложенный на здание точки (площадь пересечения > 1 м²: дубль контура, здание поверх
     * территории — шум данных) или пристроенный к нему корпус, на финальном участке тоже исключается
     * из отступа (как в ядре).
     */
    @SuppressWarnings("unchecked")
    private boolean overlapsBuildingOf(Restriction other, ConnectionPoint target) {
        if (other.getGeometry().getDimension() != 2) { return false; }
        for (Object item : restrictions.query(target.getGeometry().getEnvelopeInternal())) {
            Restriction building = (Restriction) item;
            if (building == other || !RestrictionRules.OKS.equals(profile.canonical(building.getRestrictionType()))
                    || building.getGeometry().getDimension() != 2 || !building.getGeometry().covers(target.getGeometry())) {
                continue;
            }
            if (other.getGeometry().intersection(building.getGeometry()).getArea() > 1.0) { return true; }
            // пристроенный корпус (стык стен): отступ к нему на финальном участке невыполним
            if (RestrictionRules.OKS.equals(profile.canonical(other.getRestrictionType()))
                    && other.getGeometry().distance(building.getGeometry()) <= 0.5) { return true; }
        }
        return false;
    }

    private boolean nearestBoundaryBlocked(Restriction own, ConnectionPoint target, DiameterSpec dn, RestrictionRule rule) {
        Coordinate t = target.getGeometry().getCoordinate();
        Coordinate b = DistanceOp.nearestPoints(own.getGeometry().getBoundary(), target.getGeometry())[0];
        double distance = t.distance(b);
        if (distance <= GEOMETRY_EPS) { return false; }
        double clearance = rule.minDistance(dn.getDiameter()) + dn.halfWidth() + rule.getOwnWidthM() / 2;
        Coordinate outside = new Coordinate(b.x + (b.x - t.x) * (clearance + GEOMETRY_EPS) / distance,
                b.y + (b.y - t.y) * (clearance + GEOMETRY_EPS) / distance);
        Point entry = Crs.UTM.createPoint(outside);
        if (own.getGeometry().covers(entry)) { return true; }
        LineString approach = Crs.UTM.createLineString(new Coordinate[]{b, outside});
        Envelope area = new Envelope(approach.getEnvelopeInternal()); area.expandBy(searchRadius);
        for (Object item : restrictions.query(area)) {
            Restriction other = (Restriction) item;
            if (other == own || !profile.forType(other.getRestrictionType()).isForbidden()) { continue; }
            if (other.getGeometry().getDimension() == 2 && other.getGeometry().covers(target.getGeometry())) {
                continue; // тот же разрешённый финальный подход к точке внутри нескольких территорий
            }
            RestrictionRule otherRule = profile.forType(other.getRestrictionType());
            double needed = otherRule.minDistance(dn.getDiameter()) + dn.halfWidth() + otherRule.getOwnWidthM() / 2;
            if (other.getGeometry().distance(approach) < needed - GEOMETRY_EPS) { return true; }
        }
        return false;
    }

    /**
     * Пересечение существующей сети в точке врезки (корень варианта) — не спецпроход, даже если оно найдено
     * продлённым отрезком соседнего куска (спецучасток может заканчиваться в камере врезки внутри спецзоны).
     */
    private boolean atTieIn(Restriction r, Geometry part) {
        if (!RestrictionRules.HEAT_NETWORK.equals(r.getRestrictionType()) || part.getDimension() > 0) { return false; }
        List<Object> ties = new ArrayList<>(graph.roots);
        ties.addAll(points.keySet()); // точка ОКС может быть задана прямо на существующей трубе
        for (Object id : ties) {
            Point node = graph.nodes.get(key(id));
            if (node == null) { continue; }
            boolean all = true;
            for (Coordinate c : part.getCoordinates()) { all &= node.getCoordinate().distance(c) <= GEOMETRY_EPS; }
            if (all) { return true; }
        }
        return false;
    }

    /**
     * Участок касается существующей трубы только в разрешённых узлах — врезка, а не пересечение:
     * отступ к этой трубе на нём неприменим. Разрешённый узел — корень-врезка либо точка ОКС,
     * заданная во входных данных прямо на трубе (оба конца могут быть такими). Любая другая точка
     * касания — спецпроход, и проверки §4 работают как обычно.
     */
    private boolean tieIn(NewPipe p, Restriction r) {
        if (!RestrictionRules.HEAT_NETWORK.equals(r.getRestrictionType())) { return false; }
        Geometry intersection = p.getGeometry().intersection(r.getGeometry());
        // пустое пересечение — обычное дело: конец участка лежит на трубе с точностью до записи
        // координат в GeoJSON, и JTS их уже не пересекает (пустой результат при этом остаётся
        // LineString, то есть getDimension() == 1 — проверять размерность без isEmpty нельзя).
        // Само касание проверяется по узлу ниже.
        if (!intersection.isEmpty() && intersection.getDimension() > 0) { return false; }
        List<Coordinate> allowed = new ArrayList<>();
        for (Object id : List.of(p.getStartNodeId(), p.getEndNodeId())) {
            Point node = graph.nodes.get(key(id));
            if (node != null && (graph.roots.contains(key(id)) || points.containsKey(key(id)))
                    && r.getGeometry().distance(node) <= GEOMETRY_EPS) {
                allowed.add(node.getCoordinate());
            }
        }
        if (allowed.isEmpty()) { return false; }
        for (Coordinate c : intersection.getCoordinates()) {
            boolean ok = false;
            for (Coordinate a : allowed) { ok |= c.distance(a) <= GEOMETRY_EPS; }
            if (!ok) { return false; }
        }
        return true;
    }

    private void special(NewPipe p, Restriction r, DiameterSpec dn, RestrictionRule rule, List<Interval> all) {
        LineString line = p.getGeometry();
        List<Interval> local = new ArrayList<>(); double offset = 0;
        double ownHalfWidth = rule.getOwnWidthM() / 2;
        if (RestrictionRules.HEAT_NETWORK.equals(r.getRestrictionType())) {
            for (ExistingPipe e : graph.at(Crs.UTM.createPoint(r.getGeometry().getCoordinate()))) {
                if (key(e.getId()).equals(key(r.getId()))) {
                    ownHalfWidth = DiameterTable.byDiameter(e.getDiameter()).orElseThrow().halfWidth(); break;
                }
            }
        }
        double clearance = rule.minDistance(p.getDiameter()) + dn.halfWidth() + ownHalfWidth;
        // спецучасток — ±margin от пересечения, но не короче зоны, где не выдерживается отступ
        // (пересечение под острым углом): вне спецпрохода отступ обязателен, внутри не проверяется
        Geometry clearanceZone = r.getGeometry().buffer(clearance);
        for (int i = 1; i < line.getNumPoints(); i++) {
            Coordinate a = line.getCoordinateN(i - 1), b = line.getCoordinateN(i);
            double length = a.distance(b);
            if (length <= GEOMETRY_EPS) { offset += length; continue; }
            double margin = rule.getSpecialMarginM();
            double ux = (b.x - a.x) / length, uy = (b.y - a.y) / length;
            LineString extended = Crs.UTM.createLineString(new Coordinate[]{
                    new Coordinate(a.x - ux * margin, a.y - uy * margin),
                    new Coordinate(b.x + ux * margin, b.y + uy * margin)});
            Geometry intersection = extended.intersection(r.getGeometry());
            for (int j = 0; j < intersection.getNumGeometries(); j++) {
                Geometry part = intersection.getGeometryN(j);
                if (part.isEmpty() || atTieIn(r, part)) { continue; }
                double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
                for (Coordinate c : part.getCoordinates()) {
                    double at = (c.x - a.x) * ux + (c.y - a.y) * uy;
                    low = Math.min(low, at); high = Math.max(high, at);
                    if (rule.getMinCrossingAngleDeg() > 0) { crossingAngle(p, r, rule, c, ux, uy); }
                    // просвет проверяем только там, где пересечение действительно лежит на этом
                    // участке: extended выходит за его концы на margin, и снос точки пересечения на
                    // конец участка проверял бы глубину там, где трасса уже ушла от коммуникации.
                    // Пересечение за концом проверит соседний участок, который его и содержит (§5)
                    if (at >= -GEOMETRY_EPS && at <= length + GEOMETRY_EPS) {
                        vertical(p, r, rule, dn, offset + Math.max(0, Math.min(length, at)));
                    }
                }
                if (p.getLayingMethod() == LayingMethod.BASE
                        && (low > length + GEOMETRY_EPS || high < -GEOMETRY_EPS)) {
                    // объект пересекается не этим участком, а соседним куском цепочки: спецпроход отмерен
                    // там (§4), а отступ этого куска проверяет checkOutsideClearance
                    continue;
                }
                double from = Math.max(0, low - margin), to = Math.min(length, high + margin);
                // продление по зоне отступа — только для спецучастков: base-участок за стыком со спецпроходом
                // проверяется на отступ с допуском (checkOutsideClearance), обе трактовки §4 принимаются
                Geometry inClearance = p.getLayingMethod() == LayingMethod.SPECIAL
                        ? Crs.UTM.createLineString(new Coordinate[]{a, b}).intersection(clearanceZone)
                        : Crs.UTM.createLineString(new Coordinate[]{a, b}).getFactory().createLineString();
                for (int k = 0; k < inClearance.getNumGeometries(); k++) {
                    Coordinate[] cc = inClearance.getGeometryN(k).getCoordinates();
                    if (cc.length < 2) { continue; }
                    double c0 = Double.POSITIVE_INFINITY, c1 = Double.NEGATIVE_INFINITY;
                    for (Coordinate c : cc) {
                        double at = (c.x - a.x) * ux + (c.y - a.y) * uy;
                        c0 = Math.min(c0, at); c1 = Math.max(c1, at);
                    }
                    // компонент зоны отступа, примыкающий к спецучастку (само пересечение может лежать
                    // за границей этого участка — трасса уже разбита техузлами)
                    if (c0 <= to + GEOMETRY_EPS && c1 >= from - GEOMETRY_EPS) {
                        from = Math.min(from, Math.max(0, c0)); to = Math.max(to, Math.min(length, c1));
                    } else {
                        // отдельный заход в зону отступа того же объекта без пересечения (трасса идёт
                        // почти вдоль) — как в ядре, это самостоятельный спецучасток
                        double a2 = Math.max(0, c0), b2 = Math.min(length, c1);
                        if (b2 > a2 + GEOMETRY_EPS) {
                            local.add(new Interval(offset + a2, offset + b2, rule.getKSpecial(), r.getId()));
                        }
                    }
                }
                // кусок целиком лежит в зоне отступа объекта: ядро считает зону вдоль всего исходного
                // участка до разбиения, поэтому спецучасток может занимать кусок целиком (K = max —
                // консервативно). Пограничный случай: коммуникация идёт почти параллельно трассе.
                if (p.getLayingMethod() == LayingMethod.SPECIAL
                        && Crs.UTM.createLineString(new Coordinate[]{a, b}).difference(clearanceZone).isEmpty()) {
                    from = 0;
                    to = length;
                }
                if (to > from + GEOMETRY_EPS) {
                    local.add(new Interval(offset + from, offset + to, rule.getKSpecial(), r.getId()));
                }
            }
            offset += length;
        }
        if (local.isEmpty() && p.getLayingMethod() == LayingMethod.SPECIAL
                && clearanceZone.covers(Crs.UTM.createPoint(
                        new LengthIndexedLine(line).extractPoint(line.getLength() / 2)))
                && joinedSpecialCrossing(p, r)) {
            // кусок цепочки спецпрохода, который сам объект не пересекает: пересечение на соседнем
            // куске, а этот ещё в зоне отступа — ядро продолжает там спецучасток (§4, иначе базовый
            // кусок сразу за спецпроходом нарушал бы отступ §3.1). Критерий — середина участка, как и
            // у Kспец: ядро режет трассу по границам зоны, и «внутри зоны» для куска определяет
            // именно середина, а не край, где граница считается с точностью до записи координат
            local.add(new Interval(0, line.getLength(), rule.getKSpecial(), r.getId()));
        }
        all.addAll(local);
        local.sort(Comparator.comparingDouble(v -> v.from));
        double cursor = 0;
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        for (Interval interval : local) {
            if (interval.from > cursor + GEOMETRY_EPS) {
                checkOutsideClearance(p, r, indexed, cursor, interval.from, clearance);
            }
            cursor = Math.max(cursor, interval.to);
        }
        if (cursor < line.getLength() - GEOMETRY_EPS) {
            checkOutsideClearance(p, r, indexed, cursor, line.getLength(), clearance);
        }
    }

    private void checkOutsideClearance(NewPipe p, Restriction r, LengthIndexedLine indexed,
                                       double from, double to, double clearance) {
        if (p.getLayingMethod() == LayingMethod.SPECIAL && joinedSpecialCrossing(p, r)) {
            // спецпроход разбит техузлами (наложение зон): пересечение лежит на соседнем куске той же
            // цепочки, отступ внутри спецпрохода не проверяется (§4)
            return;
        }
        double start = from, end = to;
        if (p.getLayingMethod() == LayingMethod.BASE) {
            if (from <= GEOMETRY_EPS && joinedSpecialAt(p, p.getStartNodeId(), r)) {
                start = Math.min(end, clearance + GEOMETRY_EPS);
            }
            if (to >= p.getGeometry().getLength() - GEOMETRY_EPS && joinedSpecialAt(p, p.getEndNodeId(), r)) {
                end = Math.max(start, to - clearance - GEOMETRY_EPS);
            }
        }
        if (end > start + GEOMETRY_EPS) {
            Geometry part = indexed.extractLine(start, end);
            if (RestrictionRules.HEAT_NETWORK.equals(r.getRestrictionType())) {
                // у врезки в существующую сеть отход от камеры неизбежно ближе отступа: не проверяем
                // окрестность радиусом 2·clearance вокруг узла на этой трубе (ядро требует выхода из
                // зоны отступа не дальше 2·clearance вдоль отрезка от врезки). Узлом может быть и
                // точка ОКС, заданная прямо на трубе, — подход к ней так же неизбежно идёт в зоне
                List<Object> ties = new ArrayList<>(graph.roots);
                ties.addAll(points.keySet());
                for (Object id : ties) {
                    Point node = graph.nodes.get(key(id));
                    if (node != null && r.getGeometry().distance(node) <= GEOMETRY_EPS) {
                        part = part.difference(node.buffer(2 * clearance + GEOMETRY_EPS));
                    }
                }
            }
            checks.require(part.isEmpty() || part.distance(r.getGeometry()) >= clearance - GEOMETRY_EPS,
                    p.getId(), "§4", "Недостаточный отступ вне спецпрохода " + r.getId());
        }
    }

    /** Участок примыкает к спецучастку, который реально пересекает какое-нибудь спецпроходное ограничение. */
    @SuppressWarnings("unchecked")
    private boolean joinedSpecialChain(NewPipe p) {
        for (Object id : List.of(p.getStartNodeId(), p.getEndNodeId())) {
            for (NewPipe neighbor : graph.adjacent.getOrDefault(key(id), List.of())) {
                if (neighbor == p || neighbor.getLayingMethod() != LayingMethod.SPECIAL) { continue; }
                Envelope env = new Envelope(neighbor.getGeometry().getEnvelopeInternal());
                env.expandBy(searchRadius);
                for (Object item : restrictions.query(env)) {
                    Restriction r = (Restriction) item;
                    if (!profile.forType(r.getRestrictionType()).isForbidden()
                            && neighbor.getGeometry().intersects(r.getGeometry())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Ограничение r пересекает какой-нибудь кусок той же цепочки спецпрохода. §4 делит спецучасток
     * техузлами на границах наложения зон, поэтому само пересечение может оказаться не на соседнем
     * куске, а через один-два: при пересечении под малым углом трасса идёт в зоне отступа десятки
     * метров, и эта зона нарезана границами других препятствий. Внутри спецпрохода отступ не
     * проверяется, значит и весь этот кусок цепочки проверять не нужно. Идём только по кускам
     * laying_method = special — базовый кусок цепочку обрывает.
     */
    private boolean joinedSpecialCrossing(NewPipe p, Restriction r) {
        Set<NewPipe> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<NewPipe> queue = new ArrayDeque<>();
        seen.add(p);
        queue.add(p);
        for (int guard = 0; guard < SPECIAL_CHAIN_MAX && !queue.isEmpty(); guard++) {
            NewPipe current = queue.poll();
            for (Object id : List.of(current.getStartNodeId(), current.getEndNodeId())) {
                for (NewPipe neighbor : graph.adjacent.getOrDefault(key(id), List.of())) {
                    if (!seen.add(neighbor) || neighbor.getLayingMethod() != LayingMethod.SPECIAL) { continue; }
                    if (neighbor.getGeometry().intersects(r.getGeometry())) { return true; }
                    queue.add(neighbor);
                }
            }
        }
        return false;
    }

    private boolean joinedSpecialAt(NewPipe p, Object nodeId, Restriction r) {
        for (NewPipe neighbor : graph.adjacent.getOrDefault(key(nodeId), List.of())) {
            if (neighbor == p || neighbor.getLayingMethod() != LayingMethod.SPECIAL) { continue; }
            if (neighbor.getGeometry().intersects(r.getGeometry())) { return true; }
        }
        return false;
    }

    /**
     * Kспец участка и проверка его границ. Коэффициент берём по середине участка — ровно так же, как
     * ядро: §4 делит трассу на границах спецпрохода, поэтому нормальный участок либо целиком внутри
     * зоны, либо целиком снаружи, и середина это и выражает. Устойчивость к краям важна: границы зон
     * считаются из геометрии с точностью до записи координат, и «кусочек» в пару сантиметров на краю
     * не должен менять Kспец всего участка. Настоящий промах разбиения ловит проверка смены набора
     * объектов ниже (обрезки короче MIN_SPECIAL_PIECE_M она пропускает — таких кусков ядро не создаёт).
     */
    private double factors(NewPipe p, List<Interval> intervals) {
        double length = p.getGeometry().getLength();
        TreeSet<Double> boundaries = new TreeSet<>(); boundaries.add(0.0); boundaries.add(length);
        for (Interval interval : intervals) { boundaries.add(interval.from); boundaries.add(interval.to); }
        List<Double> sorted = new ArrayList<>(boundaries);
        Set<Object> previous = null; double result = 1;
        double middle = length / 2;
        for (Interval interval : intervals) {
            if (interval.from <= middle && interval.to >= middle) { result = Math.max(result, interval.factor); }
        }
        for (int i = 1; i < sorted.size(); i++) {
            double mid = (sorted.get(i) + sorted.get(i - 1)) / 2;
            Set<Object> active = new HashSet<>();
            for (Interval interval : intervals) {
                if (interval.from <= mid && interval.to >= mid) { active.add(key(interval.id)); }
            }
            if (sorted.get(i) - sorted.get(i - 1) < MIN_SPECIAL_PIECE_M) {
                continue; // куски такой длины ядро не создаёт — набор объектов на них не проверяем
            }
            boolean chainPiece = p.getLayingMethod() == LayingMethod.SPECIAL && active.isEmpty()
                    && joinedSpecialChain(p);
            if (chainPiece) {
                // кусок внутри цепочки спецпрохода: сам объект пересекается соседним куском (§4 —
                // спецучасток делится на границах наложения зон); K = max, стоимость не занижена
                checks.warn(p.getId(), "§4", "Спецучасток без собственного пересечения: кусок цепочки"
                        + " спецпрохода (объект пересекает соседний кусок)");
                result = Math.max(result, p.getKSpecial());
                previous = null;
                continue;
            }
            checks.require((p.getLayingMethod() == LayingMethod.SPECIAL) == !active.isEmpty(), p.getId(), "§4",
                    "laying_method не соответствует границам спецпрохода");
            checks.require(previous == null || previous.equals(active), p.getId(), "§4",
                    "Нужен технический узел при смене набора спецпроходов: " + previous + " → " + active
                            + " на " + String.format(java.util.Locale.ROOT, "%.2f", sorted.get(i - 1)) + " м из "
                            + String.format(java.util.Locale.ROOT, "%.2f", length));
            previous = active;
        }
        return result;
    }

    private void crossingAngle(NewPipe p, Restriction r, RestrictionRule rule, Coordinate at, double ux, double uy) {
        Geometry boundary = r.getGeometry().getDimension() == 2 ? r.getGeometry().getBoundary() : r.getGeometry();
        for (int i = 0; i < boundary.getNumGeometries(); i++) {
            Coordinate[] coordinates = boundary.getGeometryN(i).getCoordinates();
            for (int j = 1; j < coordinates.length; j++) {
                LineSegment segment = new LineSegment(coordinates[j - 1], coordinates[j]);
                if (segment.distance(at) > GEOMETRY_EPS) { continue; }
                double angle = angle(ux, uy, segment.p1.x - segment.p0.x, segment.p1.y - segment.p0.y);
                angle = Math.min(angle, 180 - angle);
                checks.require(angle + 0.01 >= rule.getMinCrossingAngleDeg(), p.getId(), "§4",
                        "Угол пересечения " + r.getRestrictionType() + " меньше допустимого");
            }
        }
    }

    private double depth(NewPipe p) {
        if (mode == SolveOptions.Mode.PLAN_2D) {
            checks.require(p.getDepthStart() == null && p.getDepthEnd() == null, p.getId(), "§5/§7", "В 2D глубины должны быть null");
            return 1;
        }
        if (p.getDepthStart() == null || p.getDepthEnd() == null) {
            checks.require(false, p.getId(), "§5", "В 3D обязательны обе глубины"); return 1;
        }
        double a = p.getDepthStart(), b = p.getDepthEnd();
        checks.require(a >= CostConstants.MIN_DEPTH_M && b >= CostConstants.MIN_DEPTH_M, p.getId(), "§5", "Глубина меньше минимальной");
        // допуск 0.011 м — округление глубин в выходе до сантиметра (короткие куски у техузлов)
        checks.require(Math.abs(a - b) <= p.getGeometry().getLength() * CostConstants.MAX_SLOPE + 0.011,
                p.getId(), "§5", "Превышен максимальный уклон");
        checks.require((a - CostConstants.DEFAULT_DEPTH_M) * (b - CostConstants.DEFAULT_DEPTH_M) >= -1e-8,
                p.getId(), "§5", "Нужен техузел на отметке обычной глубины");
        return (CostConstants.depthFactor(a) + CostConstants.depthFactor(b)) / 2;
    }

    private void vertical(NewPipe p, Restriction r, RestrictionRule rule, DiameterSpec dn, double at) {
        if (mode != SolveOptions.Mode.DEPTH_3D || p.getDepthStart() == null || p.getDepthEnd() == null) { return; }
        double h = p.getDepthStart() + (p.getDepthEnd() - p.getDepthStart()) * at / p.getGeometry().getLength();
        if (rule.isCrossUnderOnly()) {
            checks.require(h >= rule.getVerticalClearanceM() - 0.001, p.getId(), "§5", "Недостаточная глубина под " + r.getId());
        } else {
            double ownHeight = rule.getOwnHeightM();
            if (RestrictionRules.HEAT_NETWORK.equals(r.getRestrictionType())) {
                for (ExistingPipe e : graph.at(Crs.UTM.createPoint(r.getGeometry().getCoordinate()))) {
                    if (key(e.getId()).equals(key(r.getId()))) { ownHeight = DiameterTable.byDiameter(e.getDiameter()).orElseThrow().getHeightM(); }
                }
            }
            checks.require(h + dn.getHeightM() <= rule.getOwnDepthM() - rule.getVerticalClearanceM() + 0.001
                    || h >= rule.getOwnDepthM() + ownHeight + rule.getVerticalClearanceM() - 0.001,
                    p.getId(), "§5", "Недостаточный вертикальный просвет " + r.getId());
        }
    }

    private void depthContinuity() {
        if (mode != SolveOptions.Mode.DEPTH_3D) { return; }
        for (Map.Entry<Object, List<NewPipe>> entry : graph.adjacent.entrySet()) {
            Double depth = null;
            for (NewPipe p : entry.getValue()) {
                Double current = key(p.getStartNodeId()).equals(entry.getKey()) ? p.getDepthStart() : p.getDepthEnd();
                if (depth != null && current != null) { checks.close(current, depth, 0.001, entry.getKey(), "§5", "Непрерывность глубины"); }
                depth = current;
            }
        }
    }

    private static boolean straight(LineString line) {
        return Math.abs(line.getLength() - line.getStartPoint().distance(line.getEndPoint())) <= GEOMETRY_EPS;
    }
    private static double angle(double ax, double ay, double bx, double by) {
        double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (denominator == 0) { return 180; }
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (ax * bx + ay * by) / denominator))));
    }
    private static class Interval {
        final double from, to, factor; final Object id;
        Interval(double from, double to, double factor, Object id) {
            this.from = from; this.to = to; this.factor = factor; this.id = id;
        }
    }
}
