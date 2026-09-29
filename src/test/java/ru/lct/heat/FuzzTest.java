package ru.lct.heat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.locationtech.jts.geom.LineString;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.out.NewPipe;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.routing.HeatRoutingEngine;
import ru.lct.heat.routing.RoutingParams;
import ru.lct.heat.routing.SolveOptions;
import ru.lct.heat.io.OutputGeoJsonReader;
import ru.lct.heat.validation.OutputValidator;
import ru.lct.heat.validation.ValidationReport;
import ru.lct.heat.support.SimpleGeoJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Фаззинг ядра на синтетических наборах (data/synthetic/fuzz, генератор scripts/make_synthetic.py).
 * Для каждого набора: все варианты, проверка инвариантов (все точки подключены, суммы сходятся,
 * новые участки не пересекаются). Отчёт — data/results/fuzz-report.md.
 * Запуск: ./mvnw test -Dfuzz=true -Dtest=FuzzTest
 */
@EnabledIfSystemProperty(named = "fuzz", matches = "true")
class FuzzTest {

    @Test
    void allSyntheticDatasets() throws IOException {
        // каталог можно переопределить (-Dfuzz.dir=...) — для разовых охот с другими сидами
        Path dir = Paths.get(System.getProperty("fuzz.dir", "data/synthetic/fuzz"));
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".geojson")).sorted().collect(Collectors.toList());
        }
        StringBuilder report = new StringBuilder("| набор | точек | время, с | вариантов | лучший score | неподключено"
                + " | пересечений | ошибок валидатора | макс. совпадение трасс |\n|---|---|---|---|---|---|---|---|---|\n");
        OutputValidator validator = new OutputValidator(new OutputGeoJsonReader());
        // профиль правил можно переопределить (-Dfuzz.resource=water|cable): правила лежат в данных,
        // и фаззинг должен проверять не только тепловые сети. Режим — -Dfuzz.mode=DEPTH_3D: профиль
        // глубины и вертикальные просветы (§5) иначе остаются вне фаззинга
        SolveOptions options = SolveOptions.builder()
                .mode(SolveOptions.Mode.valueOf(System.getProperty("fuzz.mode", "PLAN_2D")))
                .resource(System.getProperty("fuzz.resource", "heat")).build();
        List<String> failures = new ArrayList<>();
        for (Path f : files) {
            InputModel input = SimpleGeoJson.read(f);
            long t0 = System.currentTimeMillis();
            List<Variant> variants = new HeatRoutingEngine(new RoutingParams()).solve(input, options);
            long ms = System.currentTimeMillis() - t0;
            SimpleGeoJson.write(variants, Paths.get(System.getProperty("fuzz.out", "data/results/fuzz"), f.getFileName().toString()));
            int unconnected = variants.isEmpty() ? input.getConnectionPoints().size()
                    : variants.get(0).getSummary().getUnconnectedOksIds().size();
            int crossings = 0;
            for (Variant v : variants) {
                crossings += crossings(v);
                double pipes = v.getPipes().stream().mapToDouble(NewPipe::getCost).sum();
                double chambers = v.getChambers().stream().mapToDouble(c -> c.getCost()).sum();
                double expected = pipes + chambers + v.getSummary().getExistingChamberTieInCost();
                if (Math.abs(expected - v.getSummary().getConstructionCost()) > 1.0) {
                    failures.add(f.getFileName() + " " + v.getVariantId() + ": сумма стоимости не сходится");
                }
            }
            // независимая проверка выхода по всем правилам Техприложения
            ValidationReport report3 = validator.validate(input, variants, options);
            long errors = report3.getErrorCount();
            if (errors > 0) {
                failures.add(f.getFileName() + ": валидатор — " + report3.getDiagnostics().stream()
                        .filter(d -> "ERROR".equals(d.getSeverity())).limit(3).collect(Collectors.toList()));
            }
            // §6: варианты в выдаче должны различаться трассой
            double maxOverlap = 0;
            for (int i = 0; i < variants.size(); i++) {
                for (int j = i + 1; j < variants.size(); j++) {
                    maxOverlap = Math.max(maxOverlap, overlap(variants.get(i), variants.get(j)));
                }
            }
            if (maxOverlap > new RoutingParams().getVariantHardMaxOverlap() + 1e-6) {
                failures.add(f.getFileName() + ": варианты совпадают на " + Math.round(maxOverlap * 100) + "%");
            }
            if (unconnected > 0) {
                failures.add(f.getFileName() + ": неподключено " + unconnected);
            }
            if (crossings > 0) {
                failures.add(f.getFileName() + ": пересечений " + crossings);
            }
            report.append(String.format(java.util.Locale.ROOT, "| %s | %d | %.1f | %d | %s | %d | %d | %d | %.2f |\n",
                    f.getFileName(), input.getConnectionPoints().size(), ms / 1000.0, variants.size(),
                    variants.isEmpty() ? "-" : String.valueOf(variants.get(0).getSummary().getScore()),
                    unconnected, crossings, errors, maxOverlap));
        }
        Files.createDirectories(Paths.get("data/results"));
        Files.writeString(Paths.get(System.getProperty("fuzz.report", "data/results/fuzz-report.md")), report + "\n" + String.join("\n", failures) + "\n");
        System.out.println(report);
        System.out.println(String.join("\n", failures));
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** Доля длины, совпадающая у двух вариантов (коридор 3 м) — §6 «содержательно разные». */
    private static double overlap(Variant a, Variant b) {
        org.locationtech.jts.geom.Geometry ga = shape(a);
        org.locationtech.jts.geom.Geometry gb = shape(b);
        if (ga.isEmpty() || gb.isEmpty()) {
            return 0;
        }
        return Math.max(gb.intersection(ga.buffer(3)).getLength() / gb.getLength(),
                ga.intersection(gb.buffer(3)).getLength() / ga.getLength());
    }

    private static org.locationtech.jts.geom.Geometry shape(Variant v) {
        return ru.lct.heat.geometry.Crs.UTM.buildGeometry(
                v.getPipes().stream().map(NewPipe::getGeometry).collect(Collectors.toList())).union();
    }

    private static int crossings(Variant v) {
        List<LineString> lines = v.getPipes().stream().map(NewPipe::getGeometry).collect(Collectors.toList());
        int n = 0;
        for (int i = 0; i < lines.size(); i++) {
            for (int j = i + 1; j < lines.size(); j++) {
                if (lines.get(i).getEnvelopeInternal().intersects(lines.get(j).getEnvelopeInternal())
                        && (lines.get(i).crosses(lines.get(j)) || lines.get(i).overlaps(lines.get(j)))) {
                    n++;
                }
            }
        }
        return n;
    }
}
