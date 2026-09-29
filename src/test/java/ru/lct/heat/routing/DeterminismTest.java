package ru.lct.heat.routing;

import org.junit.jupiter.api.Test;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.support.SimpleGeoJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Одинаковый вход → байт-в-байт одинаковый выход (параллельные стратегии не вносят недетерминизма). */
class DeterminismTest {

    @Test
    void sameInputSameOutput() throws Exception {
        Path in = Paths.get("data/cases/04_shared_trunk.geojson");
        if (!Files.exists(in)) {
            return; // кейсы генерируются scripts/make_cases.py
        }
        InputModel input = SimpleGeoJson.read(in);
        Path a = Files.createTempFile("det-a", ".geojson");
        Path b = Files.createTempFile("det-b", ".geojson");
        List<Variant> v1 = new HeatRoutingEngine(new RoutingParams()).solve(input, SolveOptions.defaults());
        List<Variant> v2 = new HeatRoutingEngine(new RoutingParams()).solve(input, SolveOptions.defaults());
        SimpleGeoJson.write(v1, a);
        SimpleGeoJson.write(v2, b);
        assertEquals(v1.size(), v2.size());
        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
        assertEquals(HeatRoutingEngine.VERSION, v1.get(0).getSummary().getExtra().get("engine_version"));
    }
}
