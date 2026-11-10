package eml.util;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Runtime debug switches, read from the {@code debug:} block of the bot's own {@code bot.yml}.
 *
 * <p>Two things make this class exist. First, the diagnostics have to be readable from the java agent as
 * well as from the running bot: the agent cannot wait for {@code BotConfig} (it transforms classes long
 * before any config object exists), so the file is parsed here directly and independently - using exactly
 * the same path {@code BotConfig} uses, otherwise the two readers would look at different files.
 * Second, every flag is also settable as {@code -Deml.debug.<name>=true} so a trace can be turned on for
 * one launch without editing files.
 *
 * <p>Nothing here ever throws: a debug facility that can break the program it observes is worse than no
 * debug facility, so unreadable or malformed configuration simply means "all switches off".
 */
public final class Debug {
   /** Prefix of the per-launch switches; also what the bootstrap forwards to the child JVM. */
   private static final String PREFIX = "eml.debug.";

   private static volatile boolean tracePackets;
   private static volatile boolean traceConnect = true;
   private static volatile boolean traceErrors = true;
   private static volatile boolean traceTicks;
   private static volatile boolean loaded;

   private Debug() {
   }

   /** Every trace switch, for one-line log messages. */
   public static boolean packets() {
      return tracePackets;
   }

   /** Connection lifecycle: why a connection was closed, and by whom. */
   public static boolean connect() {
      return traceConnect;
   }

   /** Full stack traces for errors that the engine would otherwise only report as a message. */
   public static boolean errors() {
      return traceErrors;
   }

   /** Periodic tick heartbeat (TPS plus connection stage). */
   public static boolean ticks() {
      return traceTicks;
   }

   public static String describe() {
      return "packets=" + tracePackets + ", connect=" + traceConnect + ", errors=" + traceErrors + ", ticks=" + traceTicks;
   }

   /**
    * Loads {@code <gameDir>/bot.yml}. Called by the agent at premain and by {@code BotConfig} when the bot
    * reads its own configuration; the second call is harmless because the same values are parsed.
    */
   public static synchronized void load(final File gameDirectory) {
      File configFile = new File(gameDirectory, "bot.yml");

      if (configFile.isFile()) {
         try (InputStream stream = Files.newInputStream(configFile.toPath())) {
            Object root = new org.yaml.snakeyaml.Yaml().load(stream);

            if (root instanceof Map<?, ?> map && map.get("debug") instanceof Map<?, ?> debug) {
               // "enabled" is the master switch: it turns on everything it does not otherwise override.
               boolean enabled = bool(debug.get("enabled"), false);
               tracePackets = enabled || bool(debug.get("packets"), tracePackets);
               traceConnect = enabled || bool(debug.get("connect"), traceConnect);
               traceErrors = enabled || bool(debug.get("errors"), traceErrors);
               traceTicks = enabled || bool(debug.get("ticks"), traceTicks);
            }
         } catch (Throwable unparsable) {
            // Left at defaults; the absence of a debug log must never be a reason to fail a launch.
         }
      }

      applyOverrides();
      loaded = true;
   }

   /** Command line style switches win over the file, so one launch can be traced without editing it. */
   private static void applyOverrides() {
      tracePackets = override("packets", tracePackets);
      traceConnect = override("connect", traceConnect);
      traceErrors = override("errors", traceErrors);
      traceTicks = override("ticks", traceTicks);
   }

   private static boolean override(final String key, final boolean current) {
      String value = System.getProperty(PREFIX + key);
      return value == null ? current : !"false".equalsIgnoreCase(value);
   }

   /**
    * The {@code eml.debug.*} switches this JVM was started with, sorted so a relaunched command line is
    * reproducible.
    *
    * <p>The bootstrap needs this: it starts the JVM that actually reads the switches, so a property given to
    * the launcher would otherwise be dropped on the floor by the relaunch and the documented "one launch
    * without editing files" would silently do nothing.
    */
   public static List<String> overrides(final java.util.Properties properties) {
      List<String> keys = new ArrayList<>();
      for (String name : properties.stringPropertyNames()) {
         String value = properties.getProperty(name);
         if (name.startsWith(PREFIX) && value != null && !value.isBlank()) {
            keys.add(name);
         }
      }
      Collections.sort(keys);
      return keys;
   }

   /** Used by {@code BotConfig} after it has parsed the same file itself. */
   public static void set(final boolean packets, final boolean connect, final boolean errors, final boolean ticks) {
      tracePackets = packets;
      traceConnect = connect;
      traceErrors = errors;
      traceTicks = ticks;
      applyOverrides();
   }

   public static boolean isLoaded() {
      return loaded;
   }

   private static boolean bool(final Object value, final boolean fallback) {
      return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
   }
}
