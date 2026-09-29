package ru.lct.heat.routing;

import ru.lct.heat.geometry.ObstacleMap;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.ref.DiameterSpec;
import ru.lct.heat.model.ref.DiameterTable;
import ru.lct.heat.model.ref.RuleProfile;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш карт препятствий по ДУ (карта зависит от ДУ через габарит и отступ ОКС) и искателей путей.
 * Карты неизменяемы и разделяются между потоками ({@link Shared}); искатели пути хранят
 * изменяемое состояние (динамические препятствия, кэш видимости) — по одному набору на поток.
 */
public final class MapCache {

    /** Общие для всех вариантов карты препятствий. */
    public static final class Shared {
        private final InputModel input;
        private final RoutingParams params;
        private final RuleProfile profile;
        private final Map<Integer, ObstacleMap> maps = new ConcurrentHashMap<>();

        public Shared(InputModel input, RoutingParams params) {
            this(input, params, RuleProfile.heat());
        }

        public Shared(InputModel input, RoutingParams params, RuleProfile profile) {
            this.input = input;
            this.params = params;
            this.profile = profile;
        }

        /** Суммарное число проверок отрезков по всем картам — детерминированная мера проделанной работы. */
        public long checks() {
            long sum = 0;
            for (ObstacleMap m : maps.values()) {
                sum += m.checkCount();
            }
            return sum;
        }

        public ObstacleMap map(DiameterSpec dn) {
            return maps.computeIfAbsent(dn.getDiameter(), k -> ObstacleMap.build(input, dn,
                    params.getVertexClearanceEpsM(), params.getSpecialZoneSampleStepM(), profile));
        }
    }

    private final Shared shared;
    private final RoutingParams params;
    private final Map<Integer, PathFinder> finders = new HashMap<>();
    private final Map<Integer, PathFinder> exactFinders = new HashMap<>();

    public MapCache(Shared shared, RoutingParams params) {
        this.shared = shared;
        this.params = params;
    }

    public MapCache(InputModel input, RoutingParams params) {
        this(new Shared(input, params), params);
    }

    /** Суммарное число проверок отрезков (мера работы для бюджета перебора). */
    public long checks() {
        return shared.checks();
    }

    /** Точная карта для ДУ — для проверок. */
    public ObstacleMap map(DiameterSpec dn) {
        return shared.map(dn);
    }

    /** Карта для трассировки: наибольший ДУ группы, в которую попадает dn (консервативный габарит). */
    public ObstacleMap routingMap(DiameterSpec dn) {
        return shared.map(routingSpec(dn));
    }

    public DiameterSpec routingSpec(DiameterSpec dn) {
        String buckets = params.getRoutingDiameterBuckets();
        if (buckets == null || buckets.isBlank()) {
            return dn;
        }
        for (String b : buckets.split(",")) {
            int limit = Integer.parseInt(b.trim());
            if (dn.getDiameter() <= limit) {
                return DiameterTable.byDiameter(limit).orElse(dn);
            }
        }
        return dn;
    }

    public PathFinder finder(DiameterSpec dn) {
        DiameterSpec spec = routingSpec(dn);
        return finders.computeIfAbsent(spec.getDiameter(), k -> new PathFinder(shared.map(spec), params));
    }

    /**
     * Искатель по точной карте ДУ. Обычная трассировка идёт по карте наибольшего ДУ группы — габарит
     * с запасом, и в узком коридоре маршрут может не найтись там, где по точному ДУ он есть. Нужен
     * только для повторной попытки ремонта; null — точная карта совпадает с групповой.
     */
    public PathFinder exactFinder(DiameterSpec dn) {
        if (routingSpec(dn).getDiameter() == dn.getDiameter()) {
            return null;
        }
        return exactFinders.computeIfAbsent(dn.getDiameter(), k -> new PathFinder(shared.map(dn), params));
    }

    public String stats() {
        StringBuilder sb = new StringBuilder();
        finders.forEach((dn, f) -> sb.append("DN").append(dn).append(": ").append(f.stats()).append("; "));
        return sb.toString();
    }
}
