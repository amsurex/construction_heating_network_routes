package ru.lct.heat.geometry;

import lombok.Getter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import ru.lct.heat.model.ref.RestrictionRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Одно препятствие, подготовленное под конкретный ДУ новой сети.
 *
 * <ul>
 *   <li>{@code clearance} — зона, в которую ось новой сети заходить нельзя (кроме разрешённого
 *       пересечения): source.buffer(minDistance + halfWidth(new) + ownHalfWidth). Техприложение §3.1, §4.</li>
 *   <li>{@code specialZone} — для спецпроходов: зона, внутри которой участок должен быть прямым и
 *       считается специальным: source.buffer(specialMargin). Для запретов = null.</li>
 *   <li>{@code vertexRing} — вершины-кандидаты для графа видимости: mitre-буфер чуть шире clearance
 *       (и specialZone), чтобы рёбра между ними не касались зоны.</li>
 * </ul>
 */
@Getter
public final class Obstacle {

    /** Допуск упрощения контура для вершин графа, м. */
    private static final double SIMPLIFY_TOL = 0.5;

    /** Сегментов на четверть окружности в буфере зоны отступа. */
    private static final int QUADRANT_SEGMENTS = 16;
    private static final BufferParameters ROUND = new BufferParameters(QUADRANT_SEGMENTS,
            BufferParameters.CAP_ROUND, BufferParameters.JOIN_ROUND, 5.0);
    /**
     * Поправка «описанный многоугольник вместо вписанного». JTS строит буфер ломаной, вписанной в
     * окружность: у угла препятствия полигон отступа оказывается ближе истинного радиуса на
     * d·(1−cos(π/4q)) — при d=5.7 м и q=8 это 2.7 см, и трасса законно прижималась к углу дома на
     * 2 см ближе минимального расстояния (валидатор считает истинное расстояние и справедливо ругался).
     * Буферим на d/cos(π/4q): многоугольник описан вокруг окружности, ближе минимального расстояния
     * подойти нельзя. Запас — доли сантиметра, §7 «округление вверх при незначительном отклонении».
     * К зоне спецпрохода поправка не применяется: её границы — это границы участка в выдаче, и ядро
     * с валидатором должны понимать их одинаково (допуск сравнения там — MIN_SPECIAL_PIECE_M).
     */
    private static final double BUFFER_OUTSET = 1.0 / Math.cos(Math.PI / (4.0 * QUADRANT_SEGMENTS));
    private static final BufferParameters MITRE = new BufferParameters(8, BufferParameters.CAP_SQUARE,
            BufferParameters.JOIN_MITRE, 2.0);

    private final Object id;
    private final String type;
    private final RestrictionRule rule;
    private final boolean forbidden;
    private final Geometry source;
    private final PreparedGeometry sourcePrep;
    private final Geometry clearance;
    /** Радиус зоны отступа: мин. расстояние + половина габарита новой сети + половина габарита препятствия, м. */
    private final double clearanceDistM;
    private final PreparedGeometry clearancePrep;
    private final Geometry specialZone;
    private final PreparedGeometry specialZonePrep;
    private final List<Coordinate> vertices;
    private final Envelope envelope;
    /** Индекс сегментов границы источника — для расчёта угла пересечения (только если задан мин. угол). */
    private final STRtree boundarySegments;

    /**
     * @param newHalfWidth  половина расчётной ширины новой сети, м
     * @param vertexEps     зазор вершин графа от границы зоны, м
     * @param specialStepM  шаг уплотнения контура спецзоны для спецпроходов (точки, откуда можно
     *                      пересекать под нужным углом), м; 0 — не уплотнять
     */
    public Obstacle(Object id, String type, RestrictionRule rule, Geometry source,
                    double newHalfWidth, double vertexEps, double specialStepM) {
        this.id = id;
        this.type = type;
        this.rule = rule;
        this.forbidden = rule.isForbidden();
        this.source = source;
        this.sourcePrep = PreparedGeometryFactory.prepare(source);

        double ownHalf = rule.getOwnWidthM() / 2.0;
        double clearDist = rule.getMinDistanceM() + newHalfWidth + ownHalf;
        this.clearanceDistM = clearDist;
        this.clearance = BufferOp.bufferOp(source, clearDist * BUFFER_OUTSET, ROUND);
        this.clearancePrep = PreparedGeometryFactory.prepare(clearance);

        double vertexDist = clearDist;
        if (forbidden) {
            this.specialZone = null;
            this.specialZonePrep = null;
        } else {
            this.specialZone = BufferOp.bufferOp(source, rule.getSpecialMarginM(), ROUND);
            this.specialZonePrep = PreparedGeometryFactory.prepare(specialZone);
            vertexDist = Math.max(clearDist, rule.getSpecialMarginM());
        }
        // Вершины графа строим по упрощённому контуру (меньше вершин), но ring отодвигаем ещё на
        // допуск упрощения, чтобы он гарантированно лежал вне точной зоны clearance.
        Geometry simplified = TopologyPreservingSimplifier.simplify(source, SIMPLIFY_TOL);
        Geometry ring = BufferOp.bufferOp(simplified, vertexDist + vertexEps + SIMPLIFY_TOL, MITRE);
        if (!forbidden && specialStepM > 0) {
            // Поворот внутри спецзоны запрещён, а пересекать под углом ≥ min нужно из произвольной
            // точки вдоль препятствия — даём A* точки-кандидаты по всему контуру спецзоны.
            ring = Densifier.densify(ring, specialStepM);
        }
        this.vertices = collectVertices(ring);
        this.envelope = ring.getEnvelopeInternal();
        this.boundarySegments = rule.getMinCrossingAngleDeg() > 0 ? indexBoundary(source) : null;
    }

    /** Препятствие с отступом, зависящим от ДУ (oks 5/7/9 м): пересобираем rule с нужным minDistance. */
    public static Obstacle forDiameter(Object id, String type, RestrictionRule rule, Geometry source,
                                       int diameter, double newHalfWidth, double vertexEps, double specialStepM) {
        RestrictionRule effective = rule.toBuilder().minDistanceM(rule.minDistance(diameter)).build();
        return new Obstacle(id, type, effective, source, newHalfWidth, vertexEps, specialStepM);
    }

    private static List<Coordinate> collectVertices(Geometry ring) {
        List<Coordinate> out = new ArrayList<>();
        for (int i = 0; i < ring.getNumGeometries(); i++) {
            Geometry g = ring.getGeometryN(i);
            if (g instanceof Polygon) {
                Polygon p = (Polygon) g;
                addRing(out, p.getExteriorRing());
                for (int h = 0; h < p.getNumInteriorRing(); h++) {
                    addRing(out, p.getInteriorRingN(h));
                }
            }
        }
        return out;
    }

    private static void addRing(List<Coordinate> out, LineString ring) {
        Coordinate[] cs = ring.getCoordinates();
        for (int i = 0; i < cs.length - 1; i++) { // последняя = первая
            out.add(cs[i]);
        }
    }

    private static STRtree indexBoundary(Geometry source) {
        STRtree tree = new STRtree();
        Geometry boundary = source.getDimension() == 2 ? source.getBoundary() : source;
        for (int i = 0; i < boundary.getNumGeometries(); i++) {
            Coordinate[] cs = boundary.getGeometryN(i).getCoordinates();
            for (int k = 0; k < cs.length - 1; k++) {
                LineSegment s = new LineSegment(cs[k], cs[k + 1]);
                tree.insert(new Envelope(s.p0, s.p1), s);
            }
        }
        tree.build();
        return tree;
    }

    /**
     * Минимальный угол (градусы, 0–90) между отрезком и сегментами границы источника, которые он пересекает.
     * Если пересечений нет — 90 (ограничение не нарушено).
     */
    @SuppressWarnings("unchecked")
    /** Допуск «граница проходит через точку пересечения», м (совпадает с допуском валидатора). */
    private static final double CROSSING_TOL_M = 0.02;

    public double minCrossingAngleDeg(Coordinate a, Coordinate b) {
        if (boundarySegments == null) {
            return 90.0;
        }
        LineSegment seg = new LineSegment(a, b);
        Envelope env = new Envelope(a, b);
        env.expandBy(CROSSING_TOL_M);
        List<LineSegment> candidates = boundarySegments.query(env);
        List<Coordinate> hits = new ArrayList<>(2);
        for (LineSegment s : candidates) {
            Coordinate x = seg.intersection(s);
            if (x != null) {
                hits.add(x);
            }
        }
        // угол считаем ко всем звеньям границы, проходящим через точку пересечения, а не только к тем,
        // что трасса пересекает «насквозь». Иначе выход ровно через угол полигона (трасса касается
        // второй стены в той же точке) проходит проверку: к одной стене угол 86°, ко второй — 4°.
        double min = 90.0;
        for (LineSegment s : candidates) {
            for (Coordinate h : hits) {
                if (s.distance(h) <= CROSSING_TOL_M) {
                    min = Math.min(min, GeomUtil.crossingAngleDeg(a, b, s.p0, s.p1));
                    break;
                }
            }
        }
        return min;
    }
}
