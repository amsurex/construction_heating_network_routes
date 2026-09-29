package ru.lct.heat.validation;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heat.model.*;
import ru.lct.heat.model.out.*;
import ru.lct.heat.model.ref.*;
import java.util.*;
import static ru.lct.heat.validation.InputValidator.key;
import static ru.lct.heat.validation.ValidationChecks.GEOMETRY_EPS;

/** §2.1–2.5, §3.2, §6: граф строится заново по ссылкам выходного файла. */
final class OutputGraphChecks {
    final Map<Object, Point> nodes = new HashMap<>();
    final Set<Object> chamberIds = new HashSet<>();
    final Set<Object> roots = new HashSet<>();
    final Map<Object, List<NewPipe>> adjacent = new HashMap<>();
    final STRtree existing = new STRtree();
    private final InputModel input;
    private final Variant variant;
    private final ValidationChecks checks;
    private final Map<Object, ConnectionPoint> points = new HashMap<>();
    private final Set<Object> existingChambers = new HashSet<>();
    private final Map<Object, NewPipe> parentEdge = new HashMap<>();
    private final Map<Object, Object> parent = new HashMap<>();
    private final Set<Object> reachable = new HashSet<>();
    private final Map<NewPipe, Double> longestRun = new HashMap<>();
    private double chamberCost;
    private int tieIns;

    OutputGraphChecks(InputModel input, Variant variant, ValidationChecks checks) {
        this.input = input; this.variant = variant; this.checks = checks;
        input.getPipes().forEach(p -> existing.insert(p.getGeometry().getEnvelopeInternal(), p));
        existing.build();
        for (ConnectionPoint p : input.getConnectionPoints()) {
            nodes.put(key(p.getId()), p.getGeometry()); points.put(key(p.getId()), p);
        }
        for (ExistingChamber c : input.getChambers()) {
            nodes.put(key(c.getId()), c.getGeometry());
            chamberIds.add(key(c.getId())); existingChambers.add(key(c.getId()));
        }
    }

    void check() {
        Set<Object> inputIds = new HashSet<>(nodes.keySet());
        input.getSources().forEach(p -> inputIds.add(key(p.getId())));
        input.getPipes().forEach(p -> inputIds.add(key(p.getId())));
        input.getRestrictions().forEach(p -> inputIds.add(key(p.getId())));
        for (NewChamber c : variant.getChambers()) {
            checks.require(!inputIds.contains(key(c.getId())), c.getId(), "§7", "Новый id совпадает с исходным");
            nodes.put(key(c.getId()), c.getGeometry()); chamberIds.add(key(c.getId()));
        }
        for (TechnicalNode n : variant.getTechnicalNodes()) {
            checks.require(!inputIds.contains(key(n.getId())), n.getId(), "§7", "Новый id совпадает с исходным");
            nodes.put(key(n.getId()), n.getGeometry());
        }
        for (NewPipe p : variant.getPipes()) {
            checks.require(!inputIds.contains(key(p.getId())), p.getId(), "§7", "Новый id совпадает с исходным");
            endpoint(p, p.getStartNodeId(), p.getGeometry().getStartPoint());
            endpoint(p, p.getEndNodeId(), p.getGeometry().getEndPoint());
            adjacent.computeIfAbsent(key(p.getStartNodeId()), k -> new ArrayList<>()).add(p);
            adjacent.computeIfAbsent(key(p.getEndNodeId()), k -> new ArrayList<>()).add(p);
        }
        chambers();
        for (Map.Entry<Object, List<NewPipe>> entry : adjacent.entrySet()) {
            checks.require(entry.getValue().size() <= 2 || chamberIds.contains(entry.getKey()), entry.getKey(),
                    "§2.1", "Разветвление вне камеры");
            if (points.containsKey(entry.getKey())) {
                checks.require(entry.getValue().size() == 1, entry.getKey(), "§2.1", "ОКС должен быть листом дерева");
            }
        }
        for (TechnicalNode node : variant.getTechnicalNodes()) {
            List<NewPipe> edges = adjacent.getOrDefault(key(node.getId()), List.of());
            checks.require(edges.size() == 2, node.getId(), "§2.1", "Техузел должен соединять два участка");
            if (edges.size() == 2) {
                NewPipe a = edges.get(0), b = edges.get(1);
                boolean change = a.getDiameter() != b.getDiameter() || a.getLayingMethod() != b.getLayingMethod()
                        || !Objects.equals(a.getDepthStart(), b.getDepthStart())
                        || !Objects.equals(a.getDepthEnd(), b.getDepthEnd())
                        || a.getKSpecial() != b.getKSpecial();
                // Смена набора наложенных спецпроходов при одинаковом max(K) также допустима (§4).
                checks.require(change || a.getLayingMethod() == LayingMethod.SPECIAL, node.getId(), "§2.1",
                        "Техузел без изменения параметров");
            }
        }
        forest();
        paths();
        summary();
    }

    private void endpoint(NewPipe pipe, Object id, Point endpoint) {
        Point point = nodes.get(key(id));
        checks.require(point != null && point.distance(endpoint) <= GEOMETRY_EPS, pipe.getId(), "§7.2",
                "Конец участка не совпадает с узлом " + id);
    }

    @SuppressWarnings("unchecked")
    List<ExistingPipe> at(Point point) {
        Envelope envelope = new Envelope(point.getEnvelopeInternal()); envelope.expandBy(GEOMETRY_EPS);
        List<ExistingPipe> result = new ArrayList<>();
        for (ExistingPipe pipe : (List<ExistingPipe>) existing.query(envelope)) {
            if (pipe.getGeometry().distance(point) <= GEOMETRY_EPS) { result.add(pipe); }
        }
        return result;
    }

    private void chambers() {
        STRtree chamberIndex = new STRtree();
        input.getChambers().forEach(c -> chamberIndex.insert(c.getGeometry().getEnvelopeInternal(), c));
        chamberIndex.build();
        for (Object id : chamberIds) {
            Point point = nodes.get(id);
            List<ExistingPipe> old = at(point);
            int degree = adjacent.getOrDefault(id, List.of()).size();
            if (!old.isEmpty()) { roots.add(id); }
            int oldDegree = 0;
            for (ExistingPipe pipe : old) {
                LineString g = pipe.getGeometry();
                oldDegree += g.getStartPoint().distance(point) <= GEOMETRY_EPS
                        || g.getEndPoint().distance(point) <= GEOMETRY_EPS ? 1 : 2;
            }
            checks.require(degree + oldDegree <= CostConstants.MAX_CHAMBER_CONNECTIONS, id, "§2.1",
                    "Более четырёх примыканий с учётом существующей сети");
            if (existingChambers.contains(id)) { tieIns += degree; }
        }
        for (NewChamber c : variant.getChambers()) {
            Object id = key(c.getId());
            List<ExistingPipe> old = at(c.getGeometry());
            int maxDn = adjacent.getOrDefault(id, List.of()).stream().mapToInt(NewPipe::getDiameter).max().orElse(0);
            for (ExistingPipe pipe : old) { maxDn = Math.max(maxDn, pipe.getDiameter()); }
            checks.require(maxDn > 0 && c.getDiameter() == maxDn, c.getId(), "§3.2", "ДУ камеры не равен max ДУ");
            checks.close(c.getCost(), ChamberCostTable.newChamberCost(maxDn), 0.01, c.getId(), "§3.2", "cost");
            chamberCost += c.getCost();
            if (c.getTieInPipeId() != null) {
                checks.require(old.stream().anyMatch(p -> key(p.getId()).equals(key(c.getTieInPipeId()))), c.getId(),
                        "§2.4", "Камера врезки не лежит на указанной существующей трубе");
            }
            if (!old.isEmpty()) {
                Envelope search = new Envelope(c.getGeometry().getEnvelopeInternal());
                search.expandBy(CostConstants.EXISTING_CHAMBER_SNAP_M);
                for (Object candidate : chamberIndex.query(search)) {
                    ExistingChamber e = (ExistingChamber) candidate;
                    if (c.getGeometry().distance(e.getGeometry()) > CostConstants.EXISTING_CHAMBER_SNAP_M) { continue; }
                    int degree = adjacent.getOrDefault(key(e.getId()), List.of()).size();
                    for (ExistingPipe p : at(e.getGeometry())) {
                        degree += p.getGeometry().getStartPoint().distance(e.getGeometry()) <= GEOMETRY_EPS
                                || p.getGeometry().getEndPoint().distance(e.getGeometry()) <= GEOMETRY_EPS ? 1 : 2;
                    }
                    checks.require(degree + adjacent.getOrDefault(id, List.of()).size()
                            > CostConstants.MAX_CHAMBER_CONNECTIONS, c.getId(), "§2.4",
                            "Новая камера при доступной существующей камере в радиусе 10 м");
                }
            }
        }
    }

    private void forest() {
        Set<Object> visited = new HashSet<>();
        for (Object start : adjacent.keySet()) {
            if (!visited.add(start)) { continue; }
            List<Object> component = new ArrayList<>();
            Deque<Object> queue = new ArrayDeque<>(); queue.add(start);
            int degrees = 0, rootCount = 0; Object root = null;
            while (!queue.isEmpty()) {
                Object node = queue.remove(); component.add(node);
                if (roots.contains(node)) { rootCount++; root = node; }
                for (NewPipe edge : adjacent.getOrDefault(node, List.of())) {
                    degrees++; Object other = other(edge, node);
                    if (visited.add(other)) { queue.add(other); }
                }
            }
            checks.require(degrees / 2 == component.size() - 1, start, "§2.1", "Цикл в новой сети");
            checks.require(rootCount == 1, start, "§2.1/§2.4", "Компонента должна иметь одну врезку, найдено " + rootCount);
            if (rootCount != 1 || degrees / 2 != component.size() - 1) { continue; }
            List<Object> order = new ArrayList<>(); queue.add(root); reachable.add(root);
            while (!queue.isEmpty()) {
                Object node = queue.remove(); order.add(node);
                for (NewPipe edge : adjacent.getOrDefault(node, List.of())) {
                    Object child = other(edge, node);
                    if (!reachable.add(child)) { continue; }
                    parent.put(child, node); parentEdge.put(child, edge); queue.add(child);
                }
            }
            Map<Object, Double> flow = new HashMap<>();
            Collections.reverse(order);
            for (Object node : order) {
                double total = flow.getOrDefault(node, 0.0) + (points.containsKey(node) ? points.get(node).getFlowTph() : 0);
                if (parent.containsKey(node)) {
                    NewPipe edge = parentEdge.get(node);
                    checks.close(edge.getFlowTph(), total, 0.0051, edge.getId(), "§2.3", "flow_tph");
                    flow.merge(parent.get(node), total, Double::sum);
                }
            }
        }
    }

    private void paths() {
        // Обход каждого пути обязателен по §2.3: общий участок входит в каждый путь, ветви не суммируются.
        for (Object point : points.keySet()) {
            List<NewPipe> path = new ArrayList<>(); Object node = point;
            while (parent.containsKey(node)) { path.add(parentEdge.get(node)); node = parent.get(node); }
            int previousDn = 0, first = 0;
            while (first < path.size()) {
                int last = first; int dn = path.get(first).getDiameter(); double length = 0;
                checks.require(dn >= previousDn, path.get(first).getId(), "§2.3", "ДУ уменьшается к врезке");
                while (last < path.size() && path.get(last).getDiameter() == dn) {
                    length += path.get(last).getGeometry().getLength(); last++;
                }
                final double runLength = length;
                DiameterTable.byDiameter(dn).ifPresent(spec -> checks.require(runLength <= spec.getMaxLengthM() + GEOMETRY_EPS,
                        point, "§2.3", "Превышена предельная длина непрерывного пути ДУ" + dn));
                for (int i = first; i < last; i++) { longestRun.merge(path.get(i), length, Math::max); }
                previousDn = dn; first = last;
            }
            for (int i = 1; i < path.size(); i++) {
                NewPipe a = path.get(i - 1), b = path.get(i);
                checks.require(Math.abs(a.getFlowTph() - b.getFlowTph()) > 1e-6 || a.getDiameter() == b.getDiameter(),
                        b.getId(), "§2.3", "Смена ДУ без изменения расхода");
            }
        }
        for (NewPipe pipe : variant.getPipes()) {
            Optional<DiameterSpec> min = DiameterTable.minByFlow(pipe.getFlowTph());
            if (min.isEmpty()) { checks.require(false, pipe.getId(), "§2.3", "Расход выше таблицы ДУ"); continue; }
            DiameterSpec base = min.get();
            checks.require(pipe.getDiameter() >= base.getDiameter(), pipe.getId(), "§2.3", "ДУ меньше минимального по расходу");
            // Разъяснение оргов п. 20: если минимальный по расходу ДУ не проходит по предельной длине,
            // для всей части с неизменным расходом берётся следующий минимальный ДУ, удовлетворяющий
            // обоим условиям, — без ограничения «не более одной номенклатуры». Проверяем не число
            // ступеней, а минимальность: предыдущий ДУ должен не проходить по длине.
            if (pipe.getDiameter() > base.getDiameter()) {
                DiameterTable.byDiameter(pipe.getDiameter())
                        .flatMap(DiameterTable::previous)
                        .filter(prev -> prev.getDiameter() >= base.getDiameter())
                        .ifPresent(prev -> {
                            if (longestRun.getOrDefault(pipe, 0.0) <= prev.getMaxLengthM() + GEOMETRY_EPS) {
                                checks.warn(pipe.getId(), "§2.3", "ДУ" + pipe.getDiameter()
                                        + " может быть избыточным: ДУ" + prev.getDiameter() + " проходит и по расходу,"
                                        + " и по предельной длине на этом пути (" + Math.round(longestRun.getOrDefault(pipe, 0.0))
                                        + " м при пределе " + Math.round(prev.getMaxLengthM()) + " м)");
                            }
                        });
            }
            if (pipe.getDiameter() > base.getDiameter()) {
                boolean downstreamLarger = false;
                for (Object id : List.of(key(pipe.getStartNodeId()), key(pipe.getEndNodeId()))) {
                    for (NewPipe next : adjacent.getOrDefault(id, List.of())) {
                        if (next != pipe && next.getFlowTph() < pipe.getFlowTph()
                                && next.getDiameter() >= pipe.getDiameter()) { downstreamLarger = true; }
                    }
                }
                checks.require(longestRun.getOrDefault(pipe, 0.0) > base.getMaxLengthM() - GEOMETRY_EPS || downstreamLarger,
                        pipe.getId(), "§2.3", "Произвольное завышение ДУ");
            }
        }
    }

    private void summary() {
        VariantSummary s = variant.getSummary();
        Set<Object> unconnected = new HashSet<>(); double penalty = 0;
        for (Object id : s.getUnconnectedOksIds()) {
            Object k = key(id);
            checks.require(points.containsKey(k) && unconnected.add(k), id, "§2.5/§7", "Неизвестный/повторный неподключённый ОКС");
            if (points.containsKey(k)) { penalty += CostConstants.unconnectedPenalty(points.get(k).getFlowTph()); }
        }
        for (Object id : points.keySet()) {
            checks.require(reachable.contains(id) != unconnected.contains(id), id, "§2.5/§7",
                    "ОКС должен быть либо подключён, либо указан в unconnected_oks_ids");
        }
        double pipeCost = variant.getPipes().stream().mapToDouble(NewPipe::getCost).sum();
        double length = variant.getPipes().stream().mapToDouble(NewPipe::getLength).sum();
        checks.close(s.getChamberConstructionCost(), chamberCost, 0.01, s.getId(), "§6", "chamber_construction_cost");
        checks.close(s.getExistingChamberTieInCount(), tieIns, 0, s.getId(), "§3.2", "existing_chamber_tie_in_count");
        checks.close(s.getExistingChamberTieInCost(), tieIns * ChamberCostTable.TIE_IN_COST, 0.01,
                s.getId(), "§3.2", "existing_chamber_tie_in_cost");
        double construction = pipeCost + chamberCost + tieIns * ChamberCostTable.TIE_IN_COST;
        checks.close(s.getConstructionCost(), construction, 0.01, s.getId(), "§6", "construction_cost");
        checks.close(s.getUnconnectedPenalty(), penalty, 0.01, s.getId(), "§6", "unconnected_penalty");
        checks.close(s.getCalculatedCost(), construction + penalty, 0.01, s.getId(), "§6", "calculated_cost");
        checks.close(s.getNewNetworkLength(), length, 0.01, s.getId(), "§6", "new_network_length");
        checks.close(s.getScore(), CostConstants.score(construction + penalty, length), 0.000051, s.getId(), "§6", "score");
    }

    private Object other(NewPipe edge, Object node) {
        return key(edge.getStartNodeId()).equals(node) ? key(edge.getEndNodeId()) : key(edge.getStartNodeId());
    }
}
