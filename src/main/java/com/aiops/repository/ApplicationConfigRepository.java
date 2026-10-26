package com.aiops.repository;

import com.aiops.entity.ApplicationConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ApplicationConfigRepository extends JpaRepository<ApplicationConfig, Long> {

    List<ApplicationConfig> findByNameIn(Collection<String> names);
}
