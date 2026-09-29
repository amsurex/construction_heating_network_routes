package ru.lct.heat.io;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;

@Component
public class JobStorage {
    private final Path root;
    public JobStorage(@Value("${app.storage.dir:./data/uploads}") String root) throws IOException {
        this.root = Paths.get(root).toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }
    public Path directory(UUID id) throws IOException { return Files.createDirectories(root.resolve(id.toString())); }
    public Path file(UUID id, String name) { return root.resolve(id.toString()).resolve(name); }
    public void publish(Path temporary, Path destination) throws IOException {
        try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, destination); }
    }
    public void removeUpload(UUID id) throws IOException {
        Files.deleteIfExists(file(id, "input.geojson"));
        Files.deleteIfExists(root.resolve(id.toString()));
    }
}
