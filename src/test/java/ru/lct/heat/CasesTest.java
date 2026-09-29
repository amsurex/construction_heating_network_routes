package ru.lct.heat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.LayingMethod;
import ru.lct.heat.model.out.NewPipe;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.routing.HeatRoutingEngine;
import ru.lct.heat.routing.RoutingParams;
import ru.lct.heat.routing.SolveOptions;
import ru.lct.heat.support.SimpleGeoJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Мини-кейсы (data/cases, генератор scripts/make_cases.py): прогон ядра и проверка ожиданий по правилам.
 * Выход — data/cases/out/<name>.geojson, картинки — scripts/plot_cases.py.
 * Запуск: ./mvnw test -Dcases=true -Dtest=CasesTest
 */
@EnabledIfSystemProperty(named = "cases", matches = "true")
class CasesTest {

    private static final Path DIR = Paths.get("data/cases");

    @Test
    void runAllCases() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(DIR.resolve("cases.json").toFile());
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            String name = c.get("name").asText();
            SolveOptions.Mode mode = SolveOptions.Mode.valueOf(c.get("mode").asText());
            InputModel input = SimpleGeoJson.read(DIR.resolve(name + ".geojson"));
            List<Variant> variants = new HeatRoutingEngine(new RoutingParams())
                    .solve(input, SolveOptions.builder().mode(mode).build());
            SimpleGeoJson.write(variants, DIR.resolve("out").resolve(name + ".geojson"));
            try {
                check(name, variants.get(0));
            } catch (AssertionError e) {
                failures.add(name + ": " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static void check(String name, Variant v) {
        List<NewPipe> pipes = v.getPipes();
        switch (name) {
            case "01_spec_example":
                assertEquals(1, pipes.size());
                assertEquals(100, pipes.get(0).getDiameter());
                assertEquals(8_974_800, pipes.get(0).getCost(), 1.0);
                assertEquals(13_974_800, v.getSummary().getConstructionCost(), 1.0);
                assertEquals(0.6913, v.getSummary().getScore(), 1e-4);
                break;
            case "02_building_detour":
                assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
                assertTrue(v.getSummary().getNewNetworkLength() > 125.0 + 10, "обход длиннее прямой");
                break;
            case "03_road_crossing": {
                assertEquals(3, pipes.size());
                NewPipe sp = pipes.stream().filter(p -> p.getLayingMethod() == LayingMethod.SPECIAL).findFirst().orElseThrow();
                assertEquals(26.0, sp.getLength(), 0.05);
                assertEquals(1.6, sp.getKSpecial(), 1e-9);
                assertEquals(2, v.getTechnicalNodes().size());
                break;
            }
            case "04_shared_trunk": {
                long tieInChambers = v.getChambers().stream().filter(c -> c.getTieInPipeId() != null).count();
                long branchChambers = v.getChambers().size() - tieInChambers;
                assertEquals(1, v.getSummary().getExistingChamberTieInCount() + tieInChambers, "одно присоединение к сети");
                assertEquals(1, branchChambers, "одна камера-разветвление");
                assertEquals(3, pipes.size());
                assertTrue(pipes.stream().anyMatch(p -> p.getDiameter() == 125 && Math.abs(p.getFlowTph() - 40) < 1e-6));
                break;
            }
            case "05_courtyard": {
                assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty(), "точка во дворе подключена");
                NewPipe last = pipes.stream().filter(p -> "oks_1".equals(p.getEndNodeId())).findFirst().orElseThrow();
                var cs = last.getGeometry().getCoordinates();
                // вход через наружную (западную) стену x = -40: точка входа левее стены
                assertTrue(cs[cs.length - 2].x < 414000 - 40, "вход снаружи, а не со двора");
                break;
            }
            case "06_limit_length":
                assertEquals(125, pipes.get(0).getDiameter());
                break;
            case "07_school_territory":
                assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
                break;
            case "08_gas_depth": {
                assertEquals(5, pipes.size());
                NewPipe sp = pipes.stream().filter(p -> p.getKSpecial() > 1).findFirst().orElseThrow();
                // 2.8 − 0.2 − 0.18 (высота ДУ100) − 0.01 запаса на округление глубин в выходе
                assertEquals(2.41, sp.getDepthStart(), 1e-6);
                assertTrue(pipes.stream().allMatch(p -> Math.abs(p.getKDepth() - 1.0) < 1e-9));
                break;
            }
            case "09_chamber_within_10m":
                assertEquals(1, v.getSummary().getExistingChamberTieInCount());
                assertTrue(v.getChambers().isEmpty());
                assertEquals("ch_1", pipes.get(0).getStartNodeId());
                break;
            case "10_unreachable":
                assertEquals(List.of("oks_trapped"), v.getSummary().getUnconnectedOksIds());
                assertEquals(105_000_000, v.getSummary().getUnconnectedPenalty(), 1.0);
                assertEquals(1, pipes.size());
                break;
            case "11_tram_diagonal": {
                NewPipe sp = pipes.stream().filter(p -> p.getLayingMethod() == LayingMethod.SPECIAL).findFirst().orElseThrow();
                assertEquals(1.75, sp.getKSpecial(), 1e-9);
                break;
            }
            case "12_two_points_one_building":
                assertTrue(v.getSummary().getUnconnectedOksIds().isEmpty());
                assertTrue(pipes.stream().anyMatch(p -> Math.abs(p.getFlowTph() - 40) < 1e-6), "магистраль 40 т/ч");
                break;
            default:
                break;
        }
    }
}
