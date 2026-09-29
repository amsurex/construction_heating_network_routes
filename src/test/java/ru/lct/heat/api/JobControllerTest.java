package ru.lct.heat.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.core.task.TaskRejectedException;
import ru.lct.heat.io.*;
import ru.lct.heat.persistence.*;
import ru.lct.heat.config.PlatformProperties;
import ru.lct.heat.validation.*;
import org.springframework.data.domain.PageImpl;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class JobControllerTest {
    @TempDir Path directory;
    JobRepository jobs;
    JobWorker worker;
    MockMvc mvc;
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach void setup() throws Exception {
        jobs = mock(JobRepository.class); worker = mock(JobWorker.class);
        PlatformProperties properties = new PlatformProperties();
        mvc = MockMvcBuilders.standaloneSetup(new JobController(jobs, new JobStorage(directory.toString()),
                new GeoJsonReader(new InputValidator()), worker, json, properties))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        when(jobs.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }
    byte[] valid() {
        return ("{\"type\":\"FeatureCollection\",\"features\":["
                + feature("source","source","Point","[39,55]", "") + ","
                + feature("old","heat_network","LineString","[[39,55],[39.001,55]]", ",\"diameter\":300") + ","
                + feature("target","oks_connection_point","Point","[39,55.001]", ",\"flow_tph\":20") + "]}").getBytes();
    }
    String feature(String id, String type, String geometry, String coordinates, String extra) {
        return "{\"type\":\"Feature\",\"properties\":{\"id\":\""+id+"\",\"object_type\":\""+type+"\""+extra
                +"},\"geometry\":{\"type\":\""+geometry+"\",\"coordinates\":"+coordinates+"}}";
    }
    @Test void validatedUploadIsStoredAndScheduled() throws Exception {
        String response = mvc.perform(multipart("/api/v1/jobs").file(new MockMultipartFile("file", "../../unsafe.json",
                "application/geo+json", valid()))).andExpect(status().isAccepted())
                .andExpect(header().exists("Location")).andReturn().getResponse().getContentAsString();
        assertEquals(1, json.readTree(response).path("connectionPoints").asInt());
        assertEquals(6, json.readTree(response).path("estimatedSeconds").asInt());
        UUID id = UUID.fromString(json.readTree(response).get("jobId").asText());
        assertTrue(Files.exists(directory.resolve(id.toString()).resolve("input.geojson")));
        verify(jobs).saveAndFlush(argThat(j -> j.getStatus() == Job.Status.UPLOADED)); verify(worker).run(id);
    }
    @Test void resourceAndExpertChoicesArePersistedForAsyncWorker() throws Exception {
        mvc.perform(multipart("/api/v1/jobs").file(new MockMultipartFile("file", valid()))
                .param("resource", "water").param("pin_tie_in", " 106, 110 ")
                .param("exclude_tie_in", "109").param("exclude_restriction", "19, 20"))
                .andExpect(status().isAccepted());
        org.mockito.ArgumentCaptor<Job> saved = org.mockito.ArgumentCaptor.forClass(Job.class);
        verify(jobs).saveAndFlush(saved.capture());
        com.fasterxml.jackson.databind.JsonNode options = json.readTree(saved.getValue().getOptionsJson());
        assertEquals("water", options.path("resource").asText());
        assertEquals(List.of("106", "110"), json.convertValue(options.path("pinTieInIds"), List.class));
        assertEquals(List.of("109"), json.convertValue(options.path("excludeTieInIds"), List.class));
        assertEquals(List.of("19", "20"), json.convertValue(options.path("excludeRestrictionIds"), List.class));
    }
    @Test void unknownResourceAndConflictingExpertChoicesAre400() throws Exception {
        mvc.perform(multipart("/api/v1/jobs").file(new MockMultipartFile("file", valid()))
                .param("resource", "unknown-resource")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/jobs").file(new MockMultipartFile("file", valid()))
                .param("pin_tie_in", "106").param("exclude_tie_in", "106"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(jobs, worker);
    }
    @Test void spec1InvalidInputIs422WithDiagnosticsAndNoOrphanUpload() throws Exception {
        mvc.perform(multipart("/api/v1/jobs").file(new MockMultipartFile("file", "broken.json", "application/json",
                "{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.diagnostics[0].severity").value("ERROR"));
        verifyNoInteractions(jobs, worker);
        try (java.util.stream.Stream<Path> files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
    @Test void missingJobAndPendingResultHaveDifferentStatuses() throws Exception {
        UUID id = UUID.randomUUID(); when(jobs.findById(id)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/jobs/"+id)).andExpect(status().isNotFound());
        Job job = new Job(); job.setId(id); job.setStatus(Job.Status.RUNNING); when(jobs.findById(id)).thenReturn(Optional.of(job));
        mvc.perform(get("/api/v1/jobs/"+id+"/result")).andExpect(status().isConflict());
    }
    @Test void queueRejectionPersistsFailureAndReturns503() throws Exception {
        doThrow(new TaskRejectedException("full")).when(worker).run(any());
        mvc.perform(multipart("/api/v1/jobs").file(new MockMultipartFile("file", valid())))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("BUSY"));
        verify(jobs, times(2)).saveAndFlush(any());
    }
    @Test void invalidModeIdAndPaginationAre400() throws Exception {
        mvc.perform(multipart("/api/v1/jobs").file(new MockMultipartFile("file", valid())).param("mode","bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/jobs/invalid-id")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/jobs?size=101")).andExpect(status().isBadRequest());
    }

    @Test void listCanBeFilteredByStatus() throws Exception {
        when(jobs.findByStatus(eq(Job.Status.RUNNING), any())).thenReturn(new PageImpl<>(List.of()));
        mvc.perform(get("/api/v1/jobs?status=RUNNING")).andExpect(status().isOk())
                .andExpect(content().json("[]"));
        verify(jobs).findByStatus(eq(Job.Status.RUNNING), any());
    }

    @Test void deleteCancelsActiveJobAndRejectsCompletedJob() throws Exception {
        UUID activeId = UUID.randomUUID();
        Job active = new Job(); active.setId(activeId); active.setStatus(Job.Status.RUNNING);
        when(jobs.findById(activeId)).thenReturn(Optional.of(active));
        mvc.perform(delete("/api/v1/jobs/" + activeId)).andExpect(status().isNoContent());
        verify(worker).cancel(activeId);
        assertEquals(Job.Status.CANCELLED, active.getStatus());

        UUID doneId = UUID.randomUUID();
        Job done = new Job(); done.setId(doneId); done.setStatus(Job.Status.DONE);
        when(jobs.findById(doneId)).thenReturn(Optional.of(done));
        mvc.perform(delete("/api/v1/jobs/" + doneId)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));
    }

    @TestFactory Stream<DynamicTest> everyInvalidFixtureReturnsDiagnostic4xxInsteadOf500() throws Exception {
        Path root = Paths.get("data/invalid");
        com.fasterxml.jackson.databind.JsonNode cases = json.readTree(root.resolve("expected.json").toFile());
        List<DynamicTest> tests = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode expected : cases) {
            tests.add(DynamicTest.dynamicTest(expected.path("file").asText(), () -> {
                byte[] bytes = Files.readAllBytes(root.resolve(expected.path("file").asText()));
                String response = mvc.perform(multipart("/api/v1/jobs").file(new MockMultipartFile(
                                "file", expected.path("file").asText(), "application/geo+json", bytes)))
                        .andExpect(status().is(expected.path("status").asInt()))
                        .andExpect(jsonPath("$.code").value(expected.path("code").asText()))
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                com.fasterxml.jackson.databind.JsonNode body = json.readTree(response);
                assertNotEquals(500, expected.path("status").asInt());
                assertTrue(body.path("diagnostics").isArray() && !body.path("diagnostics").isEmpty());
                boolean found = false;
                for (com.fasterxml.jackson.databind.JsonNode diagnostic : body.path("diagnostics")) {
                    boolean idMatches = expected.path("objectId").isNull()
                            ? diagnostic.path("objectId").isNull()
                            : expected.path("objectId").asText().equals(diagnostic.path("objectId").asText());
                    boolean fieldMatches = expected.path("field").asText().equals(diagnostic.path("field").asText());
                    if (idMatches && fieldMatches && diagnostic.path("message").asText()
                            .contains(expected.path("messageContains").asText())) {
                        found = true;
                    }
                }
                assertTrue(found, body.toPrettyString());
            }));
        }
        return tests.stream();
    }
}
