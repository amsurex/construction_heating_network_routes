package ru.lct.heat.routing.network;

import lombok.Getter;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heat.geometry.SegmentCheck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Строящаяся новая сеть: лес деревьев, каждое дерево растёт от корня (точки присоединения к
 * существующей сети) к листьям (точкам ОКС). Техприложение §2.1: замкнутых маршрутов нет.
 */
@Getter
public final class NewNetwork {

    private final Map<Object, NetNode> nodes = new LinkedHashMap<>();
    private final List<NetEdge> edges = new ArrayList<>();
    private int seq = 0;

    public NetNode addNode(Object id, Coordinate c, NetNode.Kind kind) {
        NetNode n = new NetNode(id, c, kind);
        nodes.put(id, n);
        return n;
    }

    /** Новый служебный узел с генерируемым id. */
    public NetNode addGeneratedNode(Coordinate c, NetNode.Kind kind) {
        return addNode("gen_" + (++seq), c, kind);
    }

    public NetNode node(Object id) {
        return nodes.get(id);
    }

    /** Удаляет узел вместе с его рёбрами. */
    public void removeNode(NetNode n) {
        for (NetEdge e : new ArrayList<>(n.getEdges())) {
            removeEdge(e);
        }
        nodes.remove(n.getId());
    }

    /** Глубокая копия сети (для отката локальных изменений). */
    public NewNetwork copy() {
        NewNetwork c = new NewNetwork();
        c.seq = seq;
        for (NetNode n : nodes.values()) {
            NetNode m = c.addNode(n.getId(), n.getCoord(), n.getKind());
            m.setTieInPipeId(n.getTieInPipeId());
            m.setExistingMaxDiameter(n.getExistingMaxDiameter());
            m.setApproachNote(n.getApproachNote());
        }
        for (NetEdge e : edges) {
            NetEdge ne = c.addEdge(c.node(e.getFrom().getId()), c.node(e.getTo().getId()), e.getCoords(), e.getChecks());
            ne.setFlowTph(e.getFlowTph());
            ne.setDiameter(e.getDiameter());
            ne.setLayingMethod(e.getLayingMethod());
            ne.setKSpecial(e.getKSpecial());
            ne.setCrossedTypes(e.getCrossedTypes());
            ne.setCreatorPointId(e.getCreatorPointId());
            ne.setKDepth(e.getKDepth());
            ne.setDepthStart(e.getDepthStart());
            ne.setDepthEnd(e.getDepthEnd());
        }
        return c;
    }

    /** Переносит узел в точку c, обновляя концы прилегающих рёбер (проверки рёбер пересчитывает вызывающий). */
    public void moveNode(NetNode n, Coordinate c) {
        for (NetEdge e : n.getEdges()) {
            List<Coordinate> cs = e.getCoords();
            if (e.getFrom() == n) {
                cs.set(0, c);
            }
            if (e.getTo() == n) {
                cs.set(cs.size() - 1, c);
            }
        }
        n.setCoord(c);
    }

    /** Ребро from→to по потоку (от корня к листу). */
    public NetEdge addEdge(NetNode from, NetNode to, List<Coordinate> coords, List<SegmentCheck> checks) {
        NetEdge e = new NetEdge(from, to, coords, checks);
        edges.add(e);
        from.getEdges().add(e);
        to.getEdges().add(e);
        return e;
    }

    public void removeEdge(NetEdge e) {
        edges.remove(e);
        e.getFrom().getEdges().remove(e);
        e.getTo().getEdges().remove(e);
    }

    public List<NetNode> roots() {
        return nodes.values().stream().filter(NetNode::isRoot).collect(Collectors.toList());
    }

    public List<NetNode> leaves() {
        return nodes.values().stream().filter(n -> n.getKind() == NetNode.Kind.OKS_POINT)
                .collect(Collectors.toList());
    }

    /** Исходящие (к листьям) рёбра узла. */
    public List<NetEdge> outgoing(NetNode n) {
        List<NetEdge> out = new ArrayList<>();
        for (NetEdge e : n.getEdges()) {
            if (e.getFrom() == n) {
                out.add(e);
            }
        }
        return out;
    }

    /** Входящее (от корня) ребро узла, либо null для корня. */
    public NetEdge incoming(NetNode n) {
        for (NetEdge e : n.getEdges()) {
            if (e.getTo() == n) {
                return e;
            }
        }
        return null;
    }

    /**
     * Делит ребро в точке j, лежащей на отрезке coords[segIdx]→coords[segIdx+1] (или совпадающей с
     * вершиной), и создаёт в ней узел kind. Возвращает новый узел. Расход/ДУ копируются в обе части.
     */
    public NetNode splitEdge(NetEdge e, int segIdx, Coordinate j, NetNode.Kind kind) {
        List<Coordinate> cs = e.getCoords();
        List<SegmentCheck> ch = e.getChecks();
        // совпадение с вершиной — не дублируем точку
        if (cs.get(segIdx).distance(j) < 1e-6) {
            if (segIdx == 0) {
                return e.getFrom();
            }
            return splitAtVertex(e, segIdx, kind);
        }
        if (cs.get(segIdx + 1).distance(j) < 1e-6) {
            if (segIdx + 1 == cs.size() - 1) {
                return e.getTo();
            }
            return splitAtVertex(e, segIdx + 1, kind);
        }
        NetNode node = addGeneratedNode(j, kind);
        List<Coordinate> c1 = new ArrayList<>(cs.subList(0, segIdx + 1));
        c1.add(j);
        List<Coordinate> c2 = new ArrayList<>();
        c2.add(j);
        c2.addAll(cs.subList(segIdx + 1, cs.size()));
        // проверка отрезка симметрична и не зависит от точки деления — переиспользуем
        List<SegmentCheck> ch1 = new ArrayList<>(ch.subList(0, segIdx + 1));
        List<SegmentCheck> ch2 = new ArrayList<>(ch.subList(segIdx, ch.size()));
        replaceWithTwo(e, node, c1, ch1, c2, ch2);
        return node;
    }

    private NetNode splitAtVertex(NetEdge e, int vertexIdx, NetNode.Kind kind) {
        List<Coordinate> cs = e.getCoords();
        List<SegmentCheck> ch = e.getChecks();
        NetNode node = addGeneratedNode(cs.get(vertexIdx), kind);
        List<Coordinate> c1 = new ArrayList<>(cs.subList(0, vertexIdx + 1));
        List<Coordinate> c2 = new ArrayList<>(cs.subList(vertexIdx, cs.size()));
        List<SegmentCheck> ch1 = new ArrayList<>(ch.subList(0, vertexIdx));
        List<SegmentCheck> ch2 = new ArrayList<>(ch.subList(vertexIdx, ch.size()));
        replaceWithTwo(e, node, c1, ch1, c2, ch2);
        return node;
    }

    private void replaceWithTwo(NetEdge e, NetNode mid, List<Coordinate> c1, List<SegmentCheck> ch1,
                                List<Coordinate> c2, List<SegmentCheck> ch2) {
        removeEdge(e);
        NetEdge e1 = addEdge(e.getFrom(), mid, c1, ch1);
        NetEdge e2 = addEdge(mid, e.getTo(), c2, ch2);
        e1.setFlowTph(e.getFlowTph());
        e2.setFlowTph(e.getFlowTph());
        e1.setDiameter(e.getDiameter());
        e2.setDiameter(e.getDiameter());
        e1.setLayingMethod(e.getLayingMethod());
        e2.setLayingMethod(e.getLayingMethod());
        e1.setKSpecial(e.getKSpecial());
        e2.setKSpecial(e.getKSpecial());
        e1.setCrossedTypes(e.getCrossedTypes());
        e2.setCrossedTypes(e.getCrossedTypes());
        e1.setCreatorPointId(e.getCreatorPointId());
        e2.setCreatorPointId(e.getCreatorPointId());
        e1.setKDepth(e.getKDepth());
        e2.setKDepth(e.getKDepth());
    }

    public double totalLength() {
        return edges.stream().mapToDouble(NetEdge::length).sum();
    }

    public List<NetNode> nodeList() {
        return Collections.unmodifiableList(new ArrayList<>(nodes.values()));
    }
}
