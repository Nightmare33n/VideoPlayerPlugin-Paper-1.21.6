package com.receptor.video;

import com.receptor.Receptor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class VideoPlaybackManager {

    private static final VideoPlaybackManager INSTANCE = new VideoPlaybackManager();
    private static final double DEFAULT_FPS = 30.0;
    private static final int MAX_OUTPUT_WIDTH = 854;   // cap output to 480p (854x480) for pipe throughput
    private static final int MAX_OUTPUT_HEIGHT = 480;

    /** Monotonically increasing generation counter so old decode threads can detect they are stale. */
    private final AtomicLong generation = new AtomicLong(0);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Thread decodeThread;
    private volatile Process videoProcess;
    private volatile String currentVideoId = "";
    private volatile String statusText = "";
    private volatile float volume = 1.0f;

    /*
     * Double-buffered frame hand-off from decode thread to render thread.
     * The decode thread writes into whichever FrameBuffer is NOT currently
     * being consumed by the render thread, then atomically swaps.
     */
    private static final class FrameBuffer {
        int[] pixels;  // ABGR, length = w*h
        int width;
        int height;
    }

    private final Object swapLock = new Object();
    private volatile FrameBuffer front;  // render thread reads this
    private volatile long frontSeq;      // sequence number of front buffer

    private DynamicTexture texture;
    private ResourceLocation textureId;
    private Path tempDownloadedFile;
    private int videoWidth;
    private int videoHeight;
    private long lastUploadedSeq;  // render thread: last seq it uploaded

    /* ---------- audio ---------- */
    private AudioPlayer audioPlayer;

    private VideoPlaybackManager() {
    }

    public static VideoPlaybackManager getInstance() {
        return INSTANCE;
    }

    /* ======================= public API ======================= */

    public synchronized void play(String id, String source) {
        stop();
        currentVideoId = id;
        statusText = "Loading: " + id;
        running.set(true);
        lastUploadedSeq = 0;

        long gen = generation.incrementAndGet();
        decodeThread = new Thread(() -> decodeLoop(id, source, gen), "receptor-video-decoder");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    public synchronized void stop() {
        running.set(false);
        // Kill ffmpeg video process first
        Process vp = videoProcess;
        videoProcess = null;
        if (vp != null) vp.destroyForcibly();
        // Stop decode thread
        Thread t = decodeThread;
        decodeThread = null;
        if (t != null) {
            t.interrupt();
            // Wait briefly for the decode thread to finish so it can't write stale frames
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (swapLock) {
            front = null;
            frontSeq = 0;
        }
        statusText = "";
        currentVideoId = "";
        videoWidth = 0;
        videoHeight = 0;
        lastUploadedSeq = 0;
        clearTexture();
        deleteTempFile();
        stopAudio();
    }

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(1f, v));
        AudioPlayer ap = audioPlayer;
        if (ap != null) ap.setVolume(this.volume);
    }

    public boolean isRunning()          { return running.get(); }
    public String  getStatusText()      { return statusText; }
    public float   getVolume()          { return volume; }
    public String  getCurrentVideoId()  { return currentVideoId; }

    /* ======================= render (called on render thread) ======================= */

    // DEV LOGGING: render thread timing
    private long renderCallCount = 0;
    private long lastRenderLogTime = 0;
    private long totalUploadNanos = 0;
    private long uploadCount = 0;
    private long framesSkipped = 0;

    public void render(GuiGraphics context) {
        long renderStart = System.nanoTime();
        renderCallCount++;

        // Snapshot the current front buffer atomically
        FrameBuffer fb;
        long seq;
        synchronized (swapLock) {
            fb  = front;
            seq = frontSeq;
        }

        if (seq != lastUploadedSeq && fb != null) {
            // Count how many frames were skipped (decode produced frames faster than render consumed)
            long skipped = seq - lastUploadedSeq - 1;
            if (skipped > 0) framesSkipped += skipped;

            long uploadStart = System.nanoTime();
            uploadPixels(fb.pixels, fb.width, fb.height);
            long uploadElapsed = System.nanoTime() - uploadStart;
            totalUploadNanos += uploadElapsed;
            uploadCount++;
            lastUploadedSeq = seq;
        }

        // Draw the current texture (holds last frame when no new data)
        if (textureId != null && texture != null && videoWidth > 0 && videoHeight > 0) {
            Minecraft mc = Minecraft.getInstance();
            int screenW = mc.getWindow().getGuiScaledWidth();
            int screenH = mc.getWindow().getGuiScaledHeight();
            context.blit(RenderPipelines.GUI_TEXTURED, textureId,
                    0, 0,
                    0.0f, 0.0f,
                    screenW, screenH,
                    videoWidth, videoHeight,
                    videoWidth, videoHeight);
        }

        // DEV LOG: every 2 seconds
        long now = System.nanoTime();
        if (lastRenderLogTime == 0) lastRenderLogTime = now;
        long elapsed = now - lastRenderLogTime;
        if (elapsed >= 2_000_000_000L) {
            double sec = elapsed / 1_000_000_000.0;
            double renderFps = renderCallCount / sec;
            double avgUploadMs = uploadCount > 0 ? (totalUploadNanos / (double) uploadCount / 1_000_000.0) : 0;
            Receptor.LOGGER.info("[DEV-RENDER] renderFPS={} uploads={} avgUploadMs={} framesSkipped={} frontSeq={}",
                    String.format("%.1f", renderFps), uploadCount,
                    String.format("%.2f", avgUploadMs), framesSkipped, seq);
            renderCallCount = 0;
            totalUploadNanos = 0;
            uploadCount = 0;
            framesSkipped = 0;
            lastRenderLogTime = now;
        }
    }

    /* ======================= decode thread ======================= */

    private void decodeLoop(String id, String source, long myGeneration) {
        Process ffmpegProc = null;
        try {
            Path file = resolveSource(source);

            // Locate ffmpeg (required for video decoding)
            String ffmpegCmd = findExecutable("ffmpeg", "ffmpeg.exe");
            if (ffmpegCmd == null) {
                statusText = "Error: ffmpeg not found";
                Receptor.LOGGER.error("ffmpeg not found — cannot play video. Install ffmpeg.");
                return;
            }

            // ---- Probe video metadata (dimensions + FPS) via ffprobe ----
            int vw = 0, vh = 0;
            double fps = DEFAULT_FPS;
            String ffprobeCmd = findExecutable("ffprobe", "ffprobe.exe");
            if (ffprobeCmd != null) {
                try {
                    ProcessBuilder probePb = new ProcessBuilder(
                            ffprobeCmd, "-v", "error",
                            "-select_streams", "v:0",
                            "-show_entries", "stream=width,height,r_frame_rate",
                            "-of", "csv=p=0",
                            file.toAbsolutePath().toString());
                    probePb.redirectError(ProcessBuilder.Redirect.DISCARD);
                    Process probe = probePb.start();
                    String out = new String(probe.getInputStream().readAllBytes()).trim();
                    probe.waitFor(5, TimeUnit.SECONDS);
                    // Expected format: "480,360,15/1" or "h264,Main,1920,1080,30000/1001"
                    // ffprobe may prefix codec info — find the w,h,fps portion
                    String[] parts = out.split(",");
                    if (parts.length >= 3) {
                        // Parse from end: last part is fps fraction, before that height, before that width
                        String fpsStr = parts[parts.length - 1].trim();
                        vh = Integer.parseInt(parts[parts.length - 2].trim());
                        vw = Integer.parseInt(parts[parts.length - 3].trim());
                        String[] frac = fpsStr.split("/");
                        fps = Double.parseDouble(frac[0]);
                        if (frac.length > 1) {
                            double denom = Double.parseDouble(frac[1]);
                            if (denom > 0) fps /= denom;
                        }
                    }
                } catch (Exception e) {
                    Receptor.LOGGER.warn("ffprobe metadata extraction failed: {}", e.getMessage());
                }
            }

            if (vw <= 0 || vh <= 0) {
                Receptor.LOGGER.warn("Could not probe video dimensions, defaulting to 480x360");
                vw = 480;
                vh = 360;
            }

            long frameIntervalNanos = (long) (1_000_000_000.0 / fps);

            // Scale down if needed to keep pipe throughput manageable
            int outW = vw, outH = vh;
            String scaleFilter = null;
            if (vw > MAX_OUTPUT_WIDTH || vh > MAX_OUTPUT_HEIGHT) {
                double scaleX = (double) MAX_OUTPUT_WIDTH / vw;
                double scaleY = (double) MAX_OUTPUT_HEIGHT / vh;
                double scale = Math.min(scaleX, scaleY);
                outW = ((int) (vw * scale)) & ~1;  // ensure even
                outH = ((int) (vh * scale)) & ~1;
                scaleFilter = "scale=" + outW + ":" + outH;
            }

            Receptor.LOGGER.info("Playing '{}': {}x{} (output {}x{}) @ {} fps — source={}",
                    id, vw, vh, outW, outH, String.format("%.2f", fps), file);

            // ---- Start audio ----
            startAudio(file);
            statusText = "Playing: " + id;

            // ---- Start ffmpeg for raw RGBA frame output ----
            // Using ffmpeg instead of JCodec because JCodec 0.2.5 doesn't properly
            // reorder H.264 B-frames from decode order to presentation order,
            // which caused the "convulsing back and forth" visual glitch.
            java.util.List<String> ffmpegArgs = new java.util.ArrayList<>();
            ffmpegArgs.add(ffmpegCmd);
            ffmpegArgs.add("-i");
            ffmpegArgs.add(file.toAbsolutePath().toString());
            if (scaleFilter != null) {
                ffmpegArgs.add("-vf");
                ffmpegArgs.add(scaleFilter);
            }
            ffmpegArgs.add("-f");
            ffmpegArgs.add("rawvideo");
            ffmpegArgs.add("-pix_fmt");
            ffmpegArgs.add("rgba");
            ffmpegArgs.add("-v");
            ffmpegArgs.add("quiet");
            ffmpegArgs.add("-nostdin");
            ffmpegArgs.add("pipe:1");
            ProcessBuilder videoPb = new ProcessBuilder(ffmpegArgs);
            videoPb.redirectError(ProcessBuilder.Redirect.DISCARD);
            ffmpegProc = videoPb.start();
            this.videoProcess = ffmpegProc;

            InputStream frameStream = ffmpegProc.getInputStream();
            int frameBytesLen = outW * outH * 4;
            // Use BufferedInputStream with large buffer to avoid pipe stalls
            java.io.BufferedInputStream bufferedStream = new java.io.BufferedInputStream(frameStream, frameBytesLen * 4);
            byte[] frameBytes = new byte[frameBytesLen];
            int[] framePixels = new int[outW * outH];

            long seq = 0;
            long nextFrameAt = System.nanoTime();

            // DEV LOGGING: decode thread timing
            long decodeLogStart = System.nanoTime();
            long totalReadNanos = 0;
            long totalConvertNanos = 0;
            long totalWaitNanos = 0;
            int framesDecoded = 0;
            int fellBehindCount = 0;

            while (running.get() && generation.get() == myGeneration) {
                // Read exactly one frame of raw RGBA pixels
                long readStart = System.nanoTime();
                int totalRead = 0;
                while (totalRead < frameBytesLen) {
                    int n = bufferedStream.read(frameBytes, totalRead, frameBytesLen - totalRead);
                    if (n == -1) break;
                    totalRead += n;
                }
                if (totalRead < frameBytesLen) break; // EOF
                long readElapsed = System.nanoTime() - readStart;
                totalReadNanos += readElapsed;

                // Reinterpret RGBA bytes as little-endian ints
                long convertStart = System.nanoTime();
                ByteBuffer.wrap(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
                        .asIntBuffer().get(framePixels, 0, outW * outH);

                // Publish to front buffer (clone because we reuse framePixels array)
                FrameBuffer fb = new FrameBuffer();
                fb.pixels = framePixels.clone();
                fb.width = outW;
                fb.height = outH;
                long convertElapsed = System.nanoTime() - convertStart;
                totalConvertNanos += convertElapsed;

                seq++;
                synchronized (swapLock) {
                    front = fb;
                    frontSeq = seq;
                }

                framesDecoded++;

                // ---- Pace at video FPS: hybrid sleep + spin-wait for precision ----
                nextFrameAt += frameIntervalNanos;
                long now = System.nanoTime();
                long remaining = nextFrameAt - now;
                if (remaining > 0) {
                    long waitStart = System.nanoTime();
                    // Sleep for bulk of wait, leaving 2ms for spin
                    long sleepNanos = remaining - 2_000_000L;
                    if (sleepNanos > 0) {
                        Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
                    }
                    // Spin-wait for the last ~2ms for precise timing
                    while (System.nanoTime() < nextFrameAt) {
                        Thread.onSpinWait();
                    }
                    totalWaitNanos += System.nanoTime() - waitStart;
                } else {
                    // Fell behind — reset clock to avoid burst catching up
                    fellBehindCount++;
                    nextFrameAt = System.nanoTime();
                }

                // DEV LOG: every 2 seconds from decode thread
                long decodeNow = System.nanoTime();
                long decodeElapsed = decodeNow - decodeLogStart;
                if (decodeElapsed >= 2_000_000_000L) {
                    double sec = decodeElapsed / 1_000_000_000.0;
                    double decodeFps = framesDecoded / sec;
                    double avgReadMs = framesDecoded > 0 ? (totalReadNanos / (double) framesDecoded / 1_000_000.0) : 0;
                    double avgConvertMs = framesDecoded > 0 ? (totalConvertNanos / (double) framesDecoded / 1_000_000.0) : 0;
                    double avgWaitMs = framesDecoded > 0 ? (totalWaitNanos / (double) framesDecoded / 1_000_000.0) : 0;
                    Receptor.LOGGER.info("[DEV-DECODE] decodeFPS={} frames={} avgReadMs={} avgConvertMs={} avgWaitMs={} fellBehind={}",
                            String.format("%.1f", decodeFps), framesDecoded,
                            String.format("%.2f", avgReadMs),
                            String.format("%.2f", avgConvertMs),
                            String.format("%.2f", avgWaitMs),
                            fellBehindCount);
                    decodeLogStart = decodeNow;
                    totalReadNanos = 0;
                    totalConvertNanos = 0;
                    totalWaitNanos = 0;
                    framesDecoded = 0;
                    fellBehindCount = 0;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            Receptor.LOGGER.error("Failed to play video '{}' from source '{}'", id, source, ex);
            statusText = "Error: " + id;
        } finally {
            if (ffmpegProc != null) ffmpegProc.destroyForcibly();
            // Only update shared state if still the current generation
            if (generation.get() == myGeneration) {
                this.videoProcess = null;
                running.set(false);
                deleteTempFile();
            }
        }
    }

    /* ======================= helpers ======================= */

    private Path resolveSource(String source) throws IOException, InterruptedException {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            statusText = "Downloading video...";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();

            Path downloaded = Files.createTempFile("receptor-video-", ".mp4");
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(downloaded));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                Files.deleteIfExists(downloaded);
                throw new IOException("Unexpected HTTP status: " + response.statusCode());
            }
            tempDownloadedFile = downloaded;
            return downloaded;
        }

        Path localPath = Path.of(source);
        if (!Files.exists(localPath)) {
            throw new IOException("Video file does not exist on client: " + source);
        }
        return localPath;
    }

    /**
     * Upload pre-converted ABGR pixels to the GPU texture using bulk memcpy.
     * Called on the render thread only.
     */
    private void uploadPixels(int[] pixels, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        this.videoWidth = w;
        this.videoHeight = h;

        // Fast path: reuse existing NativeImage (same dimensions) — bulk memcpy
        if (texture != null && textureId != null) {
            NativeImage existing = texture.getPixels();
            if (existing != null && existing.getWidth() == w && existing.getHeight() == h) {
                bulkCopyToNativeImage(existing, pixels, w, h);
                texture.upload();
                return;
            }
        }

        // Slow path: first frame or dimension change
        NativeImage nativeImage = new NativeImage(w, h, false);
        bulkCopyToNativeImage(nativeImage, pixels, w, h);

        if (texture != null) {
            texture.close();
            texture = null;
        }
        if (textureId == null) {
            textureId = ResourceLocation.parse("receptor:video_overlay");
        }

        texture = new DynamicTexture(() -> "receptor_video", nativeImage);
        mc.getTextureManager().register(textureId, texture);
        texture.upload();
    }

    /**
     * Copies an int[] of ABGR pixel data into a NativeImage using a single
     * bulk put via LWJGL's MemoryUtil, instead of per-pixel setPixel() calls.
     * This is ~50-100x faster for large images.
     */
    private static void bulkCopyToNativeImage(NativeImage image, int[] pixels, int w, int h) {
        // NativeImage has a public getPointer() in MC 1.21.6 (Mojang mappings),
        // which gives direct access to the native pixel memory.
        try {
            long ptr = image.getPointer();
            if (ptr != 0L) {
                IntBuffer intBuf = MemoryUtil.memIntBuffer(ptr, w * h);
                intBuf.put(0, pixels, 0, w * h);
                return;
            }
        } catch (Exception ignored) {
            // Fall through to per-pixel path
        }
        // Fallback: per-pixel (should rarely happen)
        for (int y = 0; y < h; y++) {
            int rowOff = y * w;
            for (int x = 0; x < w; x++) {
                image.setPixel(x, y, pixels[rowOff + x]);
            }
        }
    }

    /* ---------- executable lookup ---------- */

    /** Tries to find a command on the system PATH. Returns the first that works, or null. */
    private static String findExecutable(String... names) {
        for (String cmd : names) {
            try {
                Process p = new ProcessBuilder(cmd, "-version")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return cmd;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /* ---------- audio ---------- */

    private void startAudio(Path videoFile) {
        try {
            audioPlayer = new AudioPlayer();
            audioPlayer.start(videoFile, volume);
        } catch (Exception e) {
            Receptor.LOGGER.warn("Could not start audio: {}", e.getMessage());
            audioPlayer = null;
        }
    }

    private void stopAudio() {
        AudioPlayer ap = audioPlayer;
        audioPlayer = null;
        if (ap != null) ap.stop();
    }

    /* ---------- cleanup ---------- */

    private synchronized void clearTexture() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
        textureId = null;
    }

    private synchronized void deleteTempFile() {
        if (tempDownloadedFile != null) {
            try {
                Files.deleteIfExists(tempDownloadedFile);
            } catch (IOException ignored) {
            }
            tempDownloadedFile = null;
        }
    }
}
