package com.veritae.veritae_server.domain.analysisjob;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

    List<AnalysisJob> findByStatusIn(List<AnalysisJobStatus> statuses);
}
