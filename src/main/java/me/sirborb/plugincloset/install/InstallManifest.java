package me.sirborb.plugincloset.install;

import me.sirborb.plugincloset.api.Json;
import me.sirborb.plugincloset.model.PluginListing;
import me.sirborb.plugincloset.model.Source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What PluginCloset itself installed, at {@code plugins/PluginCloset/installed.json}.
 *
 * <p>Exists because matching a listing's display name against {@code plugin.getName()} is
 * unreliable — the two rarely agree — so this is the only trustworthy answer to "is this
 * installed, and at what version".
 */
public final class InstallManifest {

    public record InstalledEntry(Source source, String sourceId, String installedVersion,
                                 Instant installedAt, String jarFileName) {
        public String key() {
            return source.name() + ":" + sourceId;
        }
    }

    private final Path file;
    private final Map<String, InstalledEntry> entries = new ConcurrentHashMap<>();

    public InstallManifest(Path file) {
        this.file = file;
    }

    public Optional<InstalledEntry> get(PluginListing listing) {
        return Optional.ofNullable(entries.get(listing.key()));
    }

    public List<InstalledEntry> all() {
        return List.copyOf(entries.values());
    }

    public void put(InstalledEntry entry) {
        entries.put(entry.key(), entry);
    }

    public void load() throws IOException {
        entries.clear();
        if (!Files.exists(file)) return;
        Object json = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        for (Object e : Json.children(json, "installed")) {
            String sourceName = Json.str(e, "source");
            String sourceId = Json.str(e, "sourceId");
            if (sourceName == null || sourceId == null) continue;
            Source source;
            try {
                source = Source.valueOf(sourceName);
            } catch (IllegalArgumentException unknownSource) {
                continue;   // written by a newer build; leave it alone rather than crash
            }
            InstalledEntry entry = new InstalledEntry(
                    source,
                    sourceId,
                    Json.str(e, "installedVersion"),
                    parseInstant(Json.str(e, "installedAt")),
                    Json.str(e, "jarFileName"));
            entries.put(entry.key(), entry);
        }
    }

    /** Write via a temp file so an interrupted save cannot leave a truncated manifest. */
    public synchronized void save() throws IOException {
        List<Object> list = new ArrayList<>();
        for (InstalledEntry e : entries.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", e.source().name());
            m.put("sourceId", e.sourceId());
            m.put("installedVersion", e.installedVersion());
            m.put("installedAt", e.installedAt().toString());
            m.put("jarFileName", e.jarFileName());
            list.add(m);
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(Map.of("installed", list)), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Instant parseInstant(String text) {
        try {
            return text == null ? Instant.EPOCH : Instant.parse(text);
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
