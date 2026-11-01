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
 * <p>Useful flags: {@code --viewDistance N} (chunks reported to the server, default 4; raise it when
 * a plugin needs a larger world view), {@code --online true}, {@code --gameDir path}.
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

      int viewDistance = Integer.parseInt(args.getOrDefault("viewDistance", String.valueOf(config.viewDistance())));
      HeadlessSession session = new HeadlessSession(gameDirectory, username, online, viewDistance, io);
      session.start();

      try {
         session.client();
      } catch (Exception e) {
         LOGGER.error("Headless client failed to initialise", e);
         System.exit(3);
      }

      Console console = new Console(io, session, stdin);
      console.startAsync();

      // Plugin system: LOAD every jar in <gameDir>/plugins now (instantiate + onLoad, no game access).
      // Enabling is deferred to the connection: BotRuntime.connectionEdge fires onServerConnected once
      // the client enters a world, which runs onEnable and registers listeners/commands. Disconnect
      // disables them (still loaded), and the shutdown below unloads them.
      BotPlugins.init(new File(gameDirectory, "plugins"), session, io);
      // Paper-style isolation: a failing plugin is disabled, never left half-alive.
      BotPlugins.configureFaultPolicy(config.pluginsDisableOnError(), config.pluginsFailThreshold());

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

   private static String ask(final BufferedReader reader, final String question, final String defaultValue) {
      // Same blue input colour as the console prompt (see ConsoleIO), but only when a capable terminal
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
