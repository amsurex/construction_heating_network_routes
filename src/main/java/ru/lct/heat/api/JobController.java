package ru.lct.heat.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.*;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heat.io.*;
import ru.lct.heat.persistence.*;
import ru.lct.heat.config.PlatformProperties;
import ru.lct.heat.routing.SolveOptions;
import ru.lct.heat.model.ref.RuleProfiles;
import ru.lct.heat.validation.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {
    private final JobRepository jobs;
    private final JobStorage storage;
    private final GeoJsonReader reader;
    private final JobWorker worker;
    private final ObjectMapper json;
    private final PlatformProperties properties;
    // В очереди хранятся только UUID. Одновременно проверяем одну загрузку, модель затем освобождается.
    private final java.util.concurrent.Semaphore validationSlot = new java.util.concurrent.Semaphore(1, true);

    @Value
    public static class Created {
        UUID jobId;
        int connectionPoints;
        long estimatedSeconds;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Загрузить GeoJSON: проверка входа (422), затем асинхронный расчёт (202)")
    public ResponseEntity<Created> create(@RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "PLAN_2D") SolveOptions.Mode mode,
            @RequestParam(defaultValue = "heat") String resource,
            @RequestParam(name = "pin_tie_in", required = false) String pinTieIn,
            @RequestParam(name = "exclude_tie_in", required = false) String excludeTieIn,
            @RequestParam(name = "exclude_restriction", required = false) String excludeRestriction) throws IOException {
        if (file.isEmpty()) { throw new HeatRoutingException("INVALID_INPUT", null, "Пустой файл"); }
        String profile;
        try { profile = RuleProfiles.load(resource).getName(); }
        catch (IllegalArgumentException e) { throw new HeatRoutingException("BAD_REQUEST", null, e.getMessage()); }
        Set<String> pinned = ids(pinTieIn), excluded = ids(excludeTieIn);
        if (!Collections.disjoint(pinned, excluded)) {
            throw new HeatRoutingException("BAD_REQUEST", null, "Одна точка врезки одновременно закреплена и исключена");
        }
        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("resource", profile);
        requested.put("pinTieInIds", pinned);
        requested.put("excludeTieInIds", excluded);
        requested.put("excludeRestrictionIds", ids(excludeRestriction));
        UUID id = UUID.randomUUID();
        storage.directory(id);
        Path input = storage.file(id, "input.geojson");
        Job job = new Job();
        boolean saved = false;
        try {
            try (InputStream stream = file.getInputStream()) { Files.copy(stream, input); }
            try { validationSlot.acquire(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new HeatRoutingException("BUSY", null, "Загрузка прервана");
            }
            GeoJsonReader.Result validation;
            try { validation = reader.readValidated(input); }
            finally { validationSlot.release(); }
            List<Diagnostic> diagnostics = validation.getDiagnostics();
            int connectionPoints = validation.getInput().getConnectionPoints().size();
            job.setId(id); job.setStatus(Job.Status.UPLOADED); job.setMode(mode);
            job.setInputPath(input.toString()); job.setInputSize(Files.size(input));
            job.setCreatedAt(LocalDateTime.now()); job.setDiagnosticsJson(json.writeValueAsString(diagnostics));
            job.setOptionsJson(json.writeValueAsString(requested));
            jobs.saveAndFlush(job);
            saved = true;
            try { worker.run(id); }
            catch (TaskRejectedException e) {
                job.setStatus(Job.Status.FAILED); job.setErrorCode("BUSY");
                job.setErrorMessage("Очередь расчётов заполнена"); job.setFinishedAt(LocalDateTime.now());
                jobs.saveAndFlush(job);
                throw new HeatRoutingException("BUSY", id, job.getErrorMessage());
            }
            return ResponseEntity.accepted().location(URI.create("/api/v1/jobs/" + id))
                    .body(new Created(id, connectionPoints, properties.estimateSeconds(connectionPoints)));
        } finally {
            if (!saved) { storage.removeUpload(id); }
        }
    }

    private static Set<String> ids(String csv) {
        if (csv == null || csv.isBlank()) { return Collections.emptySet(); }
        Set<String> ids = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) { ids.add(id); }
        }
        return ids;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Статус расчёта и диагностика входа")
    public JobView get(@PathVariable UUID id) throws IOException { return view(find(id)); }

    @GetMapping
    @Operation(summary = "Список заданий, последние первыми")
    public List<JobView> list(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size,
                              @RequestParam(required = false) Job.Status status) throws IOException {
        if (page < 0 || size < 1 || size > 100) {
            throw new HeatRoutingException("BAD_REQUEST", null, "page >= 0, size: 1..100");
        }
        List<JobView> result = new ArrayList<>();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Iterable<Job> selected = status == null ? jobs.findAll(pageable) : jobs.findByStatus(status, pageable);
        for (Job job : selected) {
            result.add(view(job));
        }
        return result;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отменить ожидающий или выполняющийся расчёт")
    public void cancel(@PathVariable UUID id) {
        Job job = find(id);
        if (job.getStatus() == Job.Status.DONE || job.getStatus() == Job.Status.FAILED
                || job.getStatus() == Job.Status.CANCELLED) {
            throw new HeatRoutingException("NOT_CANCELLABLE", id, "Задание уже завершено");
        }
        worker.cancel(id);
        job.setStatus(Job.Status.CANCELLED);
        job.setErrorCode("CANCELLED");
        job.setErrorMessage("Расчёт отменён пользователем");
        job.setFinishedAt(LocalDateTime.now());
        jobs.saveAndFlush(job);
    }

    @GetMapping({"/{id}/result", "/{id}/result.geojson"})
    @Operation(summary = "Скачать GeoJSON результата; 409 до завершения")
    public ResponseEntity<Resource> result(@PathVariable UUID id) throws IOException {
        ready(id);
        return download(storage.file(id, "result.geojson"), "application/geo+json", "result.geojson");
    }

    @GetMapping("/{id}/validation")
    @Operation(summary = "Независимый отчёт проверки записанного результата по ТЗ")
    public ResponseEntity<Resource> validation(@PathVariable UUID id) throws IOException {
        ready(id);
        return download(storage.file(id, "validation.json"), "application/json", "validation.json");
    }

    private Job find(UUID id) {
        return jobs.findById(id).orElseThrow(() -> new HeatRoutingException("NOT_FOUND", id, "Задание не найдено"));
    }
    private void ready(UUID id) {
        Job job = find(id);
        if (job.getStatus() != Job.Status.DONE) { throw new HeatRoutingException("NOT_READY", id, "Результат ещё не готов"); }
    }
    private JobView view(Job job) throws IOException {
        return new JobView(job.getId(), job.getStatus(), job.getMode(), job.getInputSize(), job.getCreatedAt(),
                job.getStartedAt(), job.getFinishedAt(), job.getErrorCode(), job.getErrorMessage(),
                json.readTree(job.getDiagnosticsJson() == null ? "[]" : job.getDiagnosticsJson()),
                json.readTree(job.getOptionsJson() == null ? "{}" : job.getOptionsJson()));
    }
    private ResponseEntity<Resource> download(Path path, String type, String name) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).contentLength(Files.size(path))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(new FileSystemResource(path));
    }
}
