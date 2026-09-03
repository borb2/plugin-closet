# PluginCloset

Browse and install Minecraft plugins from inside the game.

Run `/plugincloset` and you get a chest GUI listing plugins from [Modrinth](https://modrinth.com)
and [Hangar](https://hangar.papermc.io). 

![Paper](https://img.shields.io/badge/Paper-26.2-blue)
![Folia](https://img.shields.io/badge/Folia-supported-green)
![Java](https://img.shields.io/badge/Java-25-orange)

Open `plugins/PluginCloset/config.yml` and enter your email or something where (you@example.com) modrinth blocks default useragents

```yaml
sources:
  modrinth:
    user-agent: "PluginCloset/1.0 (you@example.com)"
```

## Notes

Downloads are checksummed where the source publishes a hash (Modrinth always does, Hangar
usually). A mismatch aborts the install.

Some plugins on Hangar aren't auctually hosted on Hangar and the download button for that plugin will direct you to something like a Jenkins or Github release page. These sort of plugins cant be downloaded.
