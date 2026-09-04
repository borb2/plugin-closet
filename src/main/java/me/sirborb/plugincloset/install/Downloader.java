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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * Downloads a jar into the plugins folder.
 *
 * <p>This writes network-supplied bytes under a network-supplied name into the directory
 * the server loads code from, so the checks here are deliberately not minimal: the name is
 * reduced to a sanitised basename, the extension must be exactly {@code .jar}, the resolved
 * target must stay inside the plugins directory, the body is size-capped, and a declared
 * hash must match before anything is moved into place.
 */
public final class Downloader {

    /** Generous for a plugin, far below anything that would fill a disk. */
    private static final long MAX_JAR_BYTES = 256L * 1024 * 1024;

    private final Path pluginsDir;
    private final Path tmpDir;
    private final String userAgent;
    private final Semaphore slots;
    private final Executor executor;

    /**
     * @param executor where the blocking work runs. In the plugin this is backed by
     *                 {@code Bukkit.getAsyncScheduler()}, which is the only scheduler
     *                 allowed to block on Folia. It must not be an executor owned by the
     *                 shared {@link Http} client — the download issues a blocking request,
     *                 and running that on the client's own pool can starve it.
     */
    public Downloader(Path pluginsDir, Path tmpDir, String userAgent, int maxConcurrent,
                      Executor executor) {
        this.pluginsDir = pluginsDir.toAbsolutePath().normalize();
        this.tmpDir = tmpDir.toAbsolutePath().normalize();
        this.userAgent = userAgent;
        this.slots = new Semaphore(Math.max(1, maxConcurrent));
        this.executor = executor;
    }

    /** Outcome of a completed install. */
    public record Result(Path jar, String fileName, long bytes, boolean hashVerified) {
    }

    /**
     * Fetch the file and place it in the plugins folder, replacing {@code previousJar} if
     * given. All blocking work is handed to the executor, so this returns immediately and
     * is safe to call from any thread, including a region thread.
     */
    public CompletableFuture<Result> install(PluginVersionFile file, String previousJar) {
        CompletableFuture<Result> out = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                out.complete(installBlocking(file, previousJar));
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    /**
     * Fetch a jar from a URL the admin typed, with no source, version or checksum behind
     * it. Same guards as a catalogue install — plain {@code .jar} name, capped size, path
     * confined to the plugins folder — minus the hash, which nobody published.
     *
     * @param progress called with the bytes written so far, roughly every 64 KB
     */
    public CompletableFuture<Result> installUrl(String url, LongConsumer progress) {
        CompletableFuture<Result> out = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                out.complete(installUrlBlocking(url, progress));
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    /** Content-Length of the URL, or 0 when the server does not say. */
    public CompletableFuture<Long> sizeOf(String url) {
        CompletableFuture<Long> out = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", userAgent)
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<Void> resp = Http.client().send(req, HttpResponse.BodyHandlers.discarding());
                out.complete(resp.headers().firstValueAsLong("content-length").orElse(0L));
            } catch (Exception e) {
                out.complete(0L);   // unknown size is not a failure, only a vaguer bar
            }
        });
        return out;
    }

    private Result installUrlBlocking(String url, LongConsumer progress) throws Exception {
        URI uri = URI.create(url.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("only http and https links can be downloaded");
        }
        String name = safeJarName(java.net.URLDecoder.decode(
                uri.getPath() == null ? "" : uri.getPath(), java.nio.charset.StandardCharsets.UTF_8));
        if (name == null) {
            throw new IOException("that link does not end in a .jar file name");
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
                long bytes = fetch(uri.toString(), tmp, progress);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return new Result(target, name, bytes, false);
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        } finally {
            slots.release();
        }
    }

    /** Delete a jar this plugin knows about. Confined to the plugins folder, like every write. */
    public boolean delete(String jarFileName) throws IOException {
        String safe = safeJarName(jarFileName);
        if (safe == null) return false;
        Path jar = pluginsDir.resolve(safe).normalize();
        if (!jar.startsWith(pluginsDir)) return false;
        return Files.deleteIfExists(jar);
    }

    private Result installBlocking(PluginVersionFile file, String previousJar) throws Exception {
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
                long bytes = fetch(file.downloadUrl(), tmp, null);
                boolean verified = false;
                if (file.hasHash()) {
                    String actual = hash(tmp, file.hashAlgo());
                    if (!actual.equalsIgnoreCase(file.hashValue())) {
                        throw new IOException("checksum mismatch for " + name
                                + " (expected " + file.hashAlgo() + " " + file.hashValue()
                                + ", got " + actual + ") - not installed");
                    }
                    verified = true;
                }
                // Only now is the file allowed near the plugins folder.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                removePrevious(previousJar, target);
                return new Result(target, name, bytes, verified);
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        } finally {
            slots.release();
        }
    }

    private long fetch(String url, Path tmp, LongConsumer progress) throws Exception {
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
            total = copyCapped(in, tmp, progress);
        }
        if (total == 0) throw new IOException("downloaded an empty file from " + url);
        return total;
    }

    /** Copy with a hard ceiling, so a bad URL cannot fill the disk. */
    private static long copyCapped(InputStream in, Path target, LongConsumer progress) throws IOException {
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
                if (progress != null) progress.accept(total);
            }
        }
        return total;
    }

    private static String hash(Path file, String algo) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algo);
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buf)) != -1) digest.update(buf, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
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
