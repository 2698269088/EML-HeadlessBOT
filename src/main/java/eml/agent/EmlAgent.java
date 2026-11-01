package eml.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import eml.util.LaunchLog;

/**
 * Java agent that turns the untouched launcher jar into a headless client at class-load time.
 *
 * <p>Launch form (classpath order matters: our jar first, vanilla jar after):
 * <pre>
 *   java -javaagent:EML-HeadlessBOT.jar -cp EML-HeadlessBOT.jar;26.1.2.jar;&lt;launcher libs&gt; \
 *        top.mcocet.hlbot.EML
 * </pre>
 *
 * <p>Switches:
 * <ul>
 *   <li>{@code -Deml.patches.disabled=net/minecraft/client/Foo,...} skip a whole class's patches</li>
 *   <li>{@code -Deml.patches.verbose=true} log every applied rule (default: on, one line per class)</li>
 * </ul>
 * A failing rule aborts startup instead of silently running half-patched code.
 */
public final class EmlAgent {
   private static final Map<String, List<RuleEntry>> RULES = new HashMap<>();
   private static java.util.Set<String> disabled = java.util.Set.of();
   /**
    * Kept so the running bot can ask which target classes were loaded BEFORE this agent was installed.
    *
    * <p>Those classes carry no patches and nothing in the transform callback ever sees them, so before this
    * field existed the only symptom was a bot that behaved vanilla at one call site for no visible reason.
    * The manifest keeps {@code Can-Retransform-Classes} false on purpose: {@code Wrap} renames the original
    * body and {@code BodyReplace} reshapes methods, neither of which is expressible as a retransformation
    * (the JVM forbids adding or removing members). Auditing and reporting is therefore the honest fix - a
    * loud, named list instead of a silent half-patched process.
    */
   private static volatile Instrumentation instrumentation;
   private static volatile boolean auditReported;

   private EmlAgent() {
   }

   /** Entry point for {@code -javaagent}. */
   public static void premain(final String argument, final Instrumentation instrumentation) {
      install(instrumentation);
   }

   /** Entry point for dynamic attach (useful when iterating on patches from a debugger). */
   public static void agentmain(final String argument, final Instrumentation instrumentation) {
      install(instrumentation);
   }

   private static void install(final Instrumentation instrumentation) {
      EmlAgent.instrumentation = instrumentation;
      // The debug switches live in <gameDir>/bot.yml, which BotConfig reads much later, so the agent parses
      // the same block itself: hooks installed by these rules consult eml.util.Debug at runtime. The game
      // directory is handed over as a property by the bootstrap, because premain runs before any argument
      // parsing and must still read the very same file as the bot.
      eml.util.Debug.load(new File(System.getProperty("eml.gameDir", ".")));

      String off = System.getProperty("eml.patches.disabled", "");
      List<String> names = new ArrayList<>();
      for (String part : off.split(",")) {
         if (!part.isBlank()) {
            names.add(part.trim());
         }
      }

      disabled = names.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(names);

      RULES.clear();
      for (eml.engine.Rules.Patch patch : EmlPatches.register()) {
         RULES.computeIfAbsent(patch.owner, key -> new ArrayList<>()).add(new RuleEntry(patch));
      }

      int classes = RULES.size();
      int rules = RULES.values().stream().mapToInt(List::size).sum();
      log("headless patches: " + rules + " rule(s) for " + classes + " class(es)" + (disabled.isEmpty() ? "" : ", disabled: " + disabled));
      log("debug switches: " + eml.util.Debug.describe());

      if ("trace".equalsIgnoreCase(System.getProperty("eml.patches.verbose", ""))) {
         RULES.values().stream().flatMap(List::stream).forEach(entry -> LaunchLog.info("[eml-agent]   " + entry.patch.describe()));
      }

      instrumentation.addTransformer(new HeadlessTransformer());
   }

   /** One rule wrapper that keeps the applied state for logging. */
   private static final class RuleEntry {
      final eml.engine.Rules.Patch patch;
      boolean applied;

      RuleEntry(final eml.engine.Rules.Patch patch) {
         this.patch = patch;
      }
   }

   // ==================== patch audit ====================

   /**
    * Target classes that are already loaded but were never run through {@link HeadlessTransformer}. Any
    * entry here is a patch site that is silently inactive; the list is empty in a normal launch, because
    * {@code Bootstrap} relaunches the JVM with {@code -javaagent} before the bot touches a vanilla class.
    */
   public static List<String> unpatchedLoadedClasses() {
      Instrumentation probe = instrumentation;

      if (probe == null || RULES.isEmpty()) {
         return List.of();
      }

      List<String> unpatched = new ArrayList<>();

      for (Class<?> loaded : probe.getAllLoadedClasses()) {
         String name = loaded.getName().replace('.', '/');
         List<RuleEntry> entries = RULES.get(name);

         if (entries != null && entries.stream().noneMatch(entry -> entry.applied)) {
            unpatched.add(name);
         }
      }

      return unpatched;
   }

   /** Rules whose target method never existed in the loaded class - a mapping change that went unnoticed. */
   public static List<String> neverAppliedRules() {
      List<String> missing = new ArrayList<>();

      for (List<RuleEntry> entries : RULES.values()) {
         for (RuleEntry entry : entries) {
            if (!entry.applied) {
               missing.add(entry.patch.describe());
            }
         }
      }

      return missing;
   }

   /**
    * Reports the audit once per process, at the first moment the interesting classes are certainly loaded
    * (the bot calls this when it enters play). Repeating it would just print the same answer every tick.
    *
    * @return the warning lines that were emitted, empty when everything is patched
    */
   public static List<String> auditOnce() {
      if (auditReported) {
         return List.of();
      }

      auditReported = true;
      List<String> lines = new ArrayList<>();

      for (String owner : unpatchedLoadedClasses()) {
         lines.add("loaded before the agent, running UNPATCHED: " + owner);
      }

      for (String rule : neverAppliedRules()) {
         lines.add("rule never applied: " + rule);
      }

      for (String line : lines) {
         LaunchLog.info("[eml-agent] " + line);
      }

      return lines;
   }

   private static final class HeadlessTransformer implements java.lang.instrument.ClassFileTransformer {
      @Override
      public byte[] transform(
         final ClassLoader loader,
         final String className,
         final Class<?> classBeingRedefined,
         final java.security.ProtectionDomain protectionDomain,
         final byte[] classfileBuffer
      ) {
         if (className == null) {
            return null;
         }

         List<RuleEntry> entries = RULES.get(className);
         if (entries == null) {
            return null;
         }

         if (disabled.contains(className)) {
            log("skipping all patches for " + className + " (eml.patches.disabled)");
            return null;
         }

         try {
            ClassNode node = new ClassNode();
            new ClassReader(classfileBuffer).accept(node, 0);

            boolean needsFrames = false;

            for (RuleEntry entry : entries) {
               entry.patch.apply(node);
               entry.applied = true;
               needsFrames |= entry.patch.needsFrameRecomputation();

               // A StatementDrop that could not bound its statement (value stored/returned, try/catch range,
               // arguments escaping the receiver chain) leaves the vanilla call in place. Such a site can
               // dereference a null render/audio object at runtime, so it is reported here - at class load -
               // instead of surfacing later as a disconnect.
               if (entry.patch instanceof eml.engine.Rules.StatementDrop drop && drop.lastSkip != null) {
                  log("not dropped: " + drop.describe() + " - " + drop.lastSkip);
               }
            }

            // Statement-removing rules change the operand stack at branch targets, so their frames must be
            // rebuilt; everything else is written verbatim to keep the transformer free of class loading.
            ClassWriter writer = needsFrames
               ? new eml.engine.EmlClassWriter(ClassWriter.COMPUTE_FRAMES, loader != null ? loader : ClassLoader.getSystemClassLoader())
               : new ClassWriter(0);
            node.accept(writer);
            log("patched " + className + " (" + entries.size() + " rule(s)" + (needsFrames ? ", frames recomputed" : "") + ")");
            return writer.toByteArray();
         } catch (Throwable t) {
            // Never hand back half-applied bytes and never swallow: a missing method means the
            // mappings moved, and a silent vanilla class would fail much later in confusing ways.
            throw new IllegalStateException("headless patch failed for " + className + ": " + t.getMessage(), t);
         }
      }
   }

   private static void log(final String message) {
      if (!"false".equals(System.getProperty("eml.patches.verbose", "true"))) {
         LaunchLog.info("[eml-agent] " + message);
      }
   }
}
