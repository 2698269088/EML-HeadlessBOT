package eml.bootstrap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import eml.util.LaunchLog;

/**
 * Startup self-check that makes the bot runnable from a single jar, with no launcher assumptions.
 *
 * <p>Runs BEFORE any {@code net.minecraft} class is touched, so it only uses the JDK and the shaded
 * libraries. It then guarantees three things:
 * <ol>
 *   <li>the working-directory layout exists ({@code plugins/}, {@code versions/}, {@code libraries/});</li>
 *   <li>the Minecraft client jar and its library jars are present - if not, they are fetched from the
 *       official Mojang endpoints into this directory and SHA-1 verified;</li>
 *   <li>the JVM that actually runs the bot has that runtime plus this agent jar on its classpath and
 *       {@code -javaagent} attached. If the current process does not (typical: {@code java -jar} with
 *       only our jar, or a plain IDE run), a child JVM is started and this one exits with its code.</li>
 * </ol>
 */
public final class Bootstrap {
   /** Version of the client this bot targets; {@code -Deml.mcVersion} overrides it. */
   public static final String DEFAULT_VERSION = "26.1.2";
   private static final String ENTRY_POINT = "top.mcocet.hlbot.EML";
   private static final String BOOTSTRAPPED = "eml.bootstrapped";

   private Bootstrap() {
   }

   /**
    * @param args the raw command line, used to find {@code --gameDir} before the game dir is parsed
    * @return true when this process may continue into the bot (runtime already on the classpath);
    *         this method never returns normally when it decided to relaunch - it exits the process
    */
   public static boolean prepare(final String[] args) {
      File gameDirectory = gameDirectory(args);
      String version = System.getProperty("eml.mcVersion", DEFAULT_VERSION);

      try {
         ensureDirectories(gameDirectory);
      } catch (IOException e) {
         LaunchLog.error("[bootstrap] could not create the working directories", e);
      }

      if (clientVisible()) {
         LaunchLog.info("[bootstrap] runtime already on the classpath");
         return true;
      }

      // We are already a relaunched child and still cannot see the client: do not loop, report.
      if (Boolean.getBoolean(BOOTSTRAPPED)) {
         LaunchLog.error("[bootstrap] the relaunched process still cannot see net/minecraft/client/Minecraft.class");
         LaunchLog.error("[bootstrap] java.class.path=" + System.getProperty("java.class.path"));
         return false;
      }

      MojangRuntime runtime = new MojangRuntime(gameDirectory, version);
      LaunchLog.info("[bootstrap] gameDir=" + gameDirectory.getAbsolutePath() + " version=" + version);

      if (runtime.findClientJar() == null) {
         LaunchLog.info("[bootstrap] no local " + version + " client jar - downloading from Mojang");
      }

      List<File> classpath;

      try {
         MojangRuntime.Resolved resolved = runtime.resolve(true);

         if (!resolved.complete()) {
            LaunchLog.error("[bootstrap] runtime is incomplete, missing: " + resolved.missing());
            return false;
         }

         classpath = resolved.classpath();
      } catch (Exception e) {
         LaunchLog.error("[bootstrap] could not prepare the Mojang runtime", e);
         LaunchLog.error("[bootstrap] check network access to piston-meta.mojang.com, or place the client jar in "
            + runtime.clientJarPath());
         return false;
      }

      File agent = agentJar(gameDirectory);
      if (agent == null) {
         LaunchLog.error("[bootstrap] cannot determine the agent jar for this run");
         return false;
      }

      classpath.add(0, agent);
      relaunch(agent, classpath, gameDirectory, args);
      return false; // unreachable: relaunch exits
   }

   // ==================== directories ====================

   /** Creates the runtime layout. The plugin folder must exist before the plugin manager scans it. */
   public static void ensureDirectories(final File gameDirectory) throws IOException {
      List<String> folders = List.of("plugins", "versions", "libraries", "logs", "lang");
      IOException first = null;

      for (String folder : folders) {
         File dir = new File(gameDirectory, folder);
         if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            first = new IOException("could not create " + dir.getAbsolutePath());
         }
      }

      if (first != null) {
         throw first;
      }

      LaunchLog.info("[bootstrap] plugins directory: " + new File(gameDirectory, "plugins").getAbsolutePath());
   }

   /** Mirrors {@code BotApplication}'s own flag parsing; kept independent so it can run before it. */
   public static File gameDirectory(final String[] args) {
      for (int i = 0; i + 1 < args.length; i++) {
         if (args[i].startsWith("--") && (args[i].equals("--gameDir") || args[i].equals("--cwd"))) {
            return new File(args[i + 1]);
         }
      }

      return new File(System.getProperty("eml.gameDir", "."));
   }

   /**
    * JFR repository for the relaunched child: {@code <gameDir>/jfr} by default, overridable with
    * {@code -Deml.jfrRepo} (a relative value resolves against the game directory, so it never has to
    * depend on where the CWD happens to be). Plain path composition only - no URI games - because a
    * Windows {@code file:///D:/...} and a Unix {@code file:///...} must both arrive intact at the JVM
    * argument, and the JFR repository accepts a bare filesystem path on every platform.
    */
   static String jfrRepository(final File gameDirectory) {
      String override = System.getProperty("eml.jfrRepo");

      if (override != null && !override.isBlank()) {
         java.nio.file.Path wanted = Path.of(override.trim());
         return (wanted.isAbsolute() ? wanted : gameDirectory.toPath().toAbsolutePath().resolve(wanted)).normalize().toString();
      }

      if (System.getProperty("jdk.rc.repository.path") != null) {
         return System.getProperty("jdk.rc.repository.path");
      }

      return new File(gameDirectory, "jfr").getAbsolutePath();
   }

   // ==================== relaunch ====================

   private static void relaunch(final File agent, final List<File> classpath, final File gameDirectory, final String[] args) {
      List<String> command = new ArrayList<>();
      command.add(javaExecutable().toAbsolutePath().toString());
      command.add("-javaagent:" + agent.getAbsolutePath());
      command.add("-D" + BOOTSTRAPPED + "=true");
      // The agent's premain runs before any argument exists, so the resolved game directory is handed over
      // as a property: otherwise --gameDir would make the bot and the agent read two different bot.yml.
      command.add("-Deml.gameDir=" + gameDirectory.getAbsolutePath());
      // JFR dumps (the vanilla JvmProfiler writes <repository>/debug/*.jfr) are anchored to the bot
      // directory too. The property must be set in the CHILD: the JFR repository is created lazily on
      // the child's first recording, and it is the child that ever records. An explicit
      // -Deml.jfrRepo / -Djdk.rc.repository.path still wins.
      command.add("-Djdk.rc.repository.path=" + jfrRepository(gameDirectory));

      String extra = System.getProperty("eml.jvmArgs");
      if (extra != null && !extra.isBlank()) {
         for (String part : extra.split("\\s+")) {
            if (!part.isBlank()) {
               command.add(part);
            }
         }
      }

      command.add("-cp");
      command.add(joinClasspath(classpath));
      command.add(ENTRY_POINT);

      for (String argument : args) {
         command.add(argument);
      }

      LaunchLog.info("[bootstrap] relaunching with " + classpath.size() + " classpath entries");

      try {
         Process process = new ProcessBuilder(command)
            .directory(gameDirectory)
            .inheritIO()
            .start();
         int code = process.waitFor();
         System.exit(code);
      } catch (IOException e) {
         LaunchLog.error("[bootstrap] failed to start the bot process", e);
         System.exit(1);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         System.exit(1);
      }
   }

   private static String joinClasspath(final List<File> entries) {
      StringBuilder out = new StringBuilder();

      for (File entry : entries) {
         if (out.length() > 0) {
            out.append(File.pathSeparator);
         }

         out.append(entry.getAbsolutePath());
      }

      return out.toString();
   }

   /**
    * The JVM to relaunch into. Asking the running process itself is the platform-neutral answer: it returns
    * exactly the executable that started us, so no file-name convention has to be encoded here. The
    * {@code java.home} lookup is only a fallback for JVMs that do not report their own command.
    */
   private static Path javaExecutable() {
      String command = ProcessHandle.current().info().command().orElse(null);

      if (command != null && !command.isBlank()) {
         return Path.of(command);
      }

      Path home = Path.of(System.getProperty("java.home"));
      Path named = home.resolve("bin").resolve("java");
      Path suffixed = home.resolve("bin").resolve("java.exe");
      return Files.exists(suffixed) ? suffixed : named;
   }

   /** True when the vanilla client classes are already reachable from this process. */
   private static boolean clientVisible() {
      return Bootstrap.class.getClassLoader().getResource("net/minecraft/client/Minecraft.class") != null;
   }

   // ==================== agent packaging ====================

   /**
    * Resolves what to pass as {@code -javaagent}. Running from a jar returns that jar; running from
    * compiled classes (an IDE run configuration) packs those classes into a jar next to the other runtime
    * files, because the agent mechanism only accepts a jar with a {@code Premain-Class} manifest entry.
    * It is written inside the game directory rather than the system temp directory: the bot's files stay in
    * one place, and a temp path is not guaranteed to be writable on every host.
    */
   private static File agentJar(final File gameDirectory) {
      Path source;

      try {
         source = Path.of(Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      } catch (Exception e) {
         return null;
      }

      if (source.toString().endsWith(".jar") && Files.isRegularFile(source)) {
         return source.toFile();
      }

      File out = new File(gameDirectory, "versions" + File.separator + "eml-agent-" + Integer.toHexString(source.toString().hashCode()) + ".jar");

      try {
         if (!Files.isDirectory(source)) {
            return null;
         }

         out.getParentFile().mkdirs();
         buildAgentJar(source, out);
         LaunchLog.info("[bootstrap] IDE run: packaged agent -> " + out.getAbsolutePath());
         return out;
      } catch (IOException e) {
         LaunchLog.error("[bootstrap] could not package the agent jar", e);
         return out.isFile() ? out : null;
      }
   }

   private static void buildAgentJar(final Path classes, final File out) throws IOException {
      Manifest manifest = new Manifest();
      manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
      manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), "eml.agent.EmlAgent");
      manifest.getMainAttributes().put(new Attributes.Name("Agent-Class"), "eml.agent.EmlAgent");
      manifest.getMainAttributes().put(new Attributes.Name("Can-Redefine-Classes"), "false");

      Map<Path, Path> relativized = new HashMap<>();

      try (var stream = Files.walk(classes)) {
         for (Path path : stream.filter(Files::isRegularFile).toList()) {
            relativized.put(path, classes.relativize(path));
         }
      }

      try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out.toPath()), manifest)) {
         for (Map.Entry<Path, Path> entry : relativized.entrySet()) {
            String name = entry.getValue().toString().replace(File.separatorChar, '/');
            jos.putNextEntry(new JarEntry(name));

            try (InputStream in = Files.newInputStream(entry.getKey())) {
               in.transferTo(jos);
            }

            jos.closeEntry();
         }
      }
   }

   /** Exposed for diagnostics: the client jar used by this run, or null when it still has to be fetched. */
   public static File locateClientJar(final File gameDirectory, final String version) {
      return new MojangRuntime(gameDirectory, version).findClientJar();
   }
}
