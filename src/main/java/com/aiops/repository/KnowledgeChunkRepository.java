package com.aiops.repository;

import com.aiops.entity.KnowledgeChunk;
import com.aiops.entity.SourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findBySource(SourceType source);

    void deleteBySource(SourceType source);

    List<KnowledgeChunk> findByApplicationIdAndSource(Long applicationId, SourceType source);
}
