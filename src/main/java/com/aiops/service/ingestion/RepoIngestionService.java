package com.aiops.service.ingestion;

import com.aiops.entity.SourceType;
import com.aiops.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Shallow-clones a project's Git repositories and ingests a bounded sample of source
 * files as knowledge chunks, so code is part of the same RAG context used to answer
 * error questions. Requires the `git` CLI on PATH; doesn't handle private repos
 * (no credential injection into the clone URL).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoIngestionService {

    private static final int MAX_FILES_PER_REPO = 40;
    private static final int MAX_CHARS_PER_FILE = 6000;
    private static final long CLONE_TIMEOUT_SECONDS = 90;

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "vendor", "dist", "build", "target",
            ".idea", ".gradle", "__pycache__", ".venv", "venv", ".next", "coverage", "bin", "obj");

    private static final Set<String> INCLUDED_EXTENSIONS = Set.of(
            "java", "py", "js", "jsx", "ts", "tsx", "go", "rb", "php", "cs",
            "cpp", "cc", "c", "h", "hpp", "rs", "kt", "kts", "scala", "swift",
            "md", "sql", "yaml", "yml", "json", "gradle", "xml");

    private static final Set<String> EXCLUDED_FILENAMES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml");

    private final VectorStoreService vectorStoreService;

    public int ingestRepos(List<String> repoUrls, Long applicationId) {
        if (repoUrls == null || repoUrls.isEmpty()) return 0;
        int total = 0;
        for (String repoUrl : repoUrls) {
            try {
                total += ingestOneRepo(repoUrl, applicationId);
            } catch (Exception e) {
                log.warn("Failed to ingest repo {}: {}", repoUrl, e.getMessage());
            }
        }
        return total;
    }

    private int ingestOneRepo(String repoUrl, Long applicationId) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("aiops-repo-");
        try {
            log.info("Cloning {} (shallow) for code analysis", repoUrl);
            Process process = new ProcessBuilder("git", "clone", "--depth", "1", "--quiet", repoUrl, tempDir.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("git clone timed out after {}s for {}", CLONE_TIMEOUT_SECONDS, repoUrl);
                return 0;
            }
            if (process.exitValue() != 0) {
                log.warn("git clone failed for {} (exit {})", repoUrl, process.exitValue());
                return 0;
            }

            List<Path> candidates = collectCandidateFiles(tempDir);
            int count = 0;
            for (Path file : candidates) {
                try {
                    String relativePath = tempDir.relativize(file).toString().replace('\\', '/');
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    if (content.isBlank()) continue;

                    boolean truncated = content.length() > MAX_CHARS_PER_FILE;
                    if (truncated) content = content.substring(0, MAX_CHARS_PER_FILE);

                    String sourceId = repoUrl + "#" + relativePath;
                    String metadata = String.format(
                            "{\"repoUrl\":\"%s\",\"path\":\"%s\",\"truncated\":%s}",
                            repoUrl.replace("\"", "'"), relativePath.replace("\"", "'"), truncated);

                    vectorStoreService.store(SourceType.CODE, sourceId, relativePath, content, metadata, applicationId);
                    count++;
                } catch (IOException e) {
                    log.debug("Skipped unreadable file {}: {}", file, e.getMessage());
                }
            }
            log.info("Code ingestion complete for {}: {} files stored", repoUrl, count);
            return count;
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** README files first, then a breadth-first sample of the rest, capped at MAX_FILES_PER_REPO. */
    private List<Path> collectCandidateFiles(Path root) throws IOException {
        List<Path> readme = new ArrayList<>();
        List<Path> others = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isInExcludedDir(root, p))
                    .filter(this::isIncludedFile)
                    .sorted(Comparator.comparingInt(Path::getNameCount))
                    .forEach(p -> {
                        if (p.getFileName().toString().toLowerCase().startsWith("readme")) {
                            readme.add(p);
                        } else {
                            others.add(p);
                        }
                    });
        }

        List<Path> ordered = new ArrayList<>(readme);
        ordered.addAll(others);
        return ordered.size() > MAX_FILES_PER_REPO ? ordered.subList(0, MAX_FILES_PER_REPO) : ordered;
    }

    private boolean isInExcludedDir(Path root, Path file) {
        Path relative = root.relativize(file);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (EXCLUDED_DIRS.contains(relative.getName(i).toString())) return true;
        }
        return false;
    }

    private boolean isIncludedFile(Path file) {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase();
        if (EXCLUDED_FILENAMES.contains(lower)) return false;
        if (lower.equals("readme") || lower.startsWith("readme.")) return true;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return INCLUDED_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    private void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of a temp clone
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean up temp clone dir {}: {}", dir, e.getMessage());
        }
    }
}
