package top.mcocet.hlbot;

import eml.bootstrap.Bootstrap;
import eml.util.LaunchLog;

/**
 * Jar {@code Main-Class} and relaunch target: the launcher shim, and nothing else.
 *
 * <p>{@code java -jar EML-HeadlessBOT.jar} starts a JVM whose classpath contains ONLY this jar - no
 * Minecraft client jar, no Netty, no {@code com.mojang.logging}. So this class must not touch any type
 * that needs those libraries, not even through a static initialiser: a {@code private static final
 * Logger} field here would fail with {@code NoClassDefFoundError: com/mojang/logging/LogUtils} before
 * {@link #main} ever ran.
 *
 * <p>That is why the real bot lives in {@code eml.app.BotApplication} and is reached reflectively (its
 * constant-pool entry is resolved when the call executes, i.e. once the runtime is in place), while
 * {@link Bootstrap} runs first: it creates the folder layout, downloads the Mojang runtime when it is
 * missing, and relaunches this jar with {@code -javaagent} plus the full classpath - which is also what
 * makes the ASM headless patches get attached.
 */
public final class EML {
   /** Loaded reflectively so this shim never links against the Minecraft side of the classpath. */
   private static final String BOT_ENTRY_POINT = "eml.app.BotApplication";

   private EML() {
   }

   public static void main(final String[] rawArgs) {
      // Publish the game directory as a property BEFORE any logging can initialise log4j, so that
      // log4j2.xml can anchor the log files to the bot directory instead of the process CWD
      // (which differs in IDE runs and is exactly how files would escape the bot's own folder).
      // Bootstrap resolves the same value again; a relaunched child already carries it and is not touched.
      if (System.getProperty("eml.gameDir") == null) {
         System.setProperty("eml.gameDir", Bootstrap.gameDirectory(rawArgs).getAbsolutePath());
      }

      // Returning false means the runtime could not be prepared; the relaunch path never returns.
      if (!Bootstrap.prepare(rawArgs)) {
         System.exit(1);
      }

      try {
         Class<?> application = Class.forName(BOT_ENTRY_POINT);
         application.getMethod("main", String[].class).invoke(null, (Object) rawArgs);
      } catch (ClassNotFoundException e) {
         LaunchLog.error("[launcher] " + BOT_ENTRY_POINT + " is missing from this jar - rebuild it with `mvn package`.");
         System.exit(4);
      } catch (ReflectiveOperationException e) {
         LaunchLog.error("[launcher] the bot main failed", e.getCause() == null ? e : e.getCause());
         System.exit(5);
      }
   }
}
