package com.aiops.repository;

import com.aiops.entity.AlertLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AlertLogRepository extends JpaRepository<AlertLog, Long> {

    boolean existsByDatadogId(String datadogId);

    Optional<AlertLog> findByDatadogId(String datadogId);
}
