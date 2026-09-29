package ru.lct.heat.routing;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.ObstacleMap;
import ru.lct.heat.geometry.SegmentCheck;
import ru.lct.heat.model.ref.CostConstants;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Внутренняя проверка построенной сети по правилам Техприложения после финального подбора ДУ
 * (габарит и отступ от ОКС зависят от ДУ, а трасса искалась при меньшем). Полная независимая
 * проверка выходного GeoJSON — validation/OutputValidator (платформа).
 */
@Slf4j
public final class NetworkChecker {

    /** Допуск совпадения точки пересечения с узлом, м. */
    private static final double TOUCH_TOL = 1e-6;

    @Value
    public static class Violation {
        NetEdge edge;
        int segIdx;
        String message;
    }

    private final MapCache maps;
    private final ApproachPlanner approach;

    public NetworkChecker(MapCache maps, ApproachPlanner approach) {
        this.maps = maps;
        this.approach = approach;
    }

    /**
     * Два участка могут касаться только в общем узле (§2.1). Проверяем так же, как выходной валидатор:
     * пересечение нульмерно и каждая его точка совпадает с общим узлом рёбер. {@code LineString.crosses}
     * для этого не годится — он даёт false, когда среди точек пересечения есть общая конечная.
     */
    private static boolean sharesOnlyCommonNodes(LineString la, LineString lb, NetEdge ea, NetEdge eb) {
        if (!la.intersects(lb)) {
            return true;
        }
        Geometry inter = la.intersection(lb);
        if (inter.isEmpty()) {
            return true;
        }
        if (inter.getDimension() > 0) {
            return false;
        }
        for (Coordinate c : inter.getCoordinates()) {
            boolean atNode = false;
            for (NetNode n : new NetNode[]{ea.getFrom(), ea.getTo()}) {
                if ((n == eb.getFrom() || n == eb.getTo()) && n.getCoord().distance(c) <= TOUCH_TOL) {
                    atNode = true;
                    break;
                }
            }
            if (!atNode) {
                return false;
            }
        }
        return true;
    }

    /**
     * Только пересечения новых участков вне общих узлов (§2.1). Отдельно от {@link #check}, потому что
     * после спецразбиения отрезки законно начинаются и заканчиваются внутри спецзон — полная проверка
     * там даёт ложные срабатывания, а пересечения проверять нужно до самого конца.
     */
    public List<Violation> crossings(NewNetwork net) {
        List<Violation> out = new ArrayList<>();
        List<NetEdge> edges = net.getEdges();
        for (int a = 0; a < edges.size(); a++) {
            LineString la = GeomUtil.line(edges.get(a).getCoords());
            for (int b = a + 1; b < edges.size(); b++) {
                NetEdge eb = edges.get(b);
                LineString lb = GeomUtil.line(eb.getCoords());
                if (!la.getEnvelopeInternal().intersects(lb.getEnvelopeInternal())) {
                    continue;
                }
                if (!sharesOnlyCommonNodes(la, lb, edges.get(a), eb)) {
                    out.add(new Violation(edges.get(a), -1, "пересекает участок " + eb.getFrom() + "→" + eb.getTo()));
                }
            }
        }
        return out;
    }

    public List<Violation> check(NewNetwork net) {
        List<Violation> out = new ArrayList<>();
        for (NetEdge e : net.getEdges()) {
            ObstacleMap map = maps.map(e.getDiameter());
            List<Coordinate> cs = e.getCoords();
            boolean leaf = e.getTo().getKind() == NetNode.Kind.OKS_POINT;
            Set<Object> leafExempt = Collections.emptySet();
            if (leaf) {
                leafExempt = approach.exemptIds(e.getTo().getId());
            }
            for (int i = 0; i + 1 < cs.size(); i++) {
                boolean last = i == cs.size() - 2;
                Set<Object> exempt = leaf && last ? leafExempt : Collections.emptySet();
                SegmentCheck chk = map.check(cs.get(i), cs.get(i + 1), exempt, leaf && last);
                if (!chk.isFree()) {
                    out.add(new Violation(e, i, "ДУ" + e.getDiameter().getDiameter() + ": " + chk.getReason()
                            + " (id=" + chk.getBlockedBy().getId() + ")"));
                }
                if (i >= 1) {
                    double turn = GeomUtil.turnAngleDeg(cs.get(i - 1), cs.get(i), cs.get(i + 1));
                    if (turn > CostConstants.MAX_TURN_ANGLE_DEG + PathFinder.ANGLE_TOL_DEG) {
                        out.add(new Violation(e, i, "поворот " + Math.round(turn) + "° > 90°"));
                    }
                }
            }
        }
        out.addAll(crossings(net));
        List<NetEdge> edges = net.getEdges();
        for (NetNode n : net.nodeList()) {
            // камера на существующем участке делит его на две части — два примыкания уже заняты
            int existing = n.getKind() == NetNode.Kind.TIE_IN_CHAMBER ? 2 : 0;
            if ((n.getKind() == NetNode.Kind.BRANCH_CHAMBER || n.getKind() == NetNode.Kind.TIE_IN_CHAMBER)
                    && n.degree() + existing > CostConstants.MAX_CHAMBER_CONNECTIONS) {
                out.add(new Violation(null, -1, "камера " + n + ": " + (n.degree() + existing) + " примыканий"));
            }
        }
        if (out.isEmpty()) {
            log.info("Проверка сети: нарушений нет ({} участков)", edges.size());
        } else {
            for (Violation v : out) {
                log.warn("Нарушение: {} {}", v.getEdge() == null ? "" : v.getEdge().getFrom() + "→" + v.getEdge().getTo(),
                        v.getMessage());
            }
        }
        return out;
    }
}
