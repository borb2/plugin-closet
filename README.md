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

## Managing what is installed

The Installed Plugins menu lists every jar in the folder. Click one to open it:

- **Update to Latest** downloads the newest build and deletes the old jar.
- **Change Version** lists every build the source publishes and installs the one you pick.
- **Uninstall** deletes the jar after a confirmation. Config folders are left alone.
- **View Logs** prints a link to a read-only web page with just that plugin's log lines
  (find, copy-all, go-to-top). The link only works from the IP of the player who clicked
  it, expires after a few minutes, and cannot run commands. Port and host live under
  `log-viewer` in config.yml.

Anything downloaded is active after a server restart; nothing is hot-loaded.

### Behind a tunnel or reverse proxy

Exposing another public port is usually the wrong move on a hosted node. Point a Cloudflare
Tunnel (or any reverse proxy) at the viewer instead, give it a loopback allocation so the
port is never published publicly, and tell the plugin what the outside world sees:

```yaml
log-viewer:
  bind: "0.0.0.0"                              # inside a container this is always right
  public-url: "https://panel.example.org"      # used verbatim for the link
  trusted-proxies: ["172.18.0.1"]              # ONLY the proxy in front of this server
```

`trusted-proxies` is what lets `CF-Connecting-IP` / `X-Forwarded-For` stand in for the
socket address — every address listed can claim to be any player, so list the one proxy and
nothing else. Leave it empty and the header is ignored entirely. The console names the
address to add the first time a forwarded request is refused.

## Install from a link

The hopper on the browse screen takes a direct `.jar` URL — a GitHub release asset, a
Jenkins artifact, anything. Progress shows on a boss bar. Nothing verifies these files, so
only paste links you trust.

## Notes

Downloads are checksummed where the source publishes a hash (Modrinth always does, Hangar
usually). A mismatch aborts the install.

Some plugins on Hangar aren't auctually hosted on Hangar and the download button for that plugin will direct you to something like a Jenkins or Github release page. These sort of plugins cant be downloaded.
