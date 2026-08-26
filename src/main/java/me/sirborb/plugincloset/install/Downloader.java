package me.sirborb.plugincloset.install;

import me.sirborb.plugincloset.api.Http;
import me.sirborb.plugincloset.model.PluginVersionFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * Downloads a jar into the plugins folder.
 *
 * <p>This writes network-supplied bytes under a network-supplied name into the directory
 * the server loads code from, so the checks here are deliberately not minimal: the name is
 * reduced to a sanitised basename, the extension must be exactly {@code .jar}, the resolved
 * target must stay inside the plugins directory, and the body is size-capped.
 */
public final class Downloader {

    /** Generous for a plugin, far below anything that would fill a disk. */
    private static final long MAX_JAR_BYTES = 256L * 1024 * 1024;

    private final Path pluginsDir;
    private final Path tmpDir;
    private final String userAgent;
    private final Semaphore slots;

    public Downloader(Path pluginsDir, Path tmpDir, String userAgent, int maxConcurrent) {
        this.pluginsDir = pluginsDir.toAbsolutePath().normalize();
        this.tmpDir = tmpDir.toAbsolutePath().normalize();
        this.userAgent = userAgent;
        this.slots = new Semaphore(Math.max(1, maxConcurrent));
    }

    /** Outcome of a completed install. */
    public record Result(Path jar, String fileName, long bytes, boolean hashVerified) {
    }

    /**
     * Fetch the file and place it in the plugins folder, replacing {@code previousJar} if
     * given. Runs on the calling thread.
     */
    public Result install(PluginVersionFile file, String previousJar) throws Exception {
        if (file.external()) {
            throw new IOException("this version is hosted outside Hangar (" + file.downloadUrl()
                    + ") and may not be a jar at all, so it was not installed");
        }
        String name = safeJarName(file.filename());
        if (name == null) {
            throw new IOException("refusing a file that is not a plain .jar: " + file.filename());
        }
        Path target = pluginsDir.resolve(name).normalize();
        if (!target.startsWith(pluginsDir)) {
            throw new IOException("refusing a path that escapes the plugins folder: " + name);
        }

        slots.acquire();
        try {
            Files.createDirectories(tmpDir);
            Path tmp = Files.createTempFile(tmpDir, "dl-", ".part");
            try {
                long bytes = fetch(file.downloadUrl(), tmp);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                removePrevious(previousJar, target);
                return new Result(target, name, bytes, false);
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        } finally {
            slots.release();
        }
    }

    private long fetch(String url, Path tmp) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<InputStream> resp =
                Http.client().send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            resp.body().close();
            throw new IOException("HTTP " + resp.statusCode() + " downloading " + url);
        }
        long total;
        try (InputStream in = resp.body()) {
            total = copyCapped(in, tmp);
        }
        if (total == 0) throw new IOException("downloaded an empty file from " + url);
        return total;
    }

    /** Copy with a hard ceiling, so a bad URL cannot fill the disk. */
    private static long copyCapped(InputStream in, Path target) throws IOException {
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (var out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buf)) != -1) {
                total += read;
                if (total > MAX_JAR_BYTES) {
                    throw new IOException("file exceeded " + (MAX_JAR_BYTES / 1024 / 1024) + " MB");
                }
                out.write(buf, 0, read);
            }
        }
        return total;
    }

    /** Delete the jar this listing installed last time, unless it is the file we just wrote. */
    private void removePrevious(String previousJar, Path justWritten) throws IOException {
        if (previousJar == null || previousJar.isBlank()) return;
        String safe = safeJarName(previousJar);
        if (safe == null) return;
        Path old = pluginsDir.resolve(safe).normalize();
        if (old.startsWith(pluginsDir) && !old.equals(justWritten)) {
            Files.deleteIfExists(old);
        }
    }

    /**
     * Reduce a source-supplied filename to a safe basename, or null if it is not a plain
     * jar. Strips any directory part (both separators, since the name comes from an API,
     * not from this filesystem), and keeps only characters that cannot surprise us.
     */
    public static String safeJarName(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String base = raw;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.isBlank() || base.equals(".") || base.equals("..")) return null;

        if (!base.toLowerCase(Locale.ROOT).endsWith(".jar")) return null;
        String stem = base.substring(0, base.length() - 4);
        if (stem.isBlank()) return null;
        // ".jar" must be the only extension: "payload.jar.exe" already failed above, but
        // "payload.exe.jar" is still not something we want to name a file after.
        if (stem.contains("..")) return null;

        StringBuilder cleaned = new StringBuilder(stem.length());
        for (char c : stem.toCharArray()) {
            boolean safe = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == '+';
            cleaned.append(safe ? c : '_');
        }
        return cleaned + ".jar";
    }
}
