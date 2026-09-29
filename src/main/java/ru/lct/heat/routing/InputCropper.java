package ru.lct.heat.routing;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ExistingChamber;
import ru.lct.heat.model.ExistingPipe;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.Restriction;

import java.util.ArrayList;
import java.util.List;

/**
 * Ограничение области расчёта для входов «в масштабе города»: тысячи ограничений и километры сети,
 * а точки подключения — в одном районе. Область интереса = охват точек подключения, расширенный
 * на расстояние до ближайшего существующего участка плюс запас (Техприложение §2.1: явно избыточный
 * уход за пределы зоны исходных объектов некорректен — трасса и так не должна выходить далеко).
 * В расчёт попадают только объекты, пересекающие область. Ограничения вне области не влияют на
 * результат, потому что до них трасса не дойдёт.
 */
@Slf4j
public final class InputCropper {

    @Value
    public static class Cropped {
        InputModel input;
        Envelope area;
        int restrictionsBefore;
        int restrictionsAfter;
        int pipesBefore;
        int pipesAfter;
    }

    /**
     * Камеры, лежащие не точно на участке (шум координат), привязываются к ближайшей точке ближайшего
     * участка в пределах snapTolM; иначе они считаются «без примыканий» и до них нельзя дойти.
     */
    @SuppressWarnings("unchecked")
    public static InputModel snapChambers(InputModel input, double snapTolM) {
        STRtree pipeIndex = new STRtree();
        for (ExistingPipe p : input.getPipes()) {
            pipeIndex.insert(p.getGeometry().getEnvelopeInternal(), p);
        }
        pipeIndex.build();
        List<ExistingChamber> out = new ArrayList<>();
        int snapped = 0;
        for (ExistingChamber ch : input.getChambers()) {
            Geometry pt = ch.getGeometry();
            Envelope env = new Envelope(pt.getCoordinate());
            env.expandBy(snapTolM);
            ExistingPipe best = null;
            double bestD = Double.MAX_VALUE;
            for (ExistingPipe p : (List<ExistingPipe>) pipeIndex.query(env)) {
                double d = p.getGeometry().distance(pt);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            if (best != null && bestD > 1e-6 && bestD <= snapTolM) {
                org.locationtech.jts.geom.Coordinate c = org.locationtech.jts.operation.distance.DistanceOp
                        .nearestPoints(best.getGeometry(), pt)[0];
                out.add(new ExistingChamber(ch.getId(), pt.getFactory().createPoint(c)));
                snapped++;
                log.warn("Камера {} в {} м от участка {} — привязана к участку", ch.getId(),
                        Math.round(bestD * 100) / 100.0, best.getId());
            } else {
                out.add(ch);
            }
        }
        if (snapped == 0) {
            return input;
        }
        return input.toBuilder().clearChambers().chambers(out).build();
    }

    /**
     * @param marginM     запас вокруг охвата точек и ближайшей сети, м
     * @param minExtentM  минимальный размер области (для одиночной точки), м
     */
    @SuppressWarnings("unchecked")
    public static Cropped crop(InputModel input, double marginM, double minExtentM) {
        if (input.getConnectionPoints().isEmpty()) {
            return new Cropped(input, null, input.getRestrictions().size(), input.getRestrictions().size(),
                    input.getPipes().size(), input.getPipes().size());
        }
        Envelope pts = new Envelope();
        for (ConnectionPoint cp : input.getConnectionPoints()) {
            pts.expandToInclude(cp.getGeometry().getCoordinate());
        }
        // расстояние от каждой точки до ближайшего существующего участка — область должна включать сеть
        STRtree pipeIndex = new STRtree();
        for (ExistingPipe p : input.getPipes()) {
            pipeIndex.insert(p.getGeometry().getEnvelopeInternal(), p);
        }
        pipeIndex.build();
        double reach = 0;
        for (ConnectionPoint cp : input.getConnectionPoints()) {
            double d = nearestPipeDistance(pipeIndex, cp);
            reach = Math.max(reach, d);
        }
        double half = Math.max(minExtentM / 2, 0);
        Envelope area = new Envelope(pts);
        area.expandBy(Math.max(half, reach + marginM));

        List<Restriction> restrictions = new ArrayList<>();
        for (Restriction r : input.getRestrictions()) {
            if (r.getGeometry().getEnvelopeInternal().intersects(area)) {
                restrictions.add(r);
            }
        }
        List<ExistingPipe> pipes = new ArrayList<>();
        for (ExistingPipe p : input.getPipes()) {
            if (p.getGeometry().getEnvelopeInternal().intersects(area)) {
                pipes.add(p);
            }
        }
        List<ExistingChamber> chambers = new ArrayList<>();
        for (ExistingChamber c : input.getChambers()) {
            if (area.contains(c.getGeometry().getCoordinate())) {
                chambers.add(c);
            }
        }
        InputModel cropped = InputModel.builder()
                .sources(input.getSources())
                .pipes(pipes)
                .chambers(chambers)
                .connectionPoints(input.getConnectionPoints())
                .restrictions(restrictions)
                .build();
        log.info("Область расчёта {}×{} м: ограничений {} из {}, участков сети {} из {}, камер {} из {}",
                Math.round(area.getWidth()), Math.round(area.getHeight()), restrictions.size(),
                input.getRestrictions().size(), pipes.size(), input.getPipes().size(), chambers.size(),
                input.getChambers().size());
        return new Cropped(cropped, area, input.getRestrictions().size(), restrictions.size(),
                input.getPipes().size(), pipes.size());
    }

    @SuppressWarnings("unchecked")
    private static double nearestPipeDistance(STRtree pipeIndex, ConnectionPoint cp) {
        Geometry p = cp.getGeometry();
        // расширяем окно поиска, пока не найдём хотя бы один участок
        double r = 200;
        for (int i = 0; i < 12; i++) {
            Envelope env = new Envelope(p.getCoordinate());
            env.expandBy(r);
            List<ExistingPipe> near = pipeIndex.query(env);
            if (!near.isEmpty()) {
                double best = Double.MAX_VALUE;
                for (ExistingPipe pipe : near) {
                    best = Math.min(best, pipe.getGeometry().distance(p));
                }
                if (best <= r) {
                    return best;
                }
            }
            r *= 2;
        }
        return r;
    }
}
