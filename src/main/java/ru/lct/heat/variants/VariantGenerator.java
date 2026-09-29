package ru.lct.heat.variants;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heat.cost.VariantAssembler;
import ru.lct.heat.depth.DepthPlanner;
import ru.lct.heat.model.ConnectionPoint;
import ru.lct.heat.model.ExistingPipe;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.routing.ApproachPlanner;
import ru.lct.heat.routing.GeometryPolisher;
import ru.lct.heat.routing.LatticeRectifier;
import ru.lct.heat.routing.LocalSearch;
import ru.lct.heat.routing.MapCache;
import ru.lct.heat.routing.NetworkChecker;
import ru.lct.heat.routing.NetworkRepairer;
import ru.lct.heat.routing.RoutingParams;
import ru.lct.heat.routing.SolveOptions;
import ru.lct.heat.routing.SpecialSplitter;
import ru.lct.heat.routing.TieInCandidates;
import ru.lct.heat.routing.TreeBuilder;
import ru.lct.heat.model.ref.DiameterSpec;
import ru.lct.heat.model.ref.RuleProfile;
import ru.lct.heat.routing.network.NetEdge;
import ru.lct.heat.routing.network.NetNode;
import ru.lct.heat.routing.network.NewNetwork;
import ru.lct.heat.sizing.FlowSizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Генерация содержательно разных вариантов (Техприложение §6, описание кейса §2.8): разные порядки
 * подключения (по расходу, от ближних, от дальних) и другое место присоединения к существующей сети. Одинаковые по топологии
 * результаты (тот же набор точек врезки и та же структура дерева) отбрасываются; остаток ранжируется
 * по score.
 */
@Slf4j
public final class VariantGenerator {

    /** Радиус, в котором исключаются точки врезки базового варианта для «другого места присоединения», м. */
    private static final double ALT_TIE_IN_EXCLUSION_M = 60.0;

    @Value
    private static class Built {
        Strategy strategy;
        NewNetwork net;
        List<ConnectionPoint> unconnected;
        Variant variant;
        /** Сигнатура топологии для отбраковки дублей. */
        String signature;
        /** Нарушений правил, оставшихся после ремонта (внутренняя проверка). */
        int violations;
        /** Доминируется ли другим вариантом по (стоимость, длина). */
        @lombok.experimental.NonFinal
        boolean dominated;
        /** Доля длины, совпадающая с уже выбранными вариантами (0..1) — для отбора и объяснения. */
        @lombok.experimental.NonFinal
        double overlapWithSelected;
        /** Геометрия трассы для сравнения вариантов (ленивая). */
        @lombok.experimental.NonFinal
        org.locationtech.jts.geom.Geometry shape;
    }

    private final InputModel input;
    private final RoutingParams params;
    private final MapCache.Shared sharedMaps;
    private final TieInCandidates tieIns;
    private final ApproachPlanner approach;
    private final Map<Object, ConnectionPoint> pointsById = new LinkedHashMap<>();

    private final boolean depthMode;
    private final RuleProfile profile;

    public VariantGenerator(InputModel input, RoutingParams params, boolean depthMode) {
        this(input, params, depthMode, RuleProfile.heat());
    }

    public VariantGenerator(InputModel input, RoutingParams params, boolean depthMode, RuleProfile profile) {
        this(input, params, depthMode, profile, SolveOptions.defaults());
    }

    public VariantGenerator(InputModel input, RoutingParams params, boolean depthMode, RuleProfile profile,
                            SolveOptions options) {
        this.input = input;
        this.params = params;
        this.depthMode = depthMode;
        this.profile = profile;
        this.sharedMaps = new MapCache.Shared(input, params, profile);
        this.tieIns = TieInCandidates.build(input, params.getTieInSampleStepM())
                .restrict(options.getPinTieInIds(), options.getExcludeTieInIds());
        this.approach = new ApproachPlanner(input, profile);
        for (ConnectionPoint cp : input.getConnectionPoints()) {
            pointsById.put(cp.getId(), cp);
        }
    }

    public List<Variant> generate(int maxVariants) {
        // базовые стратегии считаем параллельно, затем «другое место присоединения» — от лучшей
        List<Strategy> base = new ArrayList<>();
        List<Strategy> all = new ArrayList<>(List.of(byFlowDesc(), nearestFirst(), farthestFirst(), naive()));
        int points = Math.max(1, input.getConnectionPoints().size());
        int randomOrders = Math.min(params.getRandomOrderStrategies(),
                Math.max(params.getRandomOrderMin(), params.getRandomOrderBudgetPoints() / points));
        for (int i = 1; i <= randomOrders; i++) {
            all.add(randomOrder(i));
        }
        for (Strategy s : all) {
            if (enabled(s.getId())) {
                base.add(s);
            }
        }
        List<Built> built = runAll(base);
        built.sort(Comparator.comparingDouble(b -> b.variant.getSummary().getScore()));
        if (!built.isEmpty() && params.isSingleRootStrategy() && params.getEnabledStrategies().isEmpty()) {
            // вариант с одним вводом: камеры-врезки дорогие (3–12 млн), общий ввод часто выгоднее
            Built best = built.get(0);
            if (best.net.roots().size() > 1) {
                built.addAll(runAll(List.of(best.strategy.toBuilder().id("s1").maxRoots(1)
                        .description("Один ввод от существующей сети (общая магистраль)").build())));
                built.sort(Comparator.comparingDouble(x -> x.variant.getSummary().getScore()));
            }
        }
        if (!built.isEmpty() && params.getPerturbationRounds() > 0 && params.getEnabledStrategies().isEmpty()) {
            // итерационный поиск: вариации порядка лучших найденных (swap/перенос точки) — попадания
            // рядом с хорошим порядком чаще, чем у равномерно случайных
            int seeds = 0;
            for (int iter = 0; iter < params.getPerturbationIterations(); iter++) {
                if (budgetSpent()) {
                    log.info("Вариации порядка: бюджет перебора ({} проверок) исчерпан — останавливаемся",
                            params.getSearchBudgetChecks());
                    break;
                }
                List<Strategy> perturbed = new ArrayList<>();
                Set<String> parents = new HashSet<>();
                for (int b = 0; b < Math.min(2, built.size()); b++) {
                    parents.add(built.get(b).strategy.getId());
                    int perRound = Math.min(params.getPerturbationRounds(),
                            Math.max(2, params.getRandomOrderBudgetPoints() / points));
                    int rounds = b == 0 ? perRound : perRound / 2;
                    for (int r = 0; r < rounds; r++) {
                        perturbed.add(perturbedOrder(built.get(b), ++seeds));
                    }
                    if (built.get(b).strategy.getMaxRoots() > 0) {
                        // вариации порядка наследуют ограничение на число вводов
                        List<Strategy> fixed = new ArrayList<>();
                        for (Strategy s : perturbed) {
                            fixed.add(s.getMaxRoots() > 0 ? s
                                    : s.toBuilder().maxRoots(built.get(b).strategy.getMaxRoots()).build());
                        }
                        perturbed.clear();
                        perturbed.addAll(fixed);
                    }
                }
                double bestBefore = built.get(0).variant.getSummary().getScore();
                built.addAll(runAll(perturbed));
                built.sort(Comparator.comparingDouble(x -> x.variant.getSummary().getScore()));
                log.info("Вариации порядка, итерация {}: от {} → лучший score {} → {}", iter + 1, parents,
                        String.format("%.4f", bestBefore), String.format("%.4f", built.get(0).variant.getSummary().getScore()));
                if (built.get(0).variant.getSummary().getScore() >= bestBefore - 1e-9) {
                    break; // улучшения нет — дальше не итерируем
                }
            }
        }
        if (!built.isEmpty() && enabled("v4")) {
            Built best = built.get(0);
            Strategy alt = alternativeTieIns(best);
            built.addAll(runAll(List.of(alt)));
        }

        // отбраковка дублей и вариантов с неподключёнными точками (если лучший подключил больше), ранжирование
        List<Built> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        built.sort(Comparator.comparingDouble(b -> b.variant.getSummary().getScore()));
        int bestUnconnected = built.isEmpty() ? 0 : built.get(0).unconnected.size();
        boolean anyClean = built.stream().anyMatch(b -> b.violations == 0);
        for (Built b : built) {
            if (anyClean && b.violations > 0) {
                log.info("Вариант '{}' с {} нарушениями после ремонта — отброшен (есть чистые варианты)",
                        b.strategy.getId(), b.violations);
                continue;
            }
            if (b.unconnected.size() > bestUnconnected) {
                log.info("Вариант '{}' не подключил {} точек (лучший — {}) — отброшен", b.strategy.getId(),
                        b.unconnected.size(), bestUnconnected);
                continue;
            }
            if (seen.add(b.signature)) {
                distinct.add(b);
            } else {
                log.info("Вариант '{}' совпадает по топологии с лучшим — отброшен", b.strategy.getId());
            }
        }
        // отбор: лучший по score; остальные — содержательно отличающиеся трассы (§6: варианты должны
        // различаться маршрутом/местом врезки/объединением, а не сдвигом той же трассы). Среди кандидатов
        // предпочитаем непохожие на уже выбранные (общая длина ≤ порога) и недоминируемые по (стоимость, длина).
        List<Built> selected = new ArrayList<>();
        if (!distinct.isEmpty()) {
            selected.add(distinct.get(0));
        }
        List<Built> rest = new ArrayList<>(distinct.subList(Math.min(1, distinct.size()), distinct.size()));
        for (Built b : rest) {
            boolean dom = false;
            for (Built o : distinct) {
                if (o != b && dominates(o, b)) {
                    dom = true;
                    break;
                }
            }
            b.dominated = dom;
        }
        while (selected.size() < maxVariants && !rest.isEmpty()) {
            Built pick = null;
            int pickRank = Integer.MAX_VALUE;
            for (Built b : rest) {
                double overlap = 0;
                for (Built sel : selected) {
                    overlap = Math.max(overlap, overlap(sel, b));
                }
                b.overlapWithSelected = overlap;
                boolean similar = overlap > params.getVariantMaxOverlap();
                int rank = (similar ? 2 : 0) + (b.dominated ? 1 : 0);
                if (rank < pickRank) {
                    pickRank = rank;
                    pick = b;
                }
            }
            if (pick == null) {
                break;
            }
            if (pick.overlapWithSelected > params.getVariantHardMaxOverlap()) {
                // §6: «сдвиг той же трассы вариантом не считается» — лучше выдать меньше вариантов
                log.info("Вариант '{}': трасса совпадает с выбранной на {}% — не включён (нет содержательно"
                        + " разных альтернатив)", pick.strategy.getId(), Math.round(pick.overlapWithSelected * 100));
                break;
            }
            if (pick.overlapWithSelected > params.getVariantMaxOverlap()) {
                log.info("Вариант '{}': трасса совпадает с выбранной на {}% — включён как лучший из оставшихся",
                        pick.strategy.getId(), Math.round(pick.overlapWithSelected * 100));
            }
            selected.add(pick);
            rest.remove(pick);
        }
        selected.sort(Comparator.comparingDouble(b -> b.variant.getSummary().getScore()));
        log.info("Перебор: построено вариантов {}, работа {} проверок (бюджет {})", built.size(),
                sharedMaps.checks(), params.getSearchBudgetChecks());
        List<Variant> out = new ArrayList<>();
        int rank = 1;
        for (Built b : selected) {
            Variant v = b.variant;
            Map<String, Object> extra = new LinkedHashMap<>(v.getSummary().getExtra());
            extra.put("pareto_optimal", !b.dominated);
            extra.put("route_overlap_with_better", Math.round(b.overlapWithSelected * 100) / 100.0);
            extra.put("candidates_considered", built.size());
            String description = describe(b, rank == 1 ? null : selected.get(0));
            extra.put("description", description);
            v = v.toBuilder().description(description).build();
            out.add(v.toBuilder().summary(v.getSummary().toBuilder().rank(rank).extra(extra).build()).build());
            rank++;
        }
        return out;
    }

    /** a доминирует b: не хуже по стоимости и длине и строго лучше хотя бы по одному. */
    private static boolean dominates(Built a, Built b) {
        double ca = a.variant.getSummary().getCalculatedCost();
        double cb = b.variant.getSummary().getCalculatedCost();
        double la = a.variant.getSummary().getNewNetworkLength();
        double lb = b.variant.getSummary().getNewNetworkLength();
        return ca <= cb + 1e-6 && la <= lb + 1e-6 && (ca < cb - 1e-6 || la < lb - 1e-6);
    }

    private boolean enabled(String id) {
        String list = params.getEnabledStrategies();
        if (list == null || list.isBlank()) {
            return !"naive".equals(id); // базовая линия для сравнения, в обычный перебор не идёт
        }
        for (String s : list.split(",")) {
            if (s.trim().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private List<Built> runAll(List<Strategy> strategies) {
        int threads = params.getMaxThreads() > 0 ? params.getMaxThreads() : Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(strategies.size(), threads)));
        try {
            List<Future<Built>> futures = new ArrayList<>();
            for (Strategy s : strategies) {
                futures.add(pool.submit(() -> run(s)));
            }
            List<Built> out = new ArrayList<>();
            for (Future<Built> f : futures) {
                try {
                    out.add(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } catch (ExecutionException e) {
                    log.error("Стратегия завершилась ошибкой", e.getCause());
                }
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    private Built run(Strategy s) {
        long t0 = System.currentTimeMillis();
        MapCache maps = new MapCache(sharedMaps, params);
        FlowSizer sizer = new FlowSizer(pointsById);

        // проход 1: трассы при ДУ по расходу точки → итоговые ДУ после объединения
        List<ConnectionPoint> unconnected = new ArrayList<>();
        TreeBuilder builder1 = new TreeBuilder(maps, tieIns, approach, params, s);
        NewNetwork net = builder1.build(input.getConnectionPoints(), unconnected);
        Map<Object, String> reasons = builder1.getUnconnectedReasons();
        TreeBuilder active = builder1;
        sizer.size(net);
        Map<Object, DiameterSpec> hints = dnHints(net);
        // проход 2: каждая точка прокладывается сразу с итоговым ДУ своих участков
        Strategy hinted = s.withDnHints(hints);
        List<ConnectionPoint> unconnected2 = new ArrayList<>();
        TreeBuilder builder2 = new TreeBuilder(maps, tieIns, approach, params, hinted);
        NewNetwork net2 = builder2.build(input.getConnectionPoints(), unconnected2);
        sizer.size(net2);
        if (unconnected2.size() <= unconnected.size()) {
            net = net2;
            unconnected = unconnected2;
            reasons = builder2.getUnconnectedReasons();
            active = builder2;
        } else {
            log.warn("Вариант '{}': второй проход подключил меньше точек, оставляем первый", s.getId());
        }
        NetworkRepairer repairer = new NetworkRepairer(maps, approach, params, pointsById);
        int remaining = repairer.repair(net, 2);
        // подсказки ДУ прошлого прохода могли не совпасть с итогом (другое дерево) — повторяем проходы
        // с накопленными подсказками, пока есть нарушения (до params.maxBuildPasses всего)
        Map<Object, DiameterSpec> merged = new LinkedHashMap<>(hints);
        for (int pass = 3; remaining > 0 && pass <= params.getMaxBuildPasses(); pass++) {
            boolean grew = false;
            for (Map.Entry<Object, DiameterSpec> en : dnHints(net).entrySet()) {
                DiameterSpec cur = merged.get(en.getKey());
                if (cur == null || en.getValue().getDiameter() > cur.getDiameter()) {
                    merged.put(en.getKey(), en.getValue());
                    grew = true;
                }
            }
            if (!grew) {
                break; // подсказки не меняются — дальнейшие проходы бесполезны
            }
            List<ConnectionPoint> unconnectedN = new ArrayList<>();
            TreeBuilder builderN = new TreeBuilder(maps, tieIns, approach, params, s.withDnHints(merged));
            NewNetwork netN = builderN.build(input.getConnectionPoints(), unconnectedN);
            sizer.size(netN);
            int remainingN = repairer.repair(netN, 2);
            log.info("Вариант '{}': проход {} — нарушений {} (было {}), неподключено {} (было {})",
                    s.getId(), pass, remainingN, remaining, unconnectedN.size(), unconnected.size());
            if (remainingN <= remaining && unconnectedN.size() <= unconnected.size()) {
                net = netN;
                unconnected = unconnectedN;
                remaining = remainingN;
                reasons = builderN.getUnconnectedReasons();
                active = builderN;
            }
        }
        if (params.getLocalSearchPasses() > 0 && remaining == 0) {
            // локальная оптимизация: переподключение точек, если это уменьшает score
            NewNetwork improved = new LocalSearch(active, sizer, pointsById, maps, params, approach).improve(net, params.getLocalSearchPasses());
            if (improved != net) {
                int rem = repairer.repair(improved, 2);
                if (rem == 0) {
                    net = improved;
                } else {
                    log.warn("Вариант '{}': результат локального поиска не прошёл проверку ({} нарушений) — отброшен",
                            s.getId(), rem);
                }
            }
        }
        if (remaining > 0) {
            log.warn("Вариант '{}': после ремонта осталось нарушений: {}", s.getId(), remaining);
        }
        // полировка и выпрямление идут после ремонта, поэтому их результат проверяем ещё раз:
        // геометрия могла измениться так, что появилось нарушение (пересечение участков, отступ)
        NewNetwork beforePolish = net.copy();
        sizer.size(net);
        new GeometryPolisher(maps, approach, params).polish(net);
        sizer.size(net);
        new LatticeRectifier(maps, approach, params, input, profile).rectify(net);
        sizer.size(net);
        NetworkChecker checker = new NetworkChecker(maps, approach);
        int afterPolish = checker.check(net).size();
        if (afterPolish > remaining) {
            log.warn("Вариант '{}': полировка дала {} нарушений (было {}) — откат к трассе до полировки",
                    s.getId(), afterPolish, remaining);
            net = beforePolish;
            sizer.size(net);
        }
        // после ремонта и полировки нарушения всё же могли остаться (плотная застройка, ДУ вырос
        // сильнее, чем позволяет коридор): такие ветки снимаем — §2.5 разрешает не подключать точку,
        // если допустимого маршрута нет, и штраф честнее трассы с нарушением
        for (NetNode leaf : repairer.dropUnfixable(net)) {
            ConnectionPoint cp = pointsById.get(leaf.getId());
            if (cp == null) {
                continue;
            }
            sizer.size(net);
            NewNetwork before = net.copy();
            if (active.connect(cp, net) && checker.check(net).isEmpty()) {
                continue; // удалось переподключить другим маршрутом
            }
            net = before;
            sizer.size(net);
            unconnected.add(cp);
            reasons = new LinkedHashMap<>(reasons);
            reasons.put(cp.getId(), "допустимой трассы нет: участок нарушал отступ при итоговом ДУ");
            log.warn("Вариант '{}': точка {} снята с сети — допустимой трассы при итоговом ДУ нет",
                    s.getId(), cp.getId());
        }
        remaining = checker.check(net).size();
        sizer.size(net);
        new SpecialSplitter(approach, maps).split(net);
        int crossings = checker.crossings(net).size();
        if (crossings > 0) {
            log.warn("Вариант '{}': после спецразбиения пересечений участков {}", s.getId(), crossings);
            remaining += crossings;
        }
        String description = s.getDescription();
        if (depthMode) {
            new DepthPlanner().plan(net);
            description += "; профиль глубины: над коммуникациями, обычная глубина 3.0 м";
        }
        Variant v = new VariantAssembler(s.getId(), description).assemble(net, unconnected, reasons);
        log.info("Вариант '{}' построен за {} мс", s.getId(), System.currentTimeMillis() - t0);
        return new Built(s, net, unconnected, v, signature(net), remaining, false, 0, null);
    }

    /** Максимальный итоговый ДУ рёбер, созданных каждой точкой. */
    private static Map<Object, DiameterSpec> dnHints(NewNetwork net) {
        Map<Object, DiameterSpec> hints = new LinkedHashMap<>();
        for (NetEdge e : net.getEdges()) {
            Object id = e.getCreatorPointId();
            if (id == null) {
                continue;
            }
            DiameterSpec cur = hints.get(id);
            if (cur == null || e.getDiameter().getDiameter() > cur.getDiameter()) {
                hints.put(id, e.getDiameter());
            }
        }
        return hints;
    }

    /**
     * Человекочитаемое описание варианта: чем он отличается содержательно (места присоединения, объединение
     * в магистрали) и — для не лучшего — чем отличается от первого (точки с другой трассой, дельта стоимости
     * и длины). Техническое имя стратегии в описание не выносим: эксперту важно решение, а не перебор.
     */
    private String describe(Built b, Built best) {
        StringBuilder sb = new StringBuilder();
        List<String> roots = new ArrayList<>();
        for (NetNode r : b.net.roots()) {
            Object pipeId = r.getTieInPipeId();
            roots.add(r.getKind() == NetNode.Kind.EXISTING_CHAMBER ? "существующая камера " + r.getId()
                    : "новая камера на участке " + pipeId);
        }
        sb.append("Присоединение к существующей сети: ").append(roots.isEmpty() ? "нет" : String.join(", ", roots));
        int branch = 0;
        for (NetNode n : b.net.nodeList()) {
            if (n.getKind() == NetNode.Kind.BRANCH_CHAMBER) {
                branch++;
            }
        }
        sb.append("; камер-разветвлений: ").append(branch);
        sb.append(branch > 0 ? "; точки объединены в общие магистрали" : "; каждая точка отдельной трассой");
        if (best != null) {
            List<Object> moved = differingPoints(b, best);
            if (!moved.isEmpty()) {
                sb.append("; другая трасса у точек: ").append(moved.size() > 6
                        ? moved.subList(0, 6) + " и ещё " + (moved.size() - 6) : moved.toString());
            }
            double dc = b.variant.getSummary().getConstructionCost() - best.variant.getSummary().getConstructionCost();
            double dl = b.variant.getSummary().getNewNetworkLength() - best.variant.getSummary().getNewNetworkLength();
            sb.append(String.format(Locale.ROOT, "; к лучшему варианту: стоимость %+.1f млн ₽, длина %+.0f м",
                    dc / 1e6, dl));
        }
        return sb.toString();
    }

    /** Точки, путь которых до места присоединения идёт иначе, чем в варианте other (совпадение < 80 %). */
    private List<Object> differingPoints(Built b, Built other) {
        List<Object> out = new ArrayList<>();
        for (ConnectionPoint cp : input.getConnectionPoints()) {
            org.locationtech.jts.geom.Geometry a = branchShape(b, cp.getId());
            org.locationtech.jts.geom.Geometry c = branchShape(other, cp.getId());
            if (a == null || c == null) {
                continue;
            }
            double common = a.intersection(c.buffer(params.getVariantOverlapToleranceM())).getLength();
            if (common < 0.8 * a.getLength()) {
                out.add(cp.getId());
            }
        }
        return out;
    }

    /** Геометрия пути от точки подключения вверх до места присоединения к существующей сети. */
    private static org.locationtech.jts.geom.Geometry branchShape(Built b, Object pointId) {
        NetNode node = b.net.node(pointId);
        if (node == null) {
            return null;
        }
        List<org.locationtech.jts.geom.Geometry> lines = new ArrayList<>();
        NetNode cur = node;
        for (int guard = 0; guard < 1000; guard++) {
            NetEdge in = b.net.incoming(cur);
            if (in == null) {
                break;
            }
            lines.add(ru.lct.heat.geometry.GeomUtil.line(in.getCoords()));
            cur = in.getFrom();
        }
        return lines.isEmpty() ? null : ru.lct.heat.geometry.Crs.UTM.buildGeometry(lines).union();
    }

    /**
     * Доля длины трассы b, проходящей по коридору трассы a (буфер {@code variant-overlap-tolerance-m}).
     * 1.0 — тот же маршрут, 0 — полностью другой.
     */
    private double overlap(Built a, Built b) {
        org.locationtech.jts.geom.Geometry ga = shapeOf(a);
        org.locationtech.jts.geom.Geometry gb = shapeOf(b);
        if (ga.isEmpty() || gb.isEmpty()) {
            return 0;
        }
        double inA = gb.intersection(ga.buffer(params.getVariantOverlapToleranceM())).getLength();
        double inB = ga.intersection(gb.buffer(params.getVariantOverlapToleranceM())).getLength();
        return Math.max(inA / Math.max(gb.getLength(), 1e-9), inB / Math.max(ga.getLength(), 1e-9));
    }

    private static org.locationtech.jts.geom.Geometry shapeOf(Built b) {
        if (b.shape == null) {
            List<org.locationtech.jts.geom.Geometry> lines = new ArrayList<>();
            for (ru.lct.heat.model.out.NewPipe p : b.variant.getPipes()) {
                lines.add(p.getGeometry());
            }
            b.shape = ru.lct.heat.geometry.Crs.UTM.buildGeometry(lines).union();
        }
        return b.shape;
    }

    /** Сигнатура: точки присоединения (округлённые до 10 м) + число рёбер и камер. */
    private static String signature(NewNetwork net) {
        TreeSet<String> roots = new TreeSet<>();
        int chambers = 0;
        for (NetNode n : net.nodeList()) {
            if (n.isRoot()) {
                roots.add(Math.round(n.getCoord().x / 10) + ":" + Math.round(n.getCoord().y / 10));
            }
            if (n.getKind() == NetNode.Kind.BRANCH_CHAMBER) {
                chambers++;
            }
        }
        return roots + "|e=" + net.getEdges().size() + "|c=" + chambers;
    }

    // ---------- стратегии ----------

    /**
     * Наивное решение для сравнения: каждая точка подключается к существующей сети сама по себе, без
     * общих магистралей. В обычный перебор не попадает (включается только явным enabled-strategies=naive)
     * — нужно, чтобы показать выигрыш от объединения в магистрали.
     */
    private Strategy naive() {
        return Strategy.builder().id("naive")
                .description("Каждая точка отдельной трассой к существующей сети (без общих магистралей)")
                .order(Comparator.comparingDouble(ConnectionPoint::getFlowTph).reversed())
                .treeJoins(false)
                .build();
    }

    private Strategy byFlowDesc() {
        return Strategy.builder().id("v1")
                .description("Общие магистрали; точки подключаются по убыванию расхода")
                .order(Comparator.comparingDouble(ConnectionPoint::getFlowTph).reversed())
                .build();
    }

    private Map<Object, Double> distanceToNetwork() {
        Map<Object, Double> dist = new LinkedHashMap<>();
        for (ConnectionPoint cp : input.getConnectionPoints()) {
            double d = Double.MAX_VALUE;
            for (ExistingPipe p : input.getPipes()) {
                d = Math.min(d, p.getGeometry().distance(cp.getGeometry()));
            }
            dist.put(cp.getId(), d);
        }
        return dist;
    }

    private Strategy nearestFirst() {
        Map<Object, Double> dist = distanceToNetwork();
        return Strategy.builder().id("v2")
                .description("Общие магистрали; точки подключаются от ближайших к существующей сети")
                .order(Comparator.comparingDouble(cp -> dist.get(cp.getId())))
                .build();
    }

    /**
     * Детерминированно-случайный порядок (сид = номер): жадное дерево чувствительно к порядку точек,
     * несколько порядков — дешёвый способ найти лучший вариант; результат воспроизводим.
     */
    private Strategy randomOrder(int seed) {
        List<Object> ids = new ArrayList<>();
        for (ConnectionPoint cp : input.getConnectionPoints()) {
            ids.add(cp.getId());
        }
        java.util.Collections.shuffle(ids, new java.util.Random(1_000_003L * seed + 17));
        Map<Object, Integer> pos = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            pos.put(ids.get(i), i);
        }
        return Strategy.builder().id("r" + seed)
                .description("Общие магистрали; порядок подключения №" + seed + " (перебор порядков)")
                .order(Comparator.comparingInt(cp -> pos.get(cp.getId())))
                .build();
    }

    /**
     * Бюджет перебора задан в «единицах работы» (проверках отрезков в картах препятствий), а не в секундах:
     * их число детерминировано, поэтому результат не зависит от скорости машины и остаётся воспроизводимым.
     */
    private boolean budgetSpent() {
        return params.getSearchBudgetChecks() > 0 && sharedMaps.checks() > params.getSearchBudgetChecks();
    }

    /** Вариация порядка готового варианта: 1–2 случайные перестановки (обмен двух точек, перенос точки). */
    private Strategy perturbedOrder(Built from, int seed) {
        List<Object> ids = new ArrayList<>();
        for (ConnectionPoint cp : from.strategy.sorted(input.getConnectionPoints())) {
            ids.add(cp.getId());
        }
        java.util.Random rnd = new java.util.Random(7_919L * seed + 101);
        int n = ids.size();
        int moves = 1 + rnd.nextInt(2);
        for (int m = 0; m < moves && n > 1; m++) {
            int i = rnd.nextInt(n);
            int j = rnd.nextInt(n);
            if (rnd.nextBoolean()) {
                java.util.Collections.swap(ids, i, j);
            } else {
                ids.add(j, ids.remove(i));
            }
        }
        Map<Object, Integer> pos = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            pos.put(ids.get(i), i);
        }
        return Strategy.builder().id("p" + seed)
                .description("Общие магистрали; вариация порядка подключения №" + seed + " от варианта "
                        + from.strategy.getId())
                .tieInFilter(from.strategy.getTieInFilter())
                .order(Comparator.comparingInt(cp -> pos.get(cp.getId())))
                .build();
    }

    /** Дальние точки первыми: магистраль тянется сразу к дальнему краю, ближние подсаживаются на неё. */
    private Strategy farthestFirst() {
        Map<Object, Double> dist = distanceToNetwork();
        return Strategy.builder().id("v3")
                .description("Общие магистрали; точки подключаются от самых дальних от существующей сети")
                .order(Comparator.comparingDouble((ConnectionPoint cp) -> dist.get(cp.getId())).reversed())
                .build();
    }

    /** Другое место присоединения: исключаем окрестности точек врезки лучшего варианта. */
    /**
     * Другое место присоединения главной магистрали: исключаем окрестность корня лучшего варианта,
     * через который идёт наибольший расход. Остальные врезки остаются доступны.
     */
    private Strategy alternativeTieIns(Built best) {
        Coordinate mainRoot = null;
        double maxFlow = -1;
        for (NetNode n : best.net.nodeList()) {
            if (!n.isRoot()) {
                continue;
            }
            double flow = 0;
            for (NetEdge e : best.net.outgoing(n)) {
                flow += e.getFlowTph();
            }
            if (flow > maxFlow) {
                maxFlow = flow;
                mainRoot = n.getCoord();
            }
        }
        final Coordinate root = mainRoot;
        return Strategy.builder().id("v4")
                .description("Общие магистрали; другое место присоединения главной магистрали к существующей сети")
                .order(best.strategy.getOrder())
                .tieInFilter(c -> root == null || root.distance(c) > ALT_TIE_IN_EXCLUSION_M)
                .build();
    }
}
