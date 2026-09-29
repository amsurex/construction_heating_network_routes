package ru.lct.heat.api;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Value;
import ru.lct.heat.persistence.Job;
import ru.lct.heat.routing.SolveOptions;
import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class JobView {
    UUID jobId;
    Job.Status status;
    SolveOptions.Mode mode;
    Long inputSize;
    LocalDateTime createdAt;
    LocalDateTime startedAt;
    LocalDateTime finishedAt;
    String code;
    String message;
    JsonNode diagnostics;
    JsonNode options;
}
