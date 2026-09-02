# PluginCloset

Browse and install Minecraft plugins from inside the game.

Run `/plugincloset` and you get a chest GUI listing plugins from [Modrinth](https://modrinth.com)
and [Hangar](https://hangar.papermc.io). Search them, sort them, filter by platform, click one,
and the right jar for your server drops straight into `/plugins`. Restart and it's there.

No browser, no alt-tabbing, no downloading the wrong version for the wrong Minecraft release.

![Paper](https://img.shields.io/badge/Paper-26.2-blue)
![Folia](https://img.shields.io/badge/Folia-supported-green)
![Java](https://img.shields.io/badge/Java-25-orange)

## Install

Grab the jar from [Releases](../../releases), drop it in `/plugins`, restart.

Then open `plugins/PluginCloset/config.yml` and put a real contact address in the
Modrinth user agent — Modrinth blocks generic ones:

```yaml
sources:
  modrinth:
    user-agent: "PluginCloset/1.0 (you@example.com)"
```

## Commands

| Command | What it does |
|---|---|
| `/plugincloset` | Open the browser |
| `/plugincloset search <query>` | Open it with a search already applied |
| `/plugincloset list` | Show what PluginCloset has installed |
| `/plugincloset reload` | Reload the config, clear the cache |

Permissions are `plugincloset.use` and `plugincloset.admin`, both op by default.
Aliases: `/pcloset`, `/closet`.

## Using it

The top four rows are the results. The row under them is the platform filter — one icon
per platform, click to toggle, they glow when active. Bottom row has paging, the sort
button (relevance, downloads, followers, newest, recently updated) and the search box.

Clicking a plugin downloads it immediately. If you'd rather confirm first, set
`require-confirmation: true` in the config.

It picks the build matching your exact Minecraft version. If there isn't one, it falls back
to the newest build on the same major line and tells you it did that, rather than quietly
installing something that might not run.

## Notes

Downloads are checksummed where the source publishes a hash (Modrinth always does, Hangar
usually). A mismatch aborts the install.

Some plugins on Hangar aren't hosted there — the "download" is a link to the author's own
GitHub releases page. Those can't be installed automatically

Nothing is hot-loaded. Plugins activate on the next restart.

## Building

```
./gradlew build
```

Needs JDK 25. The jar lands in `build/libs/`.

`./gradlew selfcheck` runs the tests — they parse recorded Modrinth and Hangar responses
and check the download path's safety rules.
