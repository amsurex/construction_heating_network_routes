package ru.lct.heat.depth;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heat.geometry.GeomUtil;
import ru.lct.heat.geometry.Obstacle;
import ru.lct.heat.geometry.SegmentCheck;
import ru.lct.heat.model.ref.CostConstants;
import ru.lct.heat.model.ref.RestrictionRule;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Режим с учётом глубины (Техприложение §5, приложение «Трассировка с учётом глубины»).
 *
 * Поверхность земли горизонтальная; глубина — до верха расчётного габарита новой сети.
 * Обычная глубина {@link CostConstants#DEFAULT_DEPTH_M}, минимальная {@link CostConstants#MIN_DEPTH_M},
 * уклон не более {@link CostConstants#MAX_SLOPE}. На пересечении подземной коммуникации (газ, кабель,
 * существующая теплосеть) новая сеть проходит выше или ниже неё с вертикальным просветом; под дорогой и
 * трамваем — не мельче заданной глубины. Kгл = 1 при h ≤ 3 м, иначе 1 + 0.1·(h − 3), поэтому при прочих
 * равных выбирается проход <b>выше</b> коммуникации (дешевле); ниже — только если выше не вписывается
 * в минимальную глубину.
 *
 * Профиль строится по цепочкам рёбер (между камерами) как огибающая требований:
 * h(s) = max( min(3, min_i(top_i + 0.1·dist(s, I_i))), max_j(bottom_j − 0.1·dist(s, I_j)) ).
 * Это даёт участки спуска/подъёма с уклоном ровно 0.1, объединяет близкие пересечения без возврата
 * на 3 м между ними и минимально отклоняется от обычной глубины. Глубина на конце цепочки передаётся
 * следующим цепочкам как граничное условие. На переломах профиля и на пересечении отметки 3.0 м
 * создаются технические узлы.
 */
@Slf4j
public final class DepthPlanner {

    private static final double EPS = 1e-6;
    /**
     * Минимальный кусок при разбиении по глубине, м — как в {@link ru.lct.heat.routing.SpecialSplitter}.
     * Было 0.1: перелом профиля у самого конца участка оставлял обрезок в 20–40 см, который наследовал
     * Kспец родителя, хотя его собственная геометрия этот коэффициент уже не подтверждает.
     */
    private static final double MIN_PIECE_M = 1.0;
    /** Запас по вертикальному просвету, м (округление глубин в выходе). */
    private static final double DEPTH_MARGIN_M = 0.01;
    private static final double BASE = CostConstants.DEFAULT_DEPTH_M;
    private static final double SLOPE = CostConstants.MAX_SLOPE;

    /** Требование к глубине на интервале цепочки [s0, s1]: h ≤ maxTop (проход сверху), h ≥ minTop (снизу). */
    @Value
    static class Requirement {
        double s0;
        double s1;
        double maxTop;
        double minTop;
        String type;

        /** Точечное граничное условие h(s) = depth. */
        static Requirement fixed(double s, double depth) {
            return new Requirement(s, s, depth, depth, "boundary");
        }
    }

    public void plan(NewNetwork net) {
        int techNodes = 0;
        for (NetNode root : net.roots()) {
            List<NetEdge> out = net.outgoing(root);
            // глубина у камеры врезки не фиксирована (камера любой глубины): пересечения сразу за камерой
            // задают отметку старта, иначе — обычные 3.0 м; у всех ветвей из одной камеры она общая
            Double start = null;
            if (out.size() > 1) {
                double upper = BASE;
                double lower = 0;
                for (NetEdge e : out) {
                    double[] b = boundsAtStart(net, e);
                    upper = Math.min(upper, b[0]);
                    lower = Math.max(lower, b[1]);
                }
                start = Math.max(upper, lower);
            }
            for (NetEdge e : out) {
                techNodes += planFrom(net, e, start);
            }
        }
        log.info("Профиль глубины построен, технических узлов по глубине: {}", techNodes);
    }

    /** Цепочка от ребра first с заданной глубиной в её начале; затем рекурсивно ветви из конечного узла. */
    private int planFrom(NewNetwork net, NetEdge first, Double startDepth) {
        List<NetEdge> chain = chainFrom(net, first);
        NetNode end = chain.get(chain.size() - 1).getTo();
        double[] endDepth = new double[1];
        int created = planChain(net, chain, startDepth, endDepth);
        for (NetEdge next : net.outgoing(end)) {
            created += planFrom(net, next, endDepth[0]);
        }
        return created;
    }

    /**
     * Границы глубины в начале цепочки, продиктованные её собственными пересечениями и всем поддеревом ниже
     * (снизу вверх): {верхняя граница, нижняя граница}. Так конец родительской цепочки (камера) заранее
     * опускается/поднимается к требованию первого пересечения за камерой — иначе уклон 0.1 не успевает.
     */
    private double[] boundsAtStart(NewNetwork net, NetEdge first) {
        List<NetEdge> chain = chainFrom(net, first);
        List<Requirement> reqs = requirements(chain, null);
        double total = 0;
        for (NetEdge e : chain) {
            total += e.length();
        }
        NetNode end = chain.get(chain.size() - 1).getTo();
        for (NetEdge next : net.outgoing(end)) {
            double[] b = boundsAtStart(net, next);
            reqs.add(new Requirement(total, total, b[0], b[1], "downstream"));
        }
        return new double[]{upperAt(0, reqs), lowerAt(0, reqs)};
    }

    /** Требования по пересечениям цепочки (+ граничное условие в начале, если задано). */
    private static List<Requirement> requirements(List<NetEdge> chain, Double startDepth) {
        List<Requirement> reqs = new ArrayList<>();
        if (startDepth != null) {
            reqs.add(Requirement.fixed(0, startDepth));
        }
        // первый проход — «под объектом» (дорога, трамвай): это жёсткая нижняя граница глубины
        double pos = 0;
        for (NetEdge e : chain) {
            double len = e.length();
            for (Obstacle o : crossedTyped(e)) {
                if (o.getRule().isCrossUnderOnly()) {
                    Requirement r = requirement(o.getRule(), o.getType(), pos, pos + len,
                            e.getDiameter().getHeightM(), 0);
                    if (r != null) {
                        reqs.add(r);
                    }
                }
            }
            pos += len;
        }
        // второй проход — коммуникации: сверху идём, только если это не мельче нижней границы,
        // которую уже задали проходы под дорогой на том же месте. Иначе над кабелем и под дорогой
        // одновременно не получается, и правильный ответ — идти под кабелем (§5)
        pos = 0;
        for (NetEdge e : chain) {
            double len = e.length();
            double newHeight = e.getDiameter().getHeightM();
            for (Obstacle o : crossedTyped(e)) {
                if (o.getRule().isCrossUnderOnly()) {
                    continue;
                }
                Requirement r = requirement(o.getRule(), o.getType(), pos, pos + len, newHeight,
                        lowerBound(reqs, pos, pos + len));
                if (r != null) {
                    reqs.add(r);
                }
            }
            pos += len;
        }
        return reqs;
    }

    /** Рёбра подряд через технические узлы с одним выходом. */
    private static List<NetEdge> chainFrom(NewNetwork net, NetEdge first) {
        List<NetEdge> chain = new ArrayList<>();
        NetEdge cur = first;
        while (true) {
            chain.add(cur);
            NetNode to = cur.getTo();
            List<NetEdge> out = net.outgoing(to);
            if (to.getKind() == NetNode.Kind.TECH_NODE && out.size() == 1) {
                cur = out.get(0);
            } else {
                return chain;
            }
        }
    }

    private int planChain(NewNetwork net, List<NetEdge> chain, Double startDepth, double[] endDepthOut) {
        List<Requirement> reqs = requirements(chain, startDepth);
        double total = 0;
        for (NetEdge e : chain) {
            total += e.length();
        }
        // конец цепочки — с учётом требований поддерева за узлом (первое пересечение сразу за камерой)
        NetNode end = chain.get(chain.size() - 1).getTo();
        for (NetEdge next : net.outgoing(end)) {
            double[] b = boundsAtStart(net, next);
            reqs.add(new Requirement(total, total, b[0], b[1], "downstream"));
        }

        TreeSet<Double> mandatory = new TreeSet<>();
        List<Double> breaks = breakpoints(reqs, total, mandatory);
        verify(reqs);
        List<NetEdge> newChain = splitChain(net, chain, breaks, mandatory);
        double pos = 0;
        for (NetEdge e : newChain) {
            double len = e.length();
            double h0 = profile(pos, reqs);
            double h1 = profile(pos + len, reqs);
            e.setDepthStart(h0);
            e.setDepthEnd(h1);
            e.setKDepth((CostConstants.depthFactor(h0) + CostConstants.depthFactor(h1)) / 2.0);
            pos += len;
        }
        endDepthOut[0] = profile(total, reqs);
        return newChain.size() - chain.size();
    }

    /** Точки перелома профиля (без концов цепочки), где меняется уклон. */
    static List<Double> breakpoints(List<Requirement> reqs, double total) {
        return breakpoints(reqs, total, new TreeSet<>());
    }

    /** @param mandatoryOut сюда попадают точки пересечения отметки 3.0 — их дробить обязательно (§5) */
    static List<Double> breakpoints(List<Requirement> reqs, double total, TreeSet<Double> mandatoryOut) {
        TreeSet<Double> cand = new TreeSet<>();
        cand.add(0.0);
        cand.add(total);
        for (Requirement r : reqs) {
            for (double d : new double[]{r.getMaxTop(), r.getMinTop()}) {
                if (d <= 0 || d == Double.MAX_VALUE) {
                    continue;
                }
                double ramp = Math.abs(BASE - d) / SLOPE;
                for (double v : new double[]{r.getS0(), r.getS1(), r.getS0() - ramp, r.getS1() + ramp}) {
                    add(cand, v, total);
                }
            }
        }
        // попарные пересечения линейных кусков огибающих
        for (Requirement a : reqs) {
            for (Requirement b : reqs) {
                if (a == b) {
                    continue;
                }
                for (double da : new double[]{a.getMaxTop(), a.getMinTop()}) {
                    for (double db : new double[]{b.getMaxTop(), b.getMinTop()}) {
                        if (da <= 0 || db <= 0 || da == Double.MAX_VALUE || db == Double.MAX_VALUE) {
                            continue;
                        }
                        // спуск от a после s1_a встречает подъём к b до s0_b (и обратные знаки)
                        add(cand, (db - da + SLOPE * (a.getS1() + b.getS0())) / (2 * SLOPE), total);
                        add(cand, (da - db + SLOPE * (a.getS1() + b.getS0())) / (2 * SLOPE), total);
                        // плоский участок a встречает рампу b
                        add(cand, b.getS0() - Math.abs(da - db) / SLOPE, total);
                        add(cand, b.getS1() + Math.abs(da - db) / SLOPE, total);
                    }
                }
            }
        }
        // пересечение отметки 3.0 — обязательный узел по §5: добавляем без слияния и без порога,
        // иначе на сплошной рампе с «выше кабеля» на «ниже кабеля» участок пересечёт отметку внутри себя
        List<Double> pts = new ArrayList<>(cand);
        TreeSet<Double> atBase = new TreeSet<>();
        for (int i = 0; i + 1 < pts.size(); i++) {
            double h0 = profile(pts.get(i), reqs);
            double h1 = profile(pts.get(i + 1), reqs);
            if ((h0 - BASE) * (h1 - BASE) < -1e-12) {
                double t = (BASE - h0) / (h1 - h0);
                double at = pts.get(i) + t * (pts.get(i + 1) - pts.get(i));
                if (at > EPS && at < total - EPS) {
                    atBase.add(at);
                    cand.add(at);
                }
            }
        }
        pts = new ArrayList<>(cand);
        TreeSet<Double> breaks = new TreeSet<>(atBase);
        for (int i = 1; i + 1 < pts.size(); i++) {
            double sPrev = pts.get(i - 1);
            double s = pts.get(i);
            double sNext = pts.get(i + 1);
            double k1 = (profile(s, reqs) - profile(sPrev, reqs)) / (s - sPrev);
            double k2 = (profile(sNext, reqs) - profile(s, reqs)) / (sNext - s);
            if (Math.abs(k1 - k2) > 1e-4) {
                breaks.add(s);
            }
        }
        mandatoryOut.addAll(atBase);
        return new ArrayList<>(breaks);
    }

    private static void add(TreeSet<Double> cand, double v, double total) {
        if (v > MIN_PIECE_M && v < total - MIN_PIECE_M) {
            // сливаем почти совпадающие точки (куски короче MIN_PIECE_M не создаём)
            Double near = cand.floor(v + MIN_PIECE_M);
            if (near != null && Math.abs(near - v) < MIN_PIECE_M) {
                return;
            }
            cand.add(v);
        }
    }

    private static void verify(List<Requirement> reqs) {
        for (Requirement r : reqs) {
            if ("boundary".equals(r.getType()) || "downstream".equals(r.getType())) {
                continue;
            }
            double hs = Math.max(profile(r.getS0(), reqs), profile(r.getS1(), reqs));
            double hmin = Math.min(profile(r.getS0(), reqs), profile(r.getS1(), reqs));
            if (hs > r.getMaxTop() + 1e-3 || hmin < r.getMinTop() - 1e-3) {
                log.warn("Профиль: требование по глубине для {} на [{}; {}] не выполнено (h {}..{}, нужно ≤{} ≥{})",
                        r.getType(), Math.round(r.getS0()), Math.round(r.getS1()), hmin, hs,
                        r.getMaxTop(), r.getMinTop());
            }
        }
    }

    /** Делит рёбра цепочки в точках перелома, возвращает новую цепочку. */
    private static List<NetEdge> splitChain(NewNetwork net, List<NetEdge> chain, List<Double> breaks,
                                            TreeSet<Double> mandatory) {
        List<NetEdge> out = new ArrayList<>();
        double offset = 0;
        for (NetEdge e : chain) {
            double len = e.length();
            NetEdge cur = e;
            double curOffset = offset;
            for (double b : breaks) {
                double local = b - curOffset;
                boolean must = mandatory.contains(b);
                if (!must && (local <= MIN_PIECE_M || local >= cur.length() - MIN_PIECE_M)) {
                    continue; // перелом у самой вершины — кусок короче MIN_PIECE_M не создаём
                }
                if (must && (local <= EPS || local >= cur.length() - EPS)) {
                    continue; // отметка ровно на конце — участок её и так не пересекает
                }
                List<Coordinate> cs = cur.getCoords();
                double acc = 0;
                for (int i = 0; i + 1 < cs.size(); i++) {
                    double segLen = cs.get(i).distance(cs.get(i + 1));
                    if (local <= acc + segLen + EPS) {
                        Coordinate j = GeomUtil.along(cs.get(i), cs.get(i + 1), local - acc);
                        NetNode node = net.splitEdge(cur, i, j, NetNode.Kind.TECH_NODE);
                        out.add(net.incoming(node));
                        cur = net.outgoing(node).get(0);
                        curOffset = b;
                        break;
                    }
                    acc += segLen;
                }
            }
            out.add(cur);
            offset += len;
        }
        return out;
    }

    /** Глубина верха габарита в точке s цепочки: огибающая требований. */
    static double profile(double s, List<Requirement> reqs) {
        return Math.max(upperAt(s, reqs), lowerAt(s, reqs));
    }

    /** Верхняя огибающая (не глубже BASE, не глубже проходов «сверху» с рампами уклона). */
    static double upperAt(double s, List<Requirement> reqs) {
        double upper = BASE;
        for (Requirement r : reqs) {
            double dist = s < r.getS0() ? r.getS0() - s : (s > r.getS1() ? s - r.getS1() : 0);
            if (r.getMaxTop() < Double.MAX_VALUE) {
                upper = Math.min(upper, r.getMaxTop() + SLOPE * dist);
            }
        }
        return upper;
    }

    /** Нижняя огибающая (не мельче проходов «снизу»/граничных условий с рампами уклона). */
    static double lowerAt(double s, List<Requirement> reqs) {
        double lower = 0;
        for (Requirement r : reqs) {
            double dist = s < r.getS0() ? r.getS0() - s : (s > r.getS1() ? s - r.getS1() : 0);
            if (r.getMinTop() > 0) {
                lower = Math.max(lower, r.getMinTop() - SLOPE * dist);
            }
        }
        return lower;
    }

    /**
     * Требование для пересечения препятствия с правилом rule на интервале [s0, s1].
     * Коммуникации: выше (h ≤ ownDepth − clearance − newHeight), если это не мельче минимальной глубины,
     * иначе ниже (h ≥ ownDepth + ownHeight + clearance). Дороги/трамвай: под объектом, h ≥ clearance.
     */
    static Requirement requirement(RestrictionRule rule, String type, double s0, double s1, double newHeight) {
        return requirement(rule, type, s0, s1, newHeight, 0);
    }

    /**
     * @param lowerBound глубина, мельче которой на этом интервале нельзя (проход под дорогой рядом):
     *                   проход сверху выбираем, только если он её не нарушает
     */
    static Requirement requirement(RestrictionRule rule, String type, double s0, double s1, double newHeight,
                                   double lowerBound) {
        if (rule.isForbidden()) {
            return null;
        }
        if (rule.isCrossUnderOnly()) {
            return new Requirement(s0, s1, Double.MAX_VALUE, rule.getVerticalClearanceM() + DEPTH_MARGIN_M, type);
        }
        if (rule.getOwnDepthM() <= 0) {
            return null;
        }
        // запас DEPTH_MARGIN_M — чтобы округление глубин в выходе не съедало просвет
        double aboveMax = rule.getOwnDepthM() - rule.getVerticalClearanceM() - newHeight - DEPTH_MARGIN_M;
        if (aboveMax >= Math.max(CostConstants.MIN_DEPTH_M, lowerBound)) {
            return new Requirement(s0, s1, aboveMax, 0, type);
        }
        double belowMin = rule.getOwnDepthM() + rule.getOwnHeightM() + rule.getVerticalClearanceM() + DEPTH_MARGIN_M;
        return new Requirement(s0, s1, Double.MAX_VALUE, belowMin, type);
    }

    /** Максимум уже назначенных нижних границ глубины на интервале [s0, s1]. */
    private static double lowerBound(List<Requirement> reqs, double s0, double s1) {
        double lb = 0;
        for (Requirement r : reqs) {
            if (r.getMinTop() > 0 && r.getS0() <= s1 && r.getS1() >= s0) {
                lb = Math.max(lb, r.getMinTop());
            }
        }
        return lb;
    }

    /** Препятствия спецучастка ребра (по типам из crossedTypes, как и раньше). */
    private static List<Obstacle> crossedTyped(NetEdge e) {
        if (e.getCrossedTypes() == null) {
            return java.util.Collections.emptyList();
        }
        Set<String> types = new java.util.HashSet<>(java.util.Arrays.asList(e.getCrossedTypes().split(",")));
        return crossedObstacles(e, types);
    }

    private static List<Obstacle> crossedObstacles(NetEdge e, Set<String> types) {
        List<Obstacle> out = new ArrayList<>();
        for (SegmentCheck chk : e.getChecks()) {
            for (SegmentCheck.Crossing c : chk.getCrossings()) {
                if (types.contains(c.getObstacle().getType()) && !out.contains(c.getObstacle())) {
                    out.add(c.getObstacle());
                }
            }
        }
        return out;
    }
}
