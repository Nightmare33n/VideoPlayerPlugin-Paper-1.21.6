package com.receptor.video;

import com.receptor.Receptor;
import org.bytedeco.javacpp.Loader;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Locates the ffmpeg / ffprobe executables.
 * <p>
 * Prefers the binaries bundled inside the mod jar (org.bytedeco:ffmpeg platform jars, extracted
 * by JavaCPP to ~/.javacpp/cache on first use), so players don't need ffmpeg installed.
 * Falls back to whatever is on the system PATH (e.g. on platforms without a bundled binary).
 * <p>
 * The lookup runs once, on its own daemon thread, and starts as soon as this class is loaded
 * (see {@link #warmUp()}). Callers wait for that result, so stopping a video while the lookup
 * is in progress cannot interrupt it and leave a bogus "not found" behind.
 */
public final class FFmpegLocator {

    private static final ExecutorService LOOKUP_THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "receptor-ffmpeg-locator");
        t.setDaemon(true);
        return t;
    });

    private static final CompletableFuture<String> FFMPEG = CompletableFuture.supplyAsync(
            () -> locate("ffmpeg", org.bytedeco.ffmpeg.ffmpeg.class), LOOKUP_THREAD);
    private static final CompletableFuture<String> FFPROBE = CompletableFuture.supplyAsync(
            () -> locate("ffprobe", org.bytedeco.ffmpeg.ffprobe.class), LOOKUP_THREAD);

    private FFmpegLocator() {
    }

    /** Starts the lookup in the background so the first video doesn't have to wait for it. */
    public static void warmUp() {
        // Loading the class is enough: the static futures above start the lookup.
    }

    /** @return path/command for ffmpeg, or null if unavailable */
    public static String ffmpeg() {
        return FFMPEG.join();
    }

    /** @return path/command for ffprobe, or null if unavailable */
    public static String ffprobe() {
        return FFPROBE.join();
    }

    private static String locate(String name, Class<?> bundledClass) {
        try {
            String bundled = Loader.load(bundledClass);
            if (bundled != null && isRunnable(bundled)) {
                Receptor.LOGGER.info("Using bundled {}: {}", name, bundled);
                return bundled;
            }
        } catch (Throwable t) {
            Receptor.LOGGER.warn("Bundled {} unavailable on this platform ({}), trying system PATH", name, t.toString());
        }

        for (String cmd : new String[]{name, name + ".exe"}) {
            if (isRunnable(cmd)) {
                Receptor.LOGGER.info("Using system {}: {}", name, cmd);
                return cmd;
            }
        }

        Receptor.LOGGER.error("{} not found (neither bundled nor on PATH)", name);
        return null;
    }

    private static boolean isRunnable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "-version")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
