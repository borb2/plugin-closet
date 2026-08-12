package me.sirborb.plugincloset.model;

import java.time.Instant;
import java.util.List;

/**
 * A downloadable file for one version on one platform.
 *
 * <p>The spec's single {@code sha512} field became an algorithm-tagged pair: Modrinth
 * publishes sha512, Hangar publishes sha256 ({@code FileInfo.sha256Hash}). Both are
 * nullable — verification is skipped when absent, never faked.
 *
 * @param external true when Hangar has only an {@code externalUrl} — the file lives on
 *                 someone else's server and the URL may not even be a jar, so these are
 *                 surfaced to the admin rather than downloaded.
 */
public record PluginVersionFile(
        String versionLabel,
        Platform platform,
        List<String> gameVersions,
        String downloadUrl,
        String filename,
        String hashAlgo,
        String hashValue,
        Instant datePublished,
        boolean external
) {
    public boolean hasHash() {
        return hashAlgo != null && hashValue != null && !hashValue.isBlank();
    }
}
