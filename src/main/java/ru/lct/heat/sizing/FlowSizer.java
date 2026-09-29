package ru.lct.heat.sizing;

import lombok.extern.slf4j.Slf4j;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ref.DiameterSpec;
import ru.lct.heat.model.ref.DiameterTable;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Расходы и условные диаметры новой сети (Техприложение §2.3, FAQ п.1–2).
 * <ol>
 *   <li>Расход ребра = сумма flow_tph всех точек ОКС ниже по дереву.</li>
 *   <li>ДУ = минимальный по расходу (табл. 1).</li>
 *   <li>Предельная длина проверяется по каждому пути лист→корень отдельно; непрерывная часть одного
 *       ДУ (общие участки входят в каждый путь) не должна превышать предельную длину. Нарушение —
 *       поднимаем ДУ <b>всей</b> непрерывной части на следующую номенклатуру и повторяем до сходимости
 *       (разъяснение оргов п. 20: для всей части с неизменным расходом берётся следующий минимальный
 *       ДУ, удовлетворяющий обоим условиям; поднимать ДУ на одном коротком участке ради нового
 *       отсчёта предельной длины нельзя).</li>
 *   <li>От листа к корню ДУ не убывает.</li>
 * </ol>
 */
@Slf4j
public final class FlowSizer {

    private static final int MAX_LIMIT_ITERATIONS = 20;

    private final Map<Object, ConnectionPoint> pointsById;

    public FlowSizer(Map<Object, ConnectionPoint> pointsById) {
        this.pointsById = pointsById;
    }

    public void size(NewNetwork net) {
        for (NetNode root : net.roots()) {
            for (NetEdge e : net.outgoing(root)) {
                accumulateFlow(net, e);
            }
        }
        for (NetEdge e : net.getEdges()) {
            DiameterSpec dn = DiameterTable.minByFlow(e.getFlowTph()).orElseGet(() -> {
                log.warn("Расход {} т/ч превышает пропускную способность ДУ1400", e.getFlowTph());
                return DiameterTable.rows().get(DiameterTable.rows().size() - 1);
            });
            e.setDiameter(dn);
        }
        // предельная длина (Техприложение §2.3: ДУ — минимальный, удовлетворяющий и расходу, и предельной длине):
        // подъём ДУ повторяем до сходимости — после подъёма участки одного ДУ сливаются в более длинные
        // непрерывные части, и их тоже может понадобиться поднять (длинные магистрали с малыми расходами)
        int iter = 0;
        while (iter < MAX_LIMIT_ITERATIONS && fixLengthLimits(net)) {
            enforceMonotonic(net);
            iter++;
        }
        if (iter == MAX_LIMIT_ITERATIONS) {
            log.warn("Подбор ДУ по предельной длине не сошёлся за {} итераций", iter);
        }
    }

    private double accumulateFlow(NewNetwork net, NetEdge e) {
        NetNode to = e.getTo();
        double flow = 0;
        if (to.getKind() == NetNode.Kind.OKS_POINT) {
            ConnectionPoint cp = pointsById.get(to.getId());
            flow += cp != null ? cp.getFlowTph() : 0;
        }
        for (NetEdge child : net.outgoing(to)) {
            flow += accumulateFlow(net, child);
        }
        e.setFlowTph(flow);
        return flow;
    }

    /** @return true если хотя бы один ДУ был поднят */
    private boolean fixLengthLimits(NewNetwork net) {
        boolean changed = false;
        for (NetNode leaf : net.leaves()) {
            List<NetEdge> path = pathToRoot(net, leaf);
            // разбиваем путь на непрерывные части одного ДУ
            int i = 0;
            while (i < path.size()) {
                DiameterSpec dn = path.get(i).getDiameter();
                int j = i;
                double run = 0;
                while (j < path.size() && path.get(j).getDiameter() == dn) {
                    run += path.get(j).length();
                    j++;
                }
                if (run > dn.getMaxLengthM() + 1e-6) {
                    Optional<DiameterSpec> next = DiameterTable.next(dn);
                    if (next.isPresent()) {
                        log.debug("Путь от {}: часть ДУ{} длиной {} м > {} м → ДУ{}", leaf.getId(),
                                dn.getDiameter(), Math.round(run), Math.round(dn.getMaxLengthM()),
                                next.get().getDiameter());
                        for (int k = i; k < j; k++) {
                            path.get(k).setDiameter(next.get());
                        }
                        changed = true;
                    } else {
                        log.warn("Путь от {}: превышена предельная длина для максимального ДУ", leaf.getId());
                    }
                }
                i = j;
            }
        }
        return changed;
    }

    /** ДУ не убывает от листа к корню: родитель ≥ максимума детей. */
    private void enforceMonotonic(NewNetwork net) {
        for (NetNode root : net.roots()) {
            for (NetEdge e : net.outgoing(root)) {
                liftToChildren(net, e);
            }
        }
    }

    private DiameterSpec liftToChildren(NewNetwork net, NetEdge e) {
        DiameterSpec max = e.getDiameter();
        for (NetEdge child : net.outgoing(e.getTo())) {
            DiameterSpec c = liftToChildren(net, child);
            if (c.getDiameter() > max.getDiameter()) {
                max = c;
            }
        }
        e.setDiameter(max);
        return max;
    }

    /** Рёбра от листа к корню (первое — примыкающее к листу). */
    public static List<NetEdge> pathToRoot(NewNetwork net, NetNode leaf) {
        List<NetEdge> path = new ArrayList<>();
        NetNode n = leaf;
        NetEdge in;
        while ((in = net.incoming(n)) != null) {
            path.add(in);
            n = in.getFrom();
        }
        return path;
    }
}
