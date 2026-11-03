package eml.app;

import java.io.BufferedReader;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.client.headless.BotConfig;
import net.minecraft.client.headless.BotLang;
import net.minecraft.client.headless.BotTerminal;
import net.minecraft.client.headless.Commands;
import net.minecraft.client.headless.Console;
import net.minecraft.client.headless.ConsoleIO;
import net.minecraft.client.headless.HeadlessSession;
import net.minecraft.client.headless.LayeredLanguage;
import net.minecraft.client.headless.ViewDistance;
import net.minecraft.server.Bootstrap;
import org.slf4j.Logger;
import top.mcocet.hlbot.plugins.BotPlugins;

/**
 * The real bot entry point, launched only once the Mojang runtime is guaranteed to be on the
 * classpath. {@code top.mcocet.hlbot.EML} is the jar {@code Main-Class} and hands
 * over to this class reflectively after the self-check, so that the launcher class itself never
 * references anything that needs a Mojang library at class-initialisation time.
 *
 * <p>Two ways to run it:
 *
 * <ul>
 *   <li>{@code java -jar EML-HeadlessBOT.jar} - fully interactive: it asks for a username, then gives
 *       you a console prompt where {@code connect <host:port>} opens the connection.</li>
 *   <li>{@code java -jar EML-HeadlessBOT.jar --server play.example.com --username Bot} - connects on
 *       start-up and still keeps the console for {@code status}, {@code say}, {@code mine}, ...</li>
 * </ul>
 *
 * <p>Useful flags: {@code --viewDistance auto|N} (chunks reported to the server; {@code auto}, the default,
 * follows the server's own view distance, a number caps it), {@code --online true}, {@code --gameDir path}.
 *
 * <p>Persistent config: {@code <gameDir>/bot.yml} stores username/server/port/online/viewDistance.
 * With a complete file the bot skips the questionnaire and connects on its own; with a missing or
 * incomplete one it runs interactively and writes back whatever the user provides, so the next start
 * is automatic. Flags override the file per run.
 */
public final class BotApplication {
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

   private BotApplication() {
   }

   public static void main(final String[] rawArgs) {
      run(parseArgs(rawArgs));
   }

   private static void run(final Map<String, String> args) {
      // Same order as the vanilla launcher path: version first, then the block/item/registry bootstrap.
      SharedConstants.setVersion(DetectedVersion.tryDetectVersion());
      Bootstrap.bootStrap();
      Commands.bootstrap();

      // Prompt and inline input go to the raw stdout captured before Bootstrap wrapped
      // System.out in a logging pipe; message lines go through log4j (see ConsoleIO).
      ConsoleIO io = new ConsoleIO();
      File gameDirectory = new File(args.getOrDefault("gameDir", args.getOrDefault("cwd", ".")));
      // JLine terminal (tab completion / highlighting / history); log lines print above the
      // input line via JLineLogAppender from here on. Falls back to plain I/O without a tty.
      BotTerminal.init(gameDirectory.toPath());
      Runtime.getRuntime().addShutdownHook(new Thread(BotTerminal::close, "Terminal shutdown"));
      BufferedReader stdin = Console.standardIn();
      // Persistent config (bot.yml): a missing or incomplete file means interactive setup;
      // a complete one connects automatically. Command-line flags always win over the file.
      BotConfig config = BotConfig.load(new File(gameDirectory, "bot.yml"));
      // Console language: --language flag > bot.yml 'language' > OS locale > en_us.
      BotLang.init(args.getOrDefault("language", config.language()));
      // Game-content translations (chat templates, join/leave, deaths) follow the same choice.
      LayeredLanguage.install(gameDirectory, BotLang.code());
      boolean online = args.containsKey("online") ? Boolean.parseBoolean(args.get("online")) : config.online();

      io.printlnRaw("EML-HeadlessBOT (Minecraft " + SharedConstants.getCurrentVersion().name() + ", protocol "
         + SharedConstants.getProtocolVersion() + ")");

      String username = args.get("username");
      if (username == null) {
         username = config.username();
      }

      if (username == null) {
         username = ask(stdin, BotLang.get("bot.ask_username", args.getOrDefault("defaultUser", "Bot")), args.getOrDefault("defaultUser", "Bot"));
         config.setUsername(username);
      }

      // --viewDistance wins over the file; both accept 'auto' (follow the server) or a chunk count.
      String requestedViewDistance = args.get("viewDistance");
      ViewDistance viewDistance = requestedViewDistance == null
         ? config.viewDistance()
         : ViewDistance.parse(requestedViewDistance);
      HeadlessSession session = new HeadlessSession(gameDirectory, username, online, viewDistance, io);
      session.start();

      try {
         session.client();
      } catch (Exception e) {
         LOGGER.error("Headless client failed to initialise", e);
         System.exit(3);
      }

      // Built-in pathfinding (`goto`): walking/swimming/climbing, elytra travel and vehicle riding. The
      // navigator is handed the primitive facades plus a message sink, which is what keeps the whole nav
      // package free of net.minecraft types; the per-tick call lives in BotRuntime.onClientTick.
      eml.action.BotWorld world = new eml.action.BotWorld(session::currentLevel);
      eml.action.BotControls controls = new eml.action.BotControls(session::clientNow);
      top.mcocet.hlbot.nav.NavOutput navOutput = new top.mcocet.hlbot.nav.NavOutput() {
         @Override
         public void line(final String key, final Object... args) {
            io.print(BotLang.get(key, args));
         }

         @Override
         public void error(final String key, final Object... args) {
            io.printError(BotLang.get(key, args));
         }
      };
      top.mcocet.hlbot.nav.Navigator.INSTANCE.install(world, controls, navOutput);
      Commands.register(new top.mcocet.hlbot.nav.GotoCommand());

      // SuperPearl (`sp`): walk to a pearl button, press it and let the pearl carry the body home. Same
      // facades as the navigator - one terrain cache, one key driver - and the same key-based output, so its
      // lines are localised like every other command's. Its config lives next to bot.yml and is seeded on
      // first run; the tick call sits in BotRuntime next to the navigator's.
      top.mcocet.hlbot.superpearl.SuperPearl.INSTANCE.install(gameDirectory, controls, world, navOutput);
      Commands.register(top.mcocet.hlbot.superpearl.SuperPearl.INSTANCE);

      Console console = new Console(io, session, stdin);
      console.startAsync();

      // Plugin system: LOAD every jar in <gameDir>/plugins now (instantiate + onLoad, no game access).
      // Enabling is deferred to the connection: BotRuntime.connectionEdge fires onServerConnected once
      // the client enters a world, which runs onEnable and registers listeners/commands. Disconnect
      // disables them (still loaded), and the shutdown below unloads them.
      BotPlugins.init(new File(gameDirectory, "plugins"), session, io);
      // Paper-style isolation: a failing plugin is disabled, never left half-alive.
      BotPlugins.configureFaultPolicy(config.pluginsDisableOnError(), config.pluginsFailThreshold());

      // Built-in advertising (ported from EMLPga): promo.yml next to bot.yml is seeded on first run, and its
      // `enabled` switch decides whether anything is armed. The instance is wired outside the plugin manager,
      // so no jar and no plugins/ folder are involved.
      installPromo(gameDirectory);

      // Built-in AutoLogin: rule-action login flow driven by autologin.yml, with the password coming
      // from bot.yml. Installed once at startup; its listener stays active and resets on every connect.
      installAutoLogin(gameDirectory);

      String server = args.getOrDefault("server", args.getOrDefault("address", config.server()));
      if (!server.isEmpty()) {
         int port = Integer.parseInt(args.getOrDefault("port", String.valueOf(config.port())));
         String host = server;
         int split = server.lastIndexOf(':');
         if (split > 0) {
            host = server.substring(0, split);
            port = Integer.parseInt(server.substring(split + 1));
         }

         session.connect(host, port);
      }

      try {
         session.awaitTermination();
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         session.shutdown();
      }

      BotPlugins.shutdown();
      // Farewell while the terminal is still up. After close() the appender falls back to the raw byte
      // stream, and on a Windows console the codepage JLine restored no longer matches the stdout.encoding
      // captured at JVM startup - which turned the CJK goodbye into mojibake. The JLine writer emits through
      // the console Unicode API instead, so it renders correctly on every platform, exactly like the chat
      // and status lines above it.
      io.printlnRaw(BotLang.get("bot.bye"));
      // Restore the terminal state before the process leaves.
      BotTerminal.close();
   }

   /**
    * Wires the built-in advertising feature (ported from EMLPga). {@code promo.yml} sits next to
    * {@code bot.yml}: it is seeded from the bundled template on first run, and its {@code enabled} switch
    * decides whether the campaign, the greeter and the console command are armed at all.
    */
   private static void installPromo(final File gameDirectory) {
      File promoFile = new File(gameDirectory, "promo.yml");
      boolean enabled = false;

      try {
         if (!promoFile.isFile()) {
            try (java.io.InputStream template = BotApplication.class.getResourceAsStream("/promo/config.yml")) {
               if (template != null) {
                  java.nio.file.Files.copy(template, promoFile.toPath());
               }
            }
         }

         if (promoFile.isFile()) {
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(promoFile.toPath())) {
               Object root = new org.yaml.snakeyaml.Yaml().load(in);

               if (root instanceof java.util.Map<?, ?> map) {
                  Object flag = map.get("enabled");
                  enabled = flag != null && Boolean.parseBoolean(String.valueOf(flag));
               }
            }
         }
      } catch (Exception failed) {
         LOGGER.warn("Could not read {}", promoFile.getName(), failed);
         return;
      }

      top.mcocet.hlbot.promo.EmlPgaPlugin promo = new top.mcocet.hlbot.promo.EmlPgaPlugin();
      promo.attach("EMLPga", promoFile, top.mcocet.hlbot.plugins.BotPlugins.api());
      promo.onLoad();

      if (!enabled) {
         LOGGER.info("Advertising is off (promo.yml: enabled: false)");
         return;
      }

      promo.onEnable();
      LOGGER.info("Advertising enabled from {}", promoFile.getName());
   }

   /**
    * Wires the built-in AutoLogin feature. {@code autologin.yml} sits next to {@code bot.yml}; it is
    * seeded from the bundled template on first run. The listener is registered regardless of
    * {@code enabled} (the engine simply stays idle while disabled), so {@code autologin reload} can
    * switch it on without a restart.
    */
   private static void installAutoLogin(final File gameDirectory) {
      top.mcocet.hlbot.autologin.AutoLogin autoLogin = new top.mcocet.hlbot.autologin.AutoLogin();
      autoLogin.install(gameDirectory, top.mcocet.hlbot.plugins.BotPlugins.api());
      autoLogin.onLoad();
      autoLogin.onEnable();
      Commands.register(new top.mcocet.hlbot.autologin.AutoLoginCommand(autoLogin));
   }

   private static String ask(final BufferedReader reader, final String question, final String defaultValue) {      // Same blue input colour as the console prompt (see ConsoleIO), but only when a capable terminal
      // answers for it: on a pipe, container or dumb console the raw escape codes would show as garbage.
      boolean colors = BotTerminal.colorsSupported();
      Bootstrap.STDOUT.print(question + (colors ? "\u001B[94m" : ""));
      Bootstrap.STDOUT.flush();
      try {
         String line = reader.readLine();
         if (colors) {
            Bootstrap.STDOUT.print("\u001B[0m");
         }
         Bootstrap.STDOUT.flush();
         return line == null || line.isBlank() ? defaultValue : line.trim();
      } catch (Exception e) {
         return defaultValue;
      }
   }

   private static Map<String, String> parseArgs(final String[] rawArgs) {
      Map<String, String> map = new HashMap<>();

      for (int i = 0; i < rawArgs.length; i++) {
         String key = rawArgs[i];
         if (key.startsWith("--") && i + 1 < rawArgs.length) {
            map.put(key.substring(2), rawArgs[++i]);
         }
      }

      return map;
   }
}
