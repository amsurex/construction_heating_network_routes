package ru.lct.heat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.routing.HeatRoutingEngine;
import ru.lct.heat.routing.RoutingParams;
import ru.lct.heat.routing.SolveOptions;
import ru.lct.heat.support.SimpleGeoJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Прогон ядра на конкурсном датасете. Проверяет только инварианты, не координаты.
 * Пишет результат в data/results/dev-2d.geojson для визуальной проверки (scripts/plot_geojson.py).
 * Запуск: ./mvnw test -Ddataset=true -Dtest=DatasetSmokeTest
 */
@EnabledIfSystemProperty(named = "dataset", matches = "true")
class DatasetSmokeTest {

    private static final Path DATASET = Paths.get(System.getProperty("dataset.file", "data/samples/dataset.geojson"));
    private static final Path RESULT = Paths.get(System.getProperty("dataset.out", "data/results/dev-2d.geojson"));

    @Test
    void allPointsConnectedAndSummaryConsistent() throws Exception {
        assertTrue(Files.exists(DATASET), "нет датасета " + DATASET.toAbsolutePath());
        InputModel input = SimpleGeoJson.read(DATASET);

        RoutingParams params = new RoutingParams();
        params.setEnabledStrategies(System.getProperty("strategies", ""));
        params.setCropToAreaOfInterest(!"false".equals(System.getProperty("crop")));
        params.setRandomOrderStrategies(Integer.getInteger("randomOrders", params.getRandomOrderStrategies()));
        // ручной подбор параметров: ./mvnw test -Ddataset=true -Dtest=DatasetSmokeTest -DturnPenalty=2 ...
        params.setTurnPenaltyMPer45Deg(Double.parseDouble(System.getProperty("turnPenalty",
                String.valueOf(params.getTurnPenaltyMPer45Deg()))));
        params.setNeighborRadiusM(Double.parseDouble(System.getProperty("neighborRadius",
                String.valueOf(params.getNeighborRadiusM()))));
        params.setTreeJoinRadiusExtraM(Double.parseDouble(System.getProperty("treeJoinExtra",
                String.valueOf(params.getTreeJoinRadiusExtraM()))));
        params.setLocalSearchPasses(Integer.getInteger("localSearchPasses", params.getLocalSearchPasses()));
        params.setMaxThreads(Integer.getInteger("maxThreads", params.getMaxThreads()));
        params.setMaxApproaches(Integer.getInteger("maxApproaches", params.getMaxApproaches()));
        params.setPerturbationRounds(Integer.getInteger("perturbRounds", params.getPerturbationRounds()));
        params.setPerturbationIterations(Integer.getInteger("perturbIters", params.getPerturbationIterations()));
        params.setRandomOrderBudgetPoints(Integer.getInteger("orderBudget", params.getRandomOrderBudgetPoints()));
        params.setTieInSampleStepM(Double.parseDouble(System.getProperty("tieInStep",
                String.valueOf(params.getTieInSampleStepM()))));
        params.setTreeJoinSampleStepM(Double.parseDouble(System.getProperty("treeJoinStep",
                String.valueOf(params.getTreeJoinSampleStepM()))));
        params.setFallbackGridStepM(Double.parseDouble(System.getProperty("fallbackStep",
                String.valueOf(params.getFallbackGridStepM()))));
        params.setFallbackJumpMaxM(Double.parseDouble(System.getProperty("fallbackJump",
                String.valueOf(params.getFallbackJumpMaxM()))));
        params.setVertexClearanceEpsM(Double.parseDouble(System.getProperty("vertexEps",
                String.valueOf(params.getVertexClearanceEpsM()))));
        params.setSnapMaxExtraM(Double.parseDouble(System.getProperty("snapMaxExtra",
                String.valueOf(params.getSnapMaxExtraM()))));
        List<Variant> variants = new HeatRoutingEngine(params).solve(input, SolveOptions.defaults());
        SimpleGeoJson.write(variants, RESULT);

        Variant v = variants.get(0);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty(),
                "неподключённые: " + v.getSummary().getUnconnectedOksIds());
        assertFalse(v.getPipes().isEmpty());
        double pipes = v.getPipes().stream().mapToDouble(p -> p.getCost()).sum();
        double chambers = v.getChambers().stream().mapToDouble(c -> c.getCost()).sum();
        double expected = pipes + chambers + v.getSummary().getExistingChamberTieInCost();
        assertEquals(expected, v.getSummary().getConstructionCost(), 1.0);
    }

    @Test
    void depthModeProducesProfiles() throws Exception {
        InputModel input = SimpleGeoJson.read(DATASET);
        RoutingParams params = new RoutingParams();
        params.setEnabledStrategies("v1");
        List<Variant> variants = new HeatRoutingEngine(params).solve(input,
                SolveOptions.builder().mode(SolveOptions.Mode.DEPTH_3D).build());
        SimpleGeoJson.write(variants, Paths.get("data/results/dev-3d.geojson"));
        Variant v = variants.get(0);
        assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
        for (var p : v.getPipes()) {
            assertTrue(p.getDepthStart() != null && p.getDepthEnd() != null, "глубины заполнены");
            assertTrue(p.getDepthStart() >= 0.7 - 1e-9 && p.getDepthEnd() >= 0.7 - 1e-9);
        }
    }
}
