package me.sirborb.plugincloset.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.sirborb.plugincloset.PluginCloset;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * A tiny read-only log viewer, served by the JDK's own {@link HttpServer}.
 *
 * <p>Read-only is the whole design: every route serves one static page and none of them
 * accept input, so there is nothing here that could run a command.
 *
 * <p>Access is a per-click grant. Clicking "View Logs" mints a random token bound to the
 * plugin being viewed, to the clicking player's IP, and to a deadline. A request has to
 * present the token <em>and</em> come from that same address, so the link is useless to
 * anyone else who reads it.
 */
public final class LogViewer {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** One click's worth of access. */
    private record Grant(String pluginName, InetAddress viewer, Instant expires) {
        boolean expired() {
            return Instant.now().isAfter(expires);
        }

        boolean sameViewer(InetAddress caller) {
            if (viewer.equals(caller)) return true;
            // A server reached over localhost answers ::1 to a 127.0.0.1 login and back.
            return viewer.isLoopbackAddress() && caller.isLoopbackAddress();
        }
    }

    private final PluginCloset plugin;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    /** Proxy addresses already complained about, so one misconfigured proxy warns once. */
    private final java.util.Set<String> warned = ConcurrentHashMap.newKeySet();

    private HttpServer server;
    private boolean enabled;
    private int port;
    private String host = "";
    private String bind = "0.0.0.0";
    private String publicUrl = "";
    private boolean requireMatchingIp = true;
    private List<Cidr> trustedProxies = List.of();
    private Duration linkFor = Duration.ofMinutes(10);
    private int maxLines = 2000;
    private long tailBytes = 8L * 1024 * 1024;
    private Path logFile;

    public LogViewer(PluginCloset plugin) {
        this.plugin = plugin;
    }

    /** (Re)read config and rebind if the port changed. Safe to call on every reload. */
    public void reload() {
        var config = plugin.getConfig();
        boolean wasEnabled = enabled;
        int oldPort = port;
        String oldBind = bind;

        enabled = config.getBoolean("log-viewer.enabled", true);
        port = config.getInt("log-viewer.port", 8135);
        host = config.getString("log-viewer.host", "").trim();
        bind = config.getString("log-viewer.bind", "0.0.0.0").trim();
        publicUrl = config.getString("log-viewer.public-url", "").trim();
        requireMatchingIp = config.getBoolean("log-viewer.require-matching-ip", true);
        trustedProxies = parseCidrs(config.getStringList("log-viewer.trusted-proxies"),
                plugin.getLogger());
        warned.clear();     // a reload is usually someone fixing exactly that warning
        linkFor = Duration.ofMinutes(Math.max(1, config.getInt("log-viewer.link-minutes", 10)));
        maxLines = Math.max(50, config.getInt("log-viewer.max-lines", 2000));
        tailBytes = Math.max(64L * 1024, config.getLong("log-viewer.max-read-bytes", 8L * 1024 * 1024));
        logFile = serverRoot().resolve(config.getString("log-viewer.log-file", "logs/latest.log"));

        if (server != null && wasEnabled == enabled && oldPort == port && bind.equals(oldBind)) {
            return;     // same socket, only the page settings changed
        }
        stop();
        if (enabled) start();
    }

    private void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            server.createContext("/", this::handle);
            // The async scheduler rather than a pool of its own: a page render is one file read.
            server.setExecutor(plugin.asyncExecutor());
            server.start();
            plugin.getLogger().info("Log viewer listening on " + bind + ":" + port);
        } catch (IOException e) {
            server = null;
            plugin.getLogger().log(Level.WARNING, "Log viewer could not bind port " + port
                    + "; the View Logs button will say so.", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        grants.clear();
    }

    public boolean running() {
        return server != null;
    }

    /**
     * Mint a link for this player to read this plugin's log lines.
     *
     * @return the URL, or null when the viewer is off or failed to bind
     */
    public String grant(Player player, String pluginName) {
        if (server == null) return null;
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) return null;

        grants.values().removeIf(g -> Instant.now().isAfter(g.expires()));
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        grants.put(token, new Grant(pluginName, address.getAddress(), Instant.now().plus(linkFor)));
        return base() + "/l/" + token;
    }

    /**
     * What the link is rooted at. Behind a tunnel or a reverse proxy the port this server
     * listens on is not the port anyone connects to, so a configured public URL wins over
     * anything guessable from here.
     */
    private String base() {
        if (!publicUrl.isBlank()) {
            return publicUrl.endsWith("/")
                    ? publicUrl.substring(0, publicUrl.length() - 1)
                    : publicUrl;
        }
        return "http://" + linkHost() + ":" + port;
    }

    /** How long a fresh link lasts, for the chat message. */
    public Duration linkFor() {
        return linkFor;
    }

    private String linkHost() {
        if (!host.isBlank()) return host;
        String serverIp = plugin.getServer().getIp();
        if (serverIp != null && !serverIp.isBlank() && !serverIp.equals("0.0.0.0")) return serverIp;
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "localhost";
        }
    }

    // --- serving ---

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET")) {
                send(exchange, 405, "text/plain; charset=utf-8", "Only GET is served here.");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String token = path.startsWith("/l/") ? path.substring(3) : "";
            Grant grant = grants.get(token);
            if (!admitted(grant, exchange)) {
                // One answer for "wrong token", "expired" and "wrong machine": a prober
                // should not learn which of the three it hit. The console gets the detail.
                send(exchange, 403, "text/plain; charset=utf-8",
                        "This link is not valid from here. Click View Logs again in-game.");
                return;
            }
            send(exchange, 200, "text/html; charset=utf-8", page(grant.pluginName()));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Log viewer request failed", e);
        }
    }

    /**
     * The three ways in, checked separately so the console can say which one failed. The
     * browser is told none of it.
     */
    private boolean admitted(Grant grant, HttpExchange exchange) {
        InetAddress caller = caller(exchange);
        if (grant == null) {
            plugin.getLogger().info("Log viewer: refused a request from " + literal(caller)
                    + " — unknown link. Links are dropped on reload and when they expire.");
            return false;
        }
        if (grant.expired()) {
            plugin.getLogger().info("Log viewer: refused a request from " + literal(caller)
                    + " — that link has expired. Click View Logs again.");
            return false;
        }
        if (requireMatchingIp && !grant.sameViewer(caller)) {
            plugin.getLogger().warning("Log viewer: refused a request from " + literal(caller)
                    + " — the link was issued to " + literal(grant.viewer())
                    + "." + (mixedFamily(grant.viewer(), caller)
                            ? " Those are IPv6 and IPv4, so this is one machine reaching the"
                                    + " web over one and Minecraft over the other: they can"
                                    + " never match."
                            : " A changing home address does this too.")
                    + " Set log-viewer.require-matching-ip: false to rely on the link secret"
                    + " alone, and put an auth layer in front if the page is reachable"
                    + " outside your network.");
            return false;
        }
        return true;
    }

    /** One address is IPv4 and the other IPv6 — the case no amount of retrying fixes. */
    private static boolean mixedFamily(InetAddress a, InetAddress b) {
        return a.getAddress().length != b.getAddress().length;
    }

    /**
     * Who is really asking. Straight off the socket normally; through a proxy, whatever
     * that proxy says — but only if the socket address is one of the proxies configured by
     * hand. Believing the header from anywhere would let anyone claim any IP and turn the
     * whole grant check into decoration.
     */
    private InetAddress caller(HttpExchange exchange) {
        InetAddress remote = exchange.getRemoteAddress().getAddress();
        String forwarded = exchange.getRequestHeaders().getFirst("CF-Connecting-IP");
        if (forwarded == null) {
            forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        }
        if (forwarded != null && !trusted(remote, trustedProxies) && warned.add(literal(remote))) {
            // Almost always a misconfiguration rather than an attack: the tunnel is up but
            // its address is not in trusted-proxies, so every link 403s. Say which address,
            // once per address — a broken proxy would otherwise fill the log.
            plugin.getLogger().warning("Log viewer: a request arrived from " + literal(remote)
                    + " carrying a forwarded client IP, but that address is not in"
                    + " log-viewer.trusted-proxies, so the link will be refused."
                    + " Add \"" + literal(remote) + "\" there if that is your proxy.");
        }
        InetAddress claimed = forwardedAddress(remote, forwarded, trustedProxies);
        return claimed == null ? remote : claimed;
    }

    /**
     * The address a trusted proxy is speaking for, or null to use the socket address.
     * Pure, so the trust rule can be asserted without a socket.
     */
    public static InetAddress forwardedAddress(InetAddress remote, String header, List<Cidr> trusted) {
        if (header == null || header.isBlank() || !trusted(remote, trusted)) return null;
        // X-Forwarded-For is a chain; the client is the first entry. CF-Connecting-IP is
        // a single address, and the same parse handles it.
        String first = header.split(",")[0].trim();
        if (first.startsWith("[")) {
            int close = first.indexOf(']');
            if (close > 0) first = first.substring(1, close);
        }
        return literalAddress(first);
    }

    public static boolean trusted(InetAddress remote, List<Cidr> trusted) {
        for (Cidr cidr : trusted) {
            if (cidr.contains(remote)) return true;
        }
        return false;
    }

    /** One entry of {@code trusted-proxies}: a literal address, or one with a /bits mask. */
    public record Cidr(byte[] network, int bits) {
        public boolean contains(InetAddress address) {
            byte[] other = address.getAddress();
            if (other.length != network.length) return false;    // never mix v4 and v6
            int whole = bits / 8;
            for (int i = 0; i < whole; i++) {
                if (other[i] != network[i]) return false;
            }
            int rest = bits % 8;
            if (rest == 0) return true;
            int mask = 0xFF << (8 - rest);
            return (other[whole] & mask) == (network[whole] & mask);
        }
    }

    /** Parse the configured list, skipping and reporting anything that is not an address. */
    public static List<Cidr> parseCidrs(List<String> raw, java.util.logging.Logger log) {
        List<Cidr> out = new java.util.ArrayList<>();
        for (String entry : raw) {
            Cidr cidr = parseCidr(entry);
            if (cidr == null) {
                if (log != null) {
                    log.warning("log-viewer.trusted-proxies: ignoring \"" + entry
                            + "\", which is not an IP address or CIDR range.");
                }
                continue;
            }
            out.add(cidr);
        }
        return List.copyOf(out);
    }

    public static Cidr parseCidr(String entry) {
        if (entry == null || entry.isBlank()) return null;
        String text = entry.trim();
        int slash = text.indexOf('/');
        String addressPart = slash < 0 ? text : text.substring(0, slash);
        InetAddress address = literalAddress(addressPart);
        if (address == null) return null;

        int full = address.getAddress().length * 8;
        int bits = full;
        if (slash >= 0) {
            try {
                bits = Integer.parseInt(text.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (bits < 0 || bits > full) return null;
        }
        return new Cidr(address.getAddress(), bits);
    }

    /** Literal addresses only — a hostname here would mean a DNS lookup on every request. */
    private static InetAddress literalAddress(String text) {
        try {
            return InetAddress.ofLiteral(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String literal(InetAddress address) {
        return address == null ? "unknown" : address.getHostAddress();
    }

    private static void send(HttpExchange exchange, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        // Nothing here is meant to be embedded, cached, or to fetch anything of its own.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private String page(String pluginName) throws IOException {
        return render(template(), pluginName, linesFor(pluginName));
    }

    /** Fill the page template. Pure, so the escaping can be asserted without a server. */
    public static String render(String template, String pluginName, List<String> lines) {
        StringBuilder body = new StringBuilder();
        int n = 0;
        for (String line : lines) {
            n++;
            body.append("<div class=\"l ").append(levelClass(line)).append("\">")
                    .append("<span class=\"n\">").append(n).append("</span>")
                    .append("<span class=\"t\">").append(escapeHtml(line)).append("</span>")
                    .append("</div>");
        }
        if (n == 0) {
            body.append("<div class=\"l empty\"><span class=\"n\"></span><span class=\"t\">")
                    .append("Nothing in the log mentions ").append(escapeHtml(pluginName))
                    .append(" — it may not have logged anything since the last restart.")
                    .append("</span></div>");
        }
        return template
                .replace("%plugin%", escapeHtml(pluginName))
                .replace("%count%", Integer.toString(n))
                .replace("%generated%", STAMP.format(Instant.now()))
                .replace("%lines%", body.toString());
    }

    /** This plugin's lines out of the tail of the log. */
    List<String> linesFor(String pluginName) throws IOException {
        return filter(tail(), pluginName, maxLines);
    }

    /**
     * Pure, so the filtering can be asserted without a server or a log file.
     *
     * <p>Paper tags a line with {@code [PluginName]} but tags nothing that spills onto the
     * lines below it. Every real log line opens with its {@code [12:00:00 INFO]} stamp, so
     * anything that does not is a continuation of the line above — an exception header, its
     * frames, its {@code Caused by}. A match starts a run and the next stamped line ends
     * it, which is what keeps a stack trace attached to the line that announced it.
     */
    public static List<String> filter(List<String> log, String pluginName, int maxLines) {
        String tag = "[" + pluginName + "]";
        Deque<String> kept = new ArrayDeque<>();
        boolean following = false;
        for (String line : log) {
            if (line.contains(tag)) {
                following = true;
            } else if (!following || line.startsWith("[")) {
                following = false;
                continue;
            }
            kept.addLast(line);
            while (kept.size() > maxLines) kept.removeFirst();
        }
        return List.copyOf(kept);
    }

    /** The last {@link #tailBytes} of the log, as lines. Busy servers keep big logs. */
    private List<String> tail() throws IOException {
        if (logFile == null || !Files.isRegularFile(logFile)) return List.of();
        long size = Files.size(logFile);
        long from = Math.max(0, size - tailBytes);
        byte[] buf;
        try (var channel = Files.newByteChannel(logFile)) {
            channel.position(from);
            ByteBuffer bytes = ByteBuffer.allocate((int) Math.min(size - from, tailBytes));
            while (bytes.hasRemaining() && channel.read(bytes) > 0) {
                // until the buffer is full or the file ends
            }
            buf = Arrays.copyOf(bytes.array(), bytes.position());
        }
        List<String> lines = List.of(new String(buf, StandardCharsets.UTF_8)
                .replace("\r", "").split("\n", -1));
        // A first line cut in half by the seek is dropped rather than shown mangled.
        return from == 0 || lines.isEmpty() ? lines : lines.subList(1, lines.size());
    }

    private static String levelClass(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.contains("ERROR") || upper.contains("SEVERE")) return "err";
        if (upper.contains("WARN")) return "warn";
        return "info";
    }

    private String template() throws IOException {
        try (InputStream in = plugin.getResource("web/logs.html")) {
            if (in == null) throw new IOException("web/logs.html is missing from the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path serverRoot() {
        return plugin.pluginsDir().getParent();
    }

    public static String escapeHtml(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
