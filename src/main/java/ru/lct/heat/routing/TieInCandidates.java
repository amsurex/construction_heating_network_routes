package ru.lct.heat.routing;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heat.model.ExistingChamber;
import ru.lct.heat.model.ExistingPipe;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.ref.ChamberCostTable;
import ru.lct.heat.model.ref.CostConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Кандидаты точек присоединения к существующей сети (Техприложение §2.4, FAQ п.11–12):
 * <ul>
 *   <li>существующие камеры с запасом примыканий (&lt; 4) — врезка 5 млн за каждый новый участок;</li>
 *   <li>точки на существующих участках (вершины + выборка с шагом) — новая камера, стоимость по
 *       наибольшему ДУ примыкающих участков (включая существующий); точки ближе 10 м к камере
 *       с запасом исключаются — по правилу там используется камера.</li>
 * </ul>
 */
@Slf4j
public final class TieInCandidates {

    /** Запас к радиусу 10 м вокруг существующей камеры, м. */
    static final double SNAP_MARGIN_M = 0.5;

    private static final double SNAP_TOL = 1e-6;

    /** Кандидат: точка + что это (камера или участок). */
    @Value
    public static class Candidate {
        Coordinate point;
        /** Существующая камера, либо null. */
        ExistingChamber chamber;
        /** Существующий участок для новой камеры, либо null. */
        ExistingPipe pipe;
        /** Наибольший ДУ существующих участков в этой точке. */
        int existingMaxDiameter;

        public boolean isChamber() {
            return chamber != null;
        }

        /** Символическая надбавка новой камере: при равной стоимости предпочитаем существующую (меньше сооружений). */
        private static final double NEW_CHAMBER_TIE_BREAK = 1.0;

        /** Стоимость присоединения при новом участке ДУ newDiameter, руб. */
        public double terminalCost(int newDiameter) {
            return isChamber()
                    ? ChamberCostTable.TIE_IN_COST
                    : ChamberCostTable.newChamberCost(Math.max(existingMaxDiameter, newDiameter)) + NEW_CHAMBER_TIE_BREAK;
        }
    }

    private final List<Candidate> candidates;
    /** Число примыканий существующих участков к каждой камере. */
    private final Map<Object, Integer> chamberDegree;

    private TieInCandidates(List<Candidate> candidates, Map<Object, Integer> chamberDegree) {
        this.candidates = Collections.unmodifiableList(candidates);
        this.chamberDegree = chamberDegree;
    }

    public List<Candidate> all() {
        return candidates;
    }

    /** Кандидаты только к разрешённым и не к исключённым объектам (id сравниваются как строки). */
    public TieInCandidates restrict(java.util.Set<String> pinIds, java.util.Set<String> excludeIds) {
        if (pinIds.isEmpty() && excludeIds.isEmpty()) {
            return this;
        }
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : candidates) {
            String id = String.valueOf(c.isChamber() ? c.getChamber().getId() : c.getPipe().getId());
            if (excludeIds.contains(id)) {
                continue;
            }
            if (!pinIds.isEmpty() && !pinIds.contains(id)) {
                continue;
            }
            out.add(c);
        }
        log.info("Режим эксперта: кандидатов врезки {} из {} (pin={}, exclude={})", out.size(), candidates.size(),
                pinIds, excludeIds);
        return new TieInCandidates(out, chamberDegree);
    }

    public int chamberDegree(ExistingChamber ch) {
        return chamberDegree.getOrDefault(ch.getId(), 0);
    }

    @SuppressWarnings("unchecked")
    public static TieInCandidates build(InputModel input, double sampleStepM) {
        // индекс концов участков → степень камер и макс. ДУ в точке
        STRtree pipeIndex = new STRtree();
        for (ExistingPipe p : input.getPipes()) {
            pipeIndex.insert(p.getGeometry().getEnvelopeInternal(), p);
        }
        pipeIndex.build();

        Map<Object, Integer> degree = new HashMap<>();
        List<Candidate> out = new ArrayList<>();
        List<ExistingChamber> usable = new ArrayList<>();
        for (ExistingChamber ch : input.getChambers()) {
            Coordinate c = ch.getGeometry().getCoordinate();
            int deg = 0;
            int maxDn = 0;
            List<ExistingPipe> near = pipeIndex.query(new Envelope(c));
            for (ExistingPipe p : near) {
                Coordinate[] cs = p.getGeometry().getCoordinates();
                boolean touches = cs[0].distance(c) < SNAP_TOL || cs[cs.length - 1].distance(c) < SNAP_TOL;
                if (touches) {
                    deg++;
                    maxDn = Math.max(maxDn, p.getDiameter());
                } else if (p.getGeometry().distance(ch.getGeometry()) < SNAP_TOL) {
                    deg += 2; // линия проходит через камеру — два примыкания (FAQ п.12)
                    maxDn = Math.max(maxDn, p.getDiameter());
                }
            }
            degree.put(ch.getId(), deg);
            if (deg < CostConstants.MAX_CHAMBER_CONNECTIONS) {
                out.add(new Candidate(c, ch, null, maxDn));
                usable.add(ch);
            } else {
                log.debug("Камера {} заполнена ({} примыканий)", ch.getId(), deg);
            }
        }

        STRtree usableIndex = new STRtree();
        for (ExistingChamber ch : usable) {
            usableIndex.insert(ch.getGeometry().getEnvelopeInternal(), ch);
        }
        usableIndex.build();
        // запас к 10 м: точка ровно в 10 м от камеры (шаг выборки, округление координат) — пограничная,
        // проверяющий трактует её как «камера доступна» (§2.4, округление 4.999 → 5)
        double snap = CostConstants.EXISTING_CHAMBER_SNAP_M + SNAP_MARGIN_M;

        int pipePoints = 0;
        for (ExistingPipe p : input.getPipes()) {
            LengthIndexedLine lil = new LengthIndexedLine(p.getGeometry());
            double len = p.getGeometry().getLength();
            List<Double> positions = new ArrayList<>();
            // вершины ломаной
            double acc = 0;
            Coordinate[] cs = p.getGeometry().getCoordinates();
            for (int i = 0; i < cs.length; i++) {
                if (i > 0) {
                    acc += cs[i - 1].distance(cs[i]);
                }
                positions.add(acc);
            }
            // равномерная выборка
            for (double d = sampleStepM; d < len - sampleStepM / 2; d += sampleStepM) {
                positions.add(d);
            }
            for (double pos : positions) {
                Coordinate c = lil.extractPoint(pos);
                Envelope env = new Envelope(c.x - snap, c.x + snap, c.y - snap, c.y + snap);
                boolean nearChamber = false;
                for (ExistingChamber ch : (List<ExistingChamber>) usableIndex.query(env)) {
                    if (ch.getGeometry().getCoordinate().distance(c) <= snap) {
                        nearChamber = true;
                        break;
                    }
                }
                if (nearChamber) {
                    continue;
                }
                int maxDn = p.getDiameter();
                for (ExistingPipe q : (List<ExistingPipe>) pipeIndex.query(new Envelope(c))) {
                    if (q != p && q.getGeometry().distance(ru.lct.heat.geometry.GeomUtil.point(c)) < SNAP_TOL) {
                        maxDn = Math.max(maxDn, q.getDiameter());
                    }
                }
                out.add(new Candidate(c, null, p, maxDn));
                pipePoints++;
            }
        }
        log.info("Кандидаты врезки: {} камер (из {}), {} точек на участках",
                usable.size(), input.getChambers().size(), pipePoints);
        return new TieInCandidates(out, degree);
    }
}
