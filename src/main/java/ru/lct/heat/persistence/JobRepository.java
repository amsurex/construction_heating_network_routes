package ru.lct.heat.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.*;

public interface JobRepository extends JpaRepository<Job, UUID> {
    List<Job> findByStatusIn(Collection<Job.Status> statuses);
    Page<Job> findByStatus(Job.Status status, Pageable pageable);
}
