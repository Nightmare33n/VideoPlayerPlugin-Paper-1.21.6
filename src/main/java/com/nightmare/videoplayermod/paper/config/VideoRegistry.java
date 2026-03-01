package com.nightmare.videoplayermod.paper.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class VideoRegistry {

    private final Logger logger;
    private final Path configRoot;
    private final Path videosProp;
    private final Path urlsProp;
    private final Path videosDir;

    // --- Cache ---
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private Map<String, String> cachedVideos = Collections.emptyMap();
    private long lastVideosPropModified = -1;
    private long lastUrlsPropModified = -1;
    private long lastVideosDirModified = -1;

    public VideoRegistry(Logger logger, Path configRoot) {
        this.logger = logger;
        this.configRoot = configRoot;
        this.videosProp = configRoot.resolve("videos.properties");
        this.urlsProp = configRoot.resolve("urls.properties");
        this.videosDir = configRoot.resolve("videos");
    }

    /**
     * Returns the cached video map. Reloads from disk only when any source file
     * has been modified since the last load, keeping tab-complete and command
     * execution free of unnecessary I/O.
     */
    public Map<String, String> loadVideos() {
        if (!isCacheDirty()) {
            cacheLock.readLock().lock();
            try {
                return cachedVideos;
            } finally {
                cacheLock.readLock().unlock();
            }
        }

        cacheLock.writeLock().lock();
        try {
            // Double-check after acquiring write lock
            if (!isCacheDirty()) {
                return cachedVideos;
            }
            cachedVideos = loadVideosFromDisk();
            lastVideosPropModified = lastModified(videosProp);
            lastUrlsPropModified = lastModified(urlsProp);
            lastVideosDirModified = lastModified(videosDir);
            logger.info("Video registry reloaded: " + cachedVideos.size() + " entries");
            return cachedVideos;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /** Forces a full reload on the next access. */
    public void invalidateCache() {
        cacheLock.writeLock().lock();
        try {
            lastVideosPropModified = -1;
            lastUrlsPropModified = -1;
            lastVideosDirModified = -1;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    public Path getConfigRoot() {
        return configRoot;
    }

    // ---- internals ----

    private boolean isCacheDirty() {
        return lastModified(videosProp) != lastVideosPropModified
                || lastModified(urlsProp) != lastUrlsPropModified
                || lastModified(videosDir) != lastVideosDirModified;
    }

    private Map<String, String> loadVideosFromDisk() {
        Map<String, String> merged = new LinkedHashMap<>();

        mergeProperties(merged, videosProp);
        mergeProperties(merged, urlsProp);

        if (Files.exists(videosDir)) {
            try (Stream<Path> files = Files.list(videosDir)) {
                files.filter(path -> {
                            String name = path.getFileName().toString().toLowerCase();
                            return name.endsWith(".mp4") || name.endsWith(".webm") || name.endsWith(".mkv");
                        })
                        .sorted()
                        .forEach(path -> {
                            String fileName = path.getFileName().toString();
                            int dot = fileName.lastIndexOf('.');
                            String id = dot > 0 ? fileName.substring(0, dot) : fileName;
                            merged.putIfAbsent(id, path.toAbsolutePath().toString());
                        });
            } catch (IOException ex) {
                logger.warning("Failed to scan videos directory: " + ex.getMessage());
            }
        }

        return Collections.unmodifiableMap(merged);
    }

    private void mergeProperties(Map<String, String> target, Path file) {
        if (!Files.exists(file)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            for (String key : properties.stringPropertyNames()) {
                String value = properties.getProperty(key);
                if (value != null && !value.isBlank()) {
                    target.put(key, value);
                }
            }
        } catch (IOException ex) {
            logger.warning("Failed to read " + file + ": " + ex.getMessage());
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (IOException e) {
            return -1L;
        }
    }
}
