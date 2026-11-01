package eml.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;

/**
 * Logging facade for the code that runs before - or entirely without - the Mojang runtime.
 *
 * <p>The launcher shim and the self-check execute in a JVM whose classpath can contain nothing but our
 * own jar: no {@code org.slf4j}, no {@code com.mojang.logging}. A static logger field there would fail
 * exactly like the {@code NoClassDefFoundError: com/mojang/logging/LogUtils} we removed from the entry
 * point, so the SLF4J binding is looked up reflectively and the console is the fallback.
 *
 * <p>Once the full runtime is on the classpath (the relaunched, {@code -javaagent} process) the very
 * same calls flow into the standard log4j2 pipeline: launcher and agent lines get normal
 * {@code [HH:mm:ss] [thread/LEVEL]} treatment instead of arriving as captured {@code [STDERR]:} noise.
 *
 * <p>Messages are expected to be pre-formatted: only the single-String logger overloads are used, so a
 * literal {@code {}} inside a path or classpath is never interpreted.
 */
public final class LaunchLog {
   private static final Object LOGGER = initLogger();
   private static final Method INFO = method("info", String.class);
   private static final Method WARN = method("warn", String.class);
   private static final Method ERROR = method("error", String.class);
   private static final Method ERROR_THROWABLE = method("error", String.class, Throwable.class);

   private LaunchLog() {
   }

   public static void info(final String message) {
      dispatch(INFO, message, null);
   }

   public static void warn(final String message) {
      dispatch(WARN, message, null);
   }

   public static void error(final String message) {
      dispatch(ERROR, message, null);
   }

   public static void error(final String message, final Throwable cause) {
      dispatch(ERROR_THROWABLE, message, cause);
   }

   /** True when the standard logging pipeline took over (i.e. we are no longer in the bootstrap JVM). */
   public static boolean isStandardLogging() {
      return LOGGER != null && INFO != null;
   }

   // ==================== internals ====================

   private static Object initLogger() {
      try {
         Class<?> factory = Class.forName("org.slf4j.LoggerFactory");
         return factory.getMethod("getLogger", Class.class).invoke(null, LaunchLog.class);
      } catch (Throwable expectedInBootstrapJvm) {
         return null;
      }
   }

   private static Method method(final String name, final Class<?>... parameters) {
      if (LOGGER == null) {
         return null;
      }

      try {
         return LOGGER.getClass().getMethod(name, parameters);
      } catch (NoSuchMethodException e) {
         return null;
      }
   }

   private static void dispatch(final Method route, final String message, final Throwable cause) {
      if (route != null) {
         try {
            // Two separate calls on purpose: a ternary between a String and an Object[] would be typed as
            // Object and arrive as ONE vararg, i.e. "wrong number of arguments: 1 expected: 2".
            if (cause == null) {
               route.invoke(LOGGER, message);
            } else {
               route.invoke(LOGGER, message, cause);
            }

            return;
         } catch (Throwable loggingFailed) {
            // Never lose a diagnostic because of the logging backend itself.
            System.err.println("[logging] " + route.getName() + " failed: " + loggingFailed);
         }
      }

      console(message, cause);
   }

   private static void console(final String message, final Throwable cause) {
      if (cause == null) {
         System.out.println(message);
         return;
      }

      StringWriter trace = new StringWriter();
      cause.printStackTrace(new PrintWriter(trace));
      System.err.println(message + System.lineSeparator() + trace);
   }
}
