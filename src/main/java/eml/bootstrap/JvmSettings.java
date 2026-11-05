package eml.bootstrap;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import eml.util.LaunchLog;
import org.yaml.snakeyaml.Yaml;

/**
 * The {@code jvm:} block of {@code bot.yml}: the JVM flags for the relaunched bot process.
 *
 * <pre>
 * jvm:
 *   - "-Xmx384m"
 *   - "-XX:+UseCompactObjectHeaders"
 * </pre>
 *
 * <p>It lives in {@code bot.yml} rather than in a file of its own because that is the one file a user
 * already edits: one place to look, one place to change, and no "which config wins" question. The catch is
 * that the block has to be read by two processes with different classpaths - the bootstrap JVM (which
 * launches the child, so it needs the flags before any Mojang class exists) and the bot itself (whose
 * {@code BotConfig} rewrites the whole file on connect, so it must be able to write the block back). Both
 * go through this parser, which is why it depends on nothing but the JDK and SnakeYAML - both are in the
 * fat jar, so {@code java -jar} can use it before the runtime is resolved.
 *
 * <p>Shape rules, chosen to keep the format boring:
 * <ul>
 *   <li>a YAML list of strings - one flag per item;</li>
 *   <li>a single string is accepted and split on whitespace (the old {@code jvm.args} habit);</li>
 *   <li>a missing block is not an error: {@link #effective()} then supplies {@link #defaults()}, the
 *       footprint-tuned set.</li>
 * </ul>
 *
 * <p>Only the launching side validates ({@link #validate()}). The bot itself just round-trips the list so
 * that a {@code bot.yml} rewrite does not drop what the user wrote.
 */
public final class JvmSettings {
   /** Key under which the block lives in {@code bot.yml}. */
   public static final String KEY = "jvm";

   /**
    * Maximum heap used when the block does not bound it: a session holds about 86 MB of live heap before a
    * world is loaded and ~170 MB with one (vanilla classes, the block state graph, chunks), so this keeps
    * room for the server's own view distance without letting the committed heap inflate the way the JVM's
    * own default (a quarter of physical RAM) does. A capped {@code viewDistance} makes 256m enough; that is
    * the number to lower for density and to raise for a large view distance or heavy plugins.
    */
   public static final String DEFAULT_MAX_HEAP = "384m";

   /**
    * The flags a bot with no {@code jvm:} block runs with, and therefore the block the generated
    * {@code bot.yml} documents.
    *
    * <p>No collector is named on purpose. Measured on this bot at the same 384m ceiling, G1 - the JVM
    * default - sat at 340 MB RSS while {@code -XX:+UseSerialGC} reached 382 MB: a single-threaded
    * collector sizes its young generation at a third of the heap up front, so it commits far more than an
    * idle bot touches, and it also has the longer pauses. SerialGC only wins when the heap itself is
    * smaller (256m + SerialGC measured 336 MB), which is a choice for a capped view distance rather than a
    * default. Naming a collector here would also collide with a user's own choice - one JVM, one GC.
    *
    * <ul>
    *   <li>{@code -Xmx384m} - see {@link #DEFAULT_MAX_HEAP}. Lowering it is what shrinks G1's committed
    *       young generation, so this is the line that moves RSS most;</li>
    *   <li>{@code -XX:+UseCompactObjectHeaders} - 8-byte object headers (product in JDK 25, which this
    *       build targets). Measured: ~2.14M live objects, so ~8.5 MB less live heap.</li>
    * </ul>
    */
   private static final List<String> DEFAULTS = List.of(
      "-Xmx" + DEFAULT_MAX_HEAP,
      "-XX:+UseCompactObjectHeaders"
   );

   /**
    * Flags that would replace what the bootstrap assembles. Refused rather than trusted: this process
    * exists to put the Mojang runtime on the classpath, so one stray {@code -cp} would produce a launch
    * that dies on {@code NoClassDefFoundError} far from the line that caused it.
    */
   private static final List<String> RESERVED = List.of(
      "-cp", "-classpath", "--class-path", "-p", "--module-path", "-m", "--module", "-jar"
   );

   private final List<String> arguments;

   private JvmSettings(final List<String> arguments) {
      this.arguments = List.copyOf(arguments);
   }

   /** The built-in set, for the process that decides which flags a run gets to log. */
   public static List<String> defaults() {
      return DEFAULTS;
   }

   /** No configured flags; {@link #effective()} answers with {@link #defaults()}. */
   public static JvmSettings empty() {
      return new JvmSettings(List.of());
   }

   /** Reads the block out of {@code botYml}; a missing or unreadable file yields the empty set. */
   public static JvmSettings load(final File botYml) {
      if (!botYml.isFile()) {
         return empty();
      }

      try (InputStream stream = Files.newInputStream(botYml.toPath())) {
         return extract(new Yaml().load(stream));
      } catch (Exception e) {
         LaunchLog.warn("[bootstrap] could not read the " + KEY + " block from " + botYml.getAbsolutePath()
            + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
         return empty();
      }
   }

   /** Reads the block out of an already parsed {@code bot.yml} root map. */
   public static JvmSettings extract(final Object root) {
      if (!(root instanceof Map<?, ?> map)) {
         return empty();
      }

      Object node = map.get(KEY);
      List<String> arguments = new ArrayList<>();

      if (node instanceof List<?> list) {
         for (Object entry : list) {
            add(arguments, entry);
         }
      } else if (node instanceof String text) {
         // The "one line, several flags" form: same as the file this block replaced.
         for (String part : text.trim().split("\\s+")) {
            if (!part.isBlank()) {
               arguments.add(part);
            }
         }
      }

      return new JvmSettings(arguments);
   }

   private static void add(final List<String> arguments, final Object entry) {
      if (entry == null) {
         return;
      }

      String value = String.valueOf(entry).trim();
      if (!value.isEmpty()) {
         arguments.add(value);
      }
   }

   /** The flags as written, without the implied default. */
   public List<String> arguments() {
      return this.arguments;
   }

   /**
    * The flags actually handed to the JVM. An empty block yields {@link #defaults()} as a whole - the
    * object-header flag belongs to that set rather than being injected behind the back of a block somebody
    * did write. A block of one's own is used in order, plus {@link #DEFAULT_MAX_HEAP} when nothing in it
    * bounds the heap.
    */
   public List<String> effective() {
      if (this.arguments.isEmpty()) {
         return DEFAULTS;
      }

      List<String> arguments = new ArrayList<>(this.arguments);

      if (!boundsHeap(arguments)) {
         arguments.add("-Xmx" + DEFAULT_MAX_HEAP);
      }

      return arguments;
   }

   /**
    * Rejects a block that cannot be handed to the JVM as written, naming the item index so the fix is
    * obvious. Called by the bootstrap only: the launching process is where a broken flag would surface as
    * an unrelated failure, so it fails here, with the block's own name in the message.
    */
   public void validate() {
      for (int index = 0; index < this.arguments.size(); index++) {
         String argument = this.arguments.get(index);

         if (!argument.startsWith("-")) {
            throw new IllegalArgumentException(KEY + "[" + index + "]: '" + argument + "' is not a -flag");
         }

         for (String flag : RESERVED) {
            if (argument.equals(flag) || argument.startsWith(flag + ":") || argument.startsWith(flag + "=")) {
               throw new IllegalArgumentException(KEY + "[" + index + "]: '" + argument
                  + "' would replace the bot's own classpath or entry point");
            }
         }
      }
   }

   /**
    * The block as YAML list items (key and indentation included, no trailing newline), always showing what
    * will actually run - so a file that never mentioned a heap still documents the flags it gets. Values are
    * double-quoted with {@code \"} / {@code \\} escapes, which round-trips through {@link #extract(Object)}
    * even for a property whose value contains spaces or quotes.
    */
   public List<String> renderYaml() {
      List<String> lines = new ArrayList<>();

      for (String argument : effective()) {
         lines.add("  - \"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
      }

      return lines;
   }

   private static boolean boundsHeap(final List<String> arguments) {
      for (String argument : arguments) {
         if (argument.startsWith("-Xmx")
            || argument.startsWith("-XX:MaxHeapSize")
            || argument.startsWith("-XX:MaxRAMPercentage")
            || argument.startsWith("-XX:MaxRAMFraction")) {
            return true;
         }
      }

      return false;
   }
}
