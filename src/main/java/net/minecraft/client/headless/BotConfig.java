package net.minecraft.client.headless;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;
import eml.bootstrap.JvmSettings;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * Persistent bot configuration ({@code bot.yml} in the game directory).
 *
 * <p>On the first boot the file does not exist (or is incomplete), so the bot runs the
 * interactive flow: it asks for a username and waits for a manual {@code connect}. Every
 * piece of information gathered that way is written back here, so the next start finds a
 * complete file and connects on its own. Command-line flags always win over the file:
 * {@code --username}/{@code --server}/--{@code port}/{@code --online}/{@code --viewDistance}.
 *
 * <p>The {@code jvm:} block is not read for its effect here - it is consumed by the bootstrap, which
 * launches the JVM this class runs in. It is parsed and written back only so that a {@code save()} on
 * connect does not wipe the flags the user put there (see {@link JvmSettings}).
 */
public final class BotConfig {
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
   private static BotConfig instance;

   private final File file;
   private String username;
   private String server = "";
   private int port = 25565;
   /** Server password for the built-in AutoLogin feature; blank when the server needs none. */
   private String password = "";
   private boolean online;
   /** {@code auto} follows the server's radius; a number caps it. See {@link ViewDistance}. */
   private ViewDistance viewDistance = ViewDistance.AUTO;
   private String language;
   private boolean statusBar = true;
   private boolean autoRespawn;
   private boolean autoJump;
   private boolean autoReconnect = true;
   private boolean pingBeforeConnect = true;
   private int pingTimeoutSeconds = 5;
   private int reconnectDelaySeconds = 5;
   private int reconnectAttempts;
   /** How often to silently renew the Microsoft session while running (minutes); 0 disables it. */
   private int sessionRefreshMinutes = 360;
   private boolean debugPackets;
   private boolean debugConnect = true;
   private boolean debugErrors = true;
   private boolean debugTicks;
   private boolean pluginsDisableOnError = true;
   private int pluginsFailThreshold = 1;
   private String resourcePacks = "accept";
   /** Neural planning strategy for the navigator; off by default, toggled with 'goto neural on|off'. */
   private boolean navigationNeural;
   /** Experience recording for neural training; off by default, toggled with 'goto neural record on|off'. */
   private boolean navigationRecord;
   /** The JVM flags to write back unchanged; see {@link JvmSettings}. */
   private JvmSettings jvm = JvmSettings.empty();

   private BotConfig(final File file) {
      this.file = file;
   }

   /** Loads {@code bot.yml}; a missing or unreadable file simply yields an empty config. */
   public static BotConfig load(final File file) {
      BotConfig config = new BotConfig(file);
      if (file.isFile()) {
         try (InputStream stream = Files.newInputStream(file.toPath())) {
            Object root = new Yaml().load(stream);
            if (root instanceof Map<?, ?> map) {
               config.username = stringValue(map.get("username"), null);
               config.server = stringValue(map.get("server"), "");
               config.port = intValue(map.get("port"), 25565);
               config.password = stringValue(map.get("password"), "");
               config.online = boolValue(map.get("online"), false);
               // 'auto' follows the server's radius; a number (2..32) caps it. YAML may load either a string
               // or a number, so the raw value is normalised before parsing.
               config.viewDistance = ViewDistance.parse(stringValue(map.get("viewDistance"), "auto"));
               config.language = stringValue(map.get("language"), null);
               config.statusBar = boolValue(map.get("statusBar"), true);
               config.autoRespawn = boolValue(map.get("autoRespawn"), false);
               config.autoJump = boolValue(map.get("autoJump"), false);
               config.autoReconnect = boolValue(map.get("autoReconnect"), true);
               config.pingBeforeConnect = boolValue(map.get("pingBeforeConnect"), true);
               config.pingTimeoutSeconds = intValue(map.get("pingTimeoutSeconds"), 5);
               config.reconnectDelaySeconds = intValue(map.get("reconnectDelaySeconds"), 5);
               config.reconnectAttempts = intValue(map.get("reconnectAttempts"), 0);
               config.sessionRefreshMinutes = intValue(map.get("sessionRefreshMinutes"), 360);

               if (map.get("debug") instanceof Map<?, ?> debug) {
                  boolean enabled = boolValue(debug.get("enabled"), false);
                  config.debugPackets = enabled || boolValue(debug.get("packets"), false);
                  config.debugConnect = enabled || boolValue(debug.get("connect"), true);
                  config.debugErrors = enabled || boolValue(debug.get("errors"), true);
                  config.debugTicks = enabled || boolValue(debug.get("ticks"), false);
               }

               if (map.get("plugins") instanceof Map<?, ?> plugins) {
                  config.pluginsDisableOnError = boolValue(plugins.get("disableOnError"), true);
                  config.pluginsFailThreshold = intValue(plugins.get("failThreshold"), 1);
               }

               config.resourcePacks = stringValue(map.get("resourcePacks"), "accept");

               if (map.get("navigation") instanceof Map<?, ?> navigation) {
                  config.navigationNeural = boolValue(navigation.get("neural"), false);
                  config.navigationRecord = boolValue(navigation.get("record"), false);
               }

               // Only kept so save() can write it back; the bootstrap already applied it to this JVM.
               config.jvm = JvmSettings.extract(map);
            }
         } catch (Exception e) {
            LOGGER.warn("Could not parse {}; starting with empty config", file.getName(), e);
         }
      }

      // The agent reads the same block on its own (it runs before this class exists); pushing the parsed
      // values keeps one source of truth for whatever changed on disk since premain.
      eml.util.Debug.set(config.debugPackets, config.debugConnect, config.debugErrors, config.debugTicks);
      eml.hooks.BotResourcePacks.configureFromConfig(config.resourcePacks);
      instance = config;
      return config;
   }

   public static BotConfig instance() {
      return instance;
   }

   /** True when everything needed for an automatic connect (username + server) is present. */
   public boolean isComplete() {
      return this.username != null && !this.username.isBlank() && !this.server.isBlank();
   }

   public String username() {
      return this.username;
   }

   public String server() {
      return this.server;
   }

   public int port() {
      return this.port;
   }

   /** Server password read from {@code bot.yml}; used by AutoLogin as the {@code {{password}}} variable. */
   public String password() {
      return this.password;
   }

   public boolean online() {
      return this.online;
   }

   /** How much world to ask for: {@code auto} (follow the server) or a fixed radius. */
   public ViewDistance viewDistance() {
      return this.viewDistance;
   }

   /** Console language code (en_us/zh_cn/zh_tw/ru_ru/ja_jp), or null for OS auto-detect. */
   public String language() {
      return this.language;
   }

   /** Whether the terminal reserves its top row for live bot status. */
   public boolean statusBar() {
      return this.statusBar;
   }

   /** When true the bot respawns on its own after death; otherwise it waits for the 'respawn' command. */
   public boolean autoRespawn() {
      return this.autoRespawn;
   }

   /** Vanilla auto-jump; off by default because it fights a scripted pathfinder. */
   public boolean autoJump() {
      return this.autoJump;
   }

   /** Re-establish the last target after an unexpected drop (on by default, five seconds apart). */
   public boolean autoReconnect() {
      return this.autoReconnect;
   }

   /** Probe the server with a status ping before opening the login connection. */
   public boolean pingBeforeConnect() {
      return this.pingBeforeConnect;
   }

   /** How long a status probe may take before the attempt counts as failed. */
   public int pingTimeoutSeconds() {
      return Math.max(1, Math.min(30, this.pingTimeoutSeconds));
   }

   /** Quiet period before the first retry, so a restarting server is not hammered. */
   public int reconnectDelaySeconds() {
      return Math.max(1, this.reconnectDelaySeconds);
   }

   /** How many retries to attempt before giving up; zero means try forever. */
   public int reconnectAttempts() {
      return Math.max(0, this.reconnectAttempts);
   }

   /** How often to silently renew the Microsoft session while running; 0 disables the periodic refresh. */
   public int sessionRefreshMinutes() {
      return this.sessionRefreshMinutes <= 0 ? 0 : Math.max(30, this.sessionRefreshMinutes);
   }

   /** Diagnosis switches, mirrored in {@code eml.util.Debug} for the patched-engine hooks. */
   public boolean debugPackets() {
      return this.debugPackets;
   }

   public boolean debugConnect() {
      return this.debugConnect;
   }

   public boolean debugErrors() {
      return this.debugErrors;
   }

   public boolean debugTicks() {
      return this.debugTicks;
   }

   /** Paper-style isolation switch: a plugin that throws is disabled instead of left half-alive. */
   public boolean pluginsDisableOnError() {
      return this.pluginsDisableOnError;
   }

   /** How many failures a plugin may accumulate before it is disabled (1 = immediately). */
   public int pluginsFailThreshold() {
      return Math.max(1, this.pluginsFailThreshold);
   }

   /** {@code accept} answers every pushed pack; {@code decline} refuses optional ones. */
   public String resourcePacks() {
      return this.resourcePacks;
   }

   /** Neural planning strategy for {@code goto}; off by default. */
   public boolean navigationNeural() {
      return this.navigationNeural;
   }

   public void setNavigationNeural(final boolean enabled) {
      this.navigationNeural = enabled;
   }

   /** Record neural decisions and trip outcomes for `goto neural train`; off by default. */
   public boolean navigationRecord() {
      return this.navigationRecord;
   }

   public void setNavigationRecord(final boolean enabled) {
      this.navigationRecord = enabled;
   }

   public void setUsername(final String username) {
      this.username = username;
      this.save();
   }

   /** Persists a connection target; only writes when something actually changed. */
   public synchronized void updateConnection(final String username, final String server, final int port) {
      if (Objects.equals(username, this.username) && server.equals(this.server) && port == this.port) {
         return;
      }

      this.username = username;
      this.server = server;
      this.port = port;
      this.save();
   }

   public synchronized void save() {
      String content = "# EML-HeadlessBOT configuration\n"
         + "# Delete this file (or clear a value) to be asked interactively again.\n"
         + "username: " + (this.username == null ? "" : this.username) + "\n"
         + "server: " + this.server + "\n"
         + "port: " + this.port + "\n"
         + "# Server password for the AutoLogin feature (plain text; leave blank when none is needed).\n"
         + "# It is exposed to autologin.yml rules as {{password}}.\n"
         + "password: " + this.password + "\n"
         + "# online: false (default) plays offline - the username derives a stable UUID and no login runs.\n"
         + "# online: true uses a Microsoft account: on start the bot reuses/refreshes session.yml or prints a\n"
         + "# device-code login prompt (see the 'login' console command). While running, the session is silently\n"
         + "# renewed on the sessionRefreshMinutes schedule below (0 disables it).\n"
         + "online: " + this.online + "\n"
         + "# How much world to ask for: 'auto' (default) follows the server's view distance, which is adopted\n"
         + "# once the login packet names it; a number 2..32 caps it. A headless client pays for every chunk it\n"
         + "# keeps, so a server configured for a large view distance is worth capping here.\n"
         + "viewDistance: " + this.viewDistance.render() + "\n"
         + "language: " + (this.language == null ? "" : this.language) + "\n"
         + "# Pin live account, server, position, health and navigation status to the terminal's top row.\n"
         + "statusBar: " + this.statusBar + "\n"
         + "autoRespawn: " + this.autoRespawn + "\n"
         + "# Vanilla auto-jump walks the player up one-block steps on its own. A scripted pathfinder should own\n"
         + "# the movement, so this defaults to false (options.txt is overridden).\n"
         + "autoJump: " + this.autoJump + "\n"
         + "# Status ping before login: when true the bot first asks the server list endpoint whether it is up\n"
         + "# (and how busy it is), and only then opens the real connection. When false it dials straight away.\n"
         + "pingBeforeConnect: " + this.pingBeforeConnect + "\n"
         + "pingTimeoutSeconds: " + this.pingTimeoutSeconds + "\n"
         + "# Reconnect after a drop that we did not ask for (server restart, kick by timeout, lag).\n"
         + "# reconnectAttempts: 0 retries forever; any other value stops after that many tries.\n"
         + "autoReconnect: " + this.autoReconnect + "\n"
         + "reconnectDelaySeconds: " + this.reconnectDelaySeconds + "\n"
         + "reconnectAttempts: " + this.reconnectAttempts + "\n"
         + "# Microsoft session renewal while the bot runs (online mode only). 0 disables it.\n"
         + "sessionRefreshMinutes: " + this.sessionRefreshMinutes + "\n"
         + "# Diagnostics: enabled turns on everything below; each switch can also be set individually,\n"
         + "# and on the command line with -Deml.debug.<name>=true for a single launch.\n"
         + "debug:\n"
         + "  enabled: false\n"
         + "  packets: " + this.debugPackets + "   # log every inbound packet class\n"
         + "  connect: " + this.debugConnect + "   # why and by whom a connection was closed\n"
         + "  errors: " + this.debugErrors + "     # full stack traces of pipeline errors\n"
         + "  ticks: " + this.debugTicks + "      # periodic tick heartbeat (TPS + stage)\n"
         + "# Plugin isolation (Paper-style): a plugin that throws in onLoad/onEnable, an event handler,\n"
         + "# a scheduled task or its console command is disabled instead of being left half-working.\n"
         + "plugins:\n"
         + "  disableOnError: " + this.pluginsDisableOnError + "\n"
         + "  failThreshold: " + this.pluginsFailThreshold + "   # failures before disabling (1 = immediately)\n"
         + "# Resource packs: 'accept' answers ACCEPTED + SUCCESSFULLY_LOADED without downloading anything\n"
         + "# (a headless client never applies pack contents); 'decline' refuses optional packs.\n"
         + "resourcePacks: " + this.resourcePacks + "\n"
         + "# Neural navigation: a tiny bundled network picks the planning strategy for each leg of a trip\n"
         + "# (normal A*, a wider search, patient digging, a straight tunnel, or a clean stop), using the\n"
         + "# distance to the real target and the surrounding terrain as its inputs. Off by default;\n"
         + "# 'goto neural on|off' toggles it at runtime and writes the choice back here.\n"
         + "# record: logs every neural decision and trip outcome to debug/nav-experience.tsv for\n"
         + "# 'goto neural train', which trains a custom navigation.net from it (off by default).\n"
         + "navigation:\n"
         + "  neural: " + this.navigationNeural + "\n"
         + "  record: " + this.navigationRecord + "\n"
         + "# JVM flags for the bot process. One flag per line; read by the bootstrap when it starts the JVM,\n"
         + "# so a change applies on the next start. Appended after the flags the bootstrap needs, and the JVM\n"
         + "# resolves duplicates last-wins - a line here overrides a default rather than adding to it. With no\n"
         + "# -Xmx (or -XX:MaxRAMPercentage/MaxRAMFraction) present, 384m is used. -cp/-classpath/-jar/-m are\n"
         + "# refused (they would replace the runtime classpath the bootstrap assembles).\n"
         + "# The two lines below are the footprint-tuned default: 384m of heap (a session holds ~86 MB of\n"
         + "# live heap idle and ~170 MB with a world) plus 8-byte object headers, which measured ~44 MB less\n"
         + "# RSS than the old 512m block. No collector is named - G1 is the better one at this heap size.\n"
         + "# Raise the heap for a large viewDistance or heavy plugins; drop it to 256m if you cap viewDistance.\n"
         + "jvm:\n"
         + String.join("\n", this.jvm.renderYaml()) + "\n";
      try {
         Files.writeString(this.file.toPath(), content, StandardCharsets.UTF_8);
      } catch (Exception e) {
         LOGGER.warn("Could not save {}", this.file.getName(), e);
      }
   }

   private static String stringValue(final Object value, final String fallback) {
      return value != null ? String.valueOf(value) : fallback;
   }

   private static int intValue(final Object value, final int fallback) {
      if (value instanceof Number number) {
         return number.intValue();
      }

      if (value != null) {
         try {
            return Integer.parseInt(String.valueOf(value));
         } catch (NumberFormatException e) {
            return fallback;
         }
      }

      return fallback;
   }

   private static boolean boolValue(final Object value, final boolean fallback) {
      return value != null ? Boolean.parseBoolean(String.valueOf(value)) : fallback;
   }
}
