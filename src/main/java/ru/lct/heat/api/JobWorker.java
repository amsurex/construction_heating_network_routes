package ru.lct.heat.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.lct.heat.io.*;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.persistence.*;
import ru.lct.heat.routing.RoutingEngine;
import ru.lct.heat.routing.SolveOptions;
import ru.lct.heat.validation.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class JobWorker {
    private final JobRepository jobs;
    private final GeoJsonReader reader;
    private final GeoJsonWriter writer;
    private final RoutingEngine engine;
    private final JobStorage storage;
    private final ObjectMapper json;
    private final int maxVariants;
    private final long maxResultBytes;
    private final OutputValidator validator;
    private final Set<UUID> cancellationRequests = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Thread> running = new ConcurrentHashMap<>();

    public JobWorker(JobRepository jobs, GeoJsonReader reader, GeoJsonWriter writer,
                     @Qualifier("jobRoutingEngine") RoutingEngine engine, JobStorage storage,
                     ObjectMapper json, @org.springframework.beans.factory.annotation.Value("${app.routing.max-variants:3}") int maxVariants,
                     @org.springframework.beans.factory.annotation.Value("${app.storage.max-result-bytes:524288000}") long maxResultBytes,
                     OutputValidator validator) {
        this.jobs = jobs; this.reader = reader; this.writer = writer; this.engine = engine;
        this.storage = storage; this.json = json; this.maxVariants = maxVariants;
        this.maxResultBytes = maxResultBytes; this.validator = validator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        // Один инстанс: незавершённые задания после рестарта получают явный конечный статус.
        for (Job job : jobs.findByStatusIn(List.of(Job.Status.RUNNING, Job.Status.UPLOADED))) {
            job.setStatus(Job.Status.FAILED);
            job.setErrorCode("INTERRUPTED");
            job.setErrorMessage("Расчёт прерван перезапуском сервиса; загрузите файл повторно");
            job.setFinishedAt(LocalDateTime.now());
            jobs.save(job);
        }
    }

    @Async("jobExecutor")
    public void run(UUID id) {
        Job job = jobs.findById(id).orElseThrow();
        if (job.getStatus() == Job.Status.CANCELLED || cancellationRequests.contains(id)) {
            cancellationRequests.remove(id);
            return;
        }
        running.put(id, Thread.currentThread());
        long started = System.nanoTime();
        job.setStatus(Job.Status.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        jobs.saveAndFlush(job);
        try {
            GeoJsonReader.Result input = reader.readValidated(Paths.get(job.getInputPath()));
            log.info("Job {}: вход прочитан, ОКС {}, ограничений {}", id,
                    input.getInput().getConnectionPoints().size(), input.getInput().getRestrictions().size());
            job.setDiagnosticsJson(json.writeValueAsString(input.getDiagnostics()));
            SolveOptions options = options(job);
            List<Variant> variants = engine.solve(input.getInput(), options);
            checkCancelled(id);
            Path temporary = storage.file(id, "result.tmp");
            Path result = storage.file(id, "result.geojson");
            writer.write(variants, temporary, maxResultBytes);
            // Проверяем именно записанный GeoJSON, независимо от внутреннего checker ядра.
            ValidationReport report = validator.validate(input.getInput(), temporary, options);
            variants = withValidationSummary(variants, report);
            writer.write(variants, temporary, maxResultBytes);
            report = validator.validate(input.getInput(), temporary, options);
            checkCancelled(id);
            Path reportTemp = storage.file(id, "validation.tmp");
            json.writeValue(reportTemp.toFile(), report);
            storage.publish(reportTemp, storage.file(id, "validation.json"));
            storage.publish(temporary, result);
            job.setResultPath(result.toString());
            job.setStatus(Job.Status.DONE);
            log.info("Job {}: готов, вариантов {}, ошибок валидатора {}, {} мс", id, variants.size(),
                    report.getErrorCount(), (System.nanoTime() - started) / 1_000_000);
        } catch (CancellationException e) {
            log.info("Job {}: отменён", id);
            job.setStatus(Job.Status.CANCELLED);
            job.setErrorCode("CANCELLED");
            job.setErrorMessage("Расчёт отменён пользователем");
        } catch (Exception e) {
            log.error("Job {}: ошибка расчёта", id, e);
            job.setStatus(Job.Status.FAILED);
            job.setErrorCode(e instanceof HeatRoutingException ? ((HeatRoutingException) e).getCode()
                    : e instanceof OutputSizeLimitException ? "OUTPUT_TOO_LARGE" : "CALCULATION_FAILED");
            job.setErrorMessage(e instanceof HeatRoutingException ? e.getMessage() : "Ошибка расчёта; подробности в журнале сервиса");
        } finally {
            running.remove(id);
            cancellationRequests.remove(id);
        }
        job.setFinishedAt(LocalDateTime.now());
        jobs.saveAndFlush(job);
    }

    /** Ставит в очередь отмену и прерывает worker, если расчёт уже выполняется. */
    public void cancel(UUID id) {
        cancellationRequests.add(id);
        Thread thread = running.get(id);
        if (thread != null) { thread.interrupt(); }
    }

    private void checkCancelled(UUID id) {
        if (cancellationRequests.contains(id) || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    /** Проставляет в каждую сводку число ошибок и предупреждений проверки (используется и в CLI). */
    public static List<Variant> withValidationSummary(List<Variant> variants, ValidationReport report) {
        List<Variant> result = new ArrayList<>();
        for (Variant variant : variants) {
            Map<String, Object> extra = new LinkedHashMap<>(variant.getSummary().getExtra());
            extra.put("validation_errors", report.getErrorCount());
            extra.put("validation_warnings", report.getWarningCount());
            result.add(variant.toBuilder().summary(variant.getSummary().toBuilder().extra(extra).build()).build());
        }
        return result;
    }

    private SolveOptions options(Job job) throws java.io.IOException {
        if (job.getOptionsJson() == null) {
            return SolveOptions.builder().mode(job.getMode()).maxVariants(maxVariants).build();
        }
        JsonNode data = json.readTree(job.getOptionsJson());
        return SolveOptions.builder().mode(job.getMode()).maxVariants(maxVariants)
                .resource(data.path("resource").asText("heat"))
                .pinTieInIds(ids(data.path("pinTieInIds")))
                .excludeTieInIds(ids(data.path("excludeTieInIds")))
                .excludeRestrictionIds(ids(data.path("excludeRestrictionIds"))).build();
    }

    private static Set<String> ids(JsonNode values) {
        Set<String> ids = new LinkedHashSet<>();
        if (values.isArray()) { values.forEach(value -> ids.add(value.asText())); }
        return ids;
    }
}
