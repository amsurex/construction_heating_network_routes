package ru.lct.heat.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.api.JobWorker;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.routing.HeatRoutingEngine;
import ru.lct.heat.routing.RoutingParams;
import ru.lct.heat.routing.SolveOptions;
import ru.lct.heat.routing.stub.StubRoutingEngine;
import ru.lct.heat.validation.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Автономные parse/solve/validate для воспроизводимых проверок без БД и Spring. */
public final class PlatformCli {
    private PlatformCli() { }
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("parse INPUT | solve INPUT OUTPUT [stub|core] | validate INPUT OUTPUT [DEPTH_3D]");
        }
        long start = System.nanoTime();
        Path inputPath = Paths.get(args[1]);
        InputModel input;
        try {
            input = new GeoJsonReader(new InputValidator()).read(inputPath);
        } catch (ru.lct.heat.validation.HeatRoutingException e) {
            System.err.println(e.getMessage());
            for (Object d : e.getDiagnostics()) { System.err.println("  " + d); }
            System.exit(3);
            return;
        }
        ObjectMapper json = new ObjectMapper();
        if ("parse".equals(args[0])) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("inputBytes", Files.size(inputPath)); metrics.put("restrictions", input.getRestrictions().size());
            metrics.put("elapsedMs", (System.nanoTime()-start)/1_000_000);
            metrics.put("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            metrics.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
            json.writeValue(System.out, metrics); return;
        }
        Path output = Paths.get(args[2]);
        SolveOptions.Mode mode = args.length > 3 && "DEPTH_3D".equals(args[3])
                ? SolveOptions.Mode.DEPTH_3D : SolveOptions.Mode.PLAN_2D;
        OutputValidator validator = new OutputValidator(new OutputGeoJsonReader());
        // профиль ресурса задаётся -Dresource и должен использоваться и при проверке результата
        SolveOptions options = SolveOptions.builder().mode(mode)
                .resource(System.getProperty("resource", "heat")).build();
        if ("solve".equals(args[0])) {
            if (output.toAbsolutePath().getParent() != null) { Files.createDirectories(output.toAbsolutePath().getParent()); }
            List<Variant> variants = args.length > 3 && "stub".equals(args[3])
                    ? new StubRoutingEngine().solve(input, SolveOptions.defaults())
                    : new HeatRoutingEngine(new RoutingParams()).solve(input, options);
            // как и в API: число ошибок/предупреждений проверки видно прямо в сводке варианта
            ValidationReport pre = validator.validate(input, variants, options);
            new GeoJsonWriter().write(JobWorker.withValidationSummary(variants, pre), output);
        } else if (!"validate".equals(args[0])) { throw new IllegalArgumentException("Неизвестная команда"); }
        ValidationReport report = validator.validate(input, output, options);
        Path reportPath = Paths.get(output + ".validation.json");
        json.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        System.out.println("validation=" + reportPath + ", errors=" + report.getErrorCount());
        if (!report.isValid()) { System.exit(2); }
    }
}
