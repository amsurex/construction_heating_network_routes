package ru.lct.heat.persistence;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import ru.lct.heat.routing.SolveOptions;

@Getter
@Setter
@Entity
@Table(name = "jobs")
public class Job {
    public enum Status { UPLOADED, RUNNING, DONE, FAILED, CANCELLED }
    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SolveOptions.Mode mode;
    @Column(nullable = false, columnDefinition = "text")
    private String inputPath;
    private Long inputSize;
    @Column(columnDefinition = "text")
    private String resultPath;
    @Column(columnDefinition = "text")
    private String errorMessage;
    @Column(columnDefinition = "text")
    private String diagnosticsJson;
    @Column(columnDefinition = "text")
    private String optionsJson;
    @Column(length = 64)
    private String errorCode;
    @Column(nullable = false)
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
