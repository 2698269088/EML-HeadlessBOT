package eml.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Last-resort crash diagnostics with no Minecraft or logging-framework dependency. */
public final class CrashReporter {
   private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
   private static volatile Consumer<PrintStream> stateWriter;

   private CrashReporter() {
   }

   public static void install() {
      Thread.setDefaultUncaughtExceptionHandler(CrashReporter::report);
   }

   /** Installs the bot-specific part; the base reporter remains usable before the client exists. */
   public static void installStateWriter(final Consumer<PrintStream> writer) {
      stateWriter = writer;
   }

   /** Writes a standalone report first, then makes a best-effort copy through the normal log pipeline. */
   public static synchronized File report(final Thread thread, final Throwable error) {
      File file = null;

      try {
         File gameDirectory = new File(System.getProperty("eml.gameDir", "."));
         File logs = new File(gameDirectory, "logs");
         Files.createDirectories(logs.toPath());
         String threadName = thread == null ? "unknown" : thread.getName();
         file = new File(logs, "crash-" + FILE_TIME.format(LocalDateTime.now()) + "-" + safeFileName(threadName) + ".log");

         try (PrintStream out = new PrintStream(new FileOutputStream(file, true), true, StandardCharsets.UTF_8)) {
            out.println("EML-HeadlessBOT crash report");
            line(out, "Time", Instant::now);
            line(out, "Thread", () -> threadName + " (id=" + (thread == null ? "?" : thread.threadId())
               + ", state=" + (thread == null ? "?" : thread.getState()) + ")");
            out.println();
            out.println("=== Exception ===");
            error.printStackTrace(out);
            out.println();
            out.println("=== System ===");
            writeSystemState(out);
            out.println();
            out.println("=== Bot ===");
            Consumer<PrintStream> writer = stateWriter;
            if (writer == null) {
               out.println("not available before client startup");
            } else {
               try {
                  writer.accept(out);
               } catch (Throwable unavailable) {
                  out.println("state capture failed: " + unavailable);
               }
            }
            out.flush();
         }
      } catch (Throwable recordingFailed) {
         System.err.println("Could not write the crash report: " + recordingFailed);
         error.printStackTrace(System.err);
      }

      try {
         LaunchLog.error("Crash on " + (thread == null ? "unknown thread" : thread.getName())
            + "; full report: " + (file == null ? "unavailable" : file.getAbsolutePath()), error);
      } catch (Throwable loggingFailed) {
         System.err.println("Crash logging failed: " + loggingFailed);
         error.printStackTrace(System.err);
      }

      return file;
   }

   private static void writeSystemState(final PrintStream out) {
      Runtime runtime = Runtime.getRuntime();
      line(out, "Java", () -> System.getProperty("java.runtime.version") + " (" + System.getProperty("java.vendor") + ")");
      line(out, "JVM", () -> System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
      line(out, "OS", () -> System.getProperty("os.name") + " " + System.getProperty("os.version")
         + " " + System.getProperty("os.arch"));
      line(out, "CPU model", () -> {
         String model = System.getenv("PROCESSOR_IDENTIFIER");
         return model == null || model.isBlank() ? "unavailable" : model;
      });
      line(out, "CPU cores", runtime::availableProcessors);

      line(out, "Heap used", () -> bytes(runtime.totalMemory() - runtime.freeMemory()));
      line(out, "Heap committed", () -> bytes(runtime.totalMemory()));
      line(out, "Heap maximum", () -> bytes(runtime.maxMemory()));
      line(out, "Non-heap used", () -> bytes(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed()));
      line(out, "Loaded classes", () -> ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
      line(out, "Live threads", Thread::activeCount);

      try {
         java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
         if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
            line(out, "Process CPU", () -> percent(sun.getProcessCpuLoad()));
            line(out, "System CPU", () -> percent(sun.getCpuLoad()));
            line(out, "Process committed virtual memory", () -> bytes(sun.getCommittedVirtualMemorySize()));
            line(out, "Physical memory", () -> bytes(sun.getTotalMemorySize() - sun.getFreeMemorySize())
               + " used / " + bytes(sun.getTotalMemorySize()) + " total");
         } else {
            out.println("CPU load and physical memory: unavailable on this JVM");
         }
      } catch (Throwable unavailable) {
         out.println("Operating-system metrics unavailable: " + unavailable);
      }
   }

   private static void line(final PrintStream out, final String name, final Supplier<?> value) {
      try {
         out.println(name + ": " + value.get());
      } catch (Throwable unavailable) {
         out.println(name + ": unavailable (" + unavailable.getClass().getName() + ")");
      }
   }

   private static String percent(final double value) {
      return !Double.isFinite(value) || value < 0.0 ? "unavailable" : String.format(Locale.ROOT, "%.1f%%", value * 100.0);
   }

   private static String bytes(final long value) {
      if (value < 0L) {
         return "unavailable";
      }

      if (value < 1024L) {
         return value + " B";
      }

      String[] units = {"KiB", "MiB", "GiB", "TiB"};
      double scaled = value;
      int unit = -1;
      do {
         scaled /= 1024.0;
         unit++;
      } while (scaled >= 1024.0 && unit < units.length - 1);
      return String.format(Locale.ROOT, "%.1f %s (%d bytes)", scaled, units[unit], value);
   }

   private static String safeFileName(final String value) {
      return value.replaceAll("[^A-Za-z0-9._-]", "_");
   }
}
