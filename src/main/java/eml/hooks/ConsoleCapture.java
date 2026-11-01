package eml.hooks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import top.mcocet.hlbot.plugins.ConsoleLine;

/**
 * Bounded in-memory capture of everything the bot writes to its console, so a plugin can show it without
 * owning the log configuration.
 *
 * <p>The console already flows through log4j2 ({@code JLineLogAppender} renders it above the input line), so
 * the honest way to observe it is a second appender on the root logger rather than a parallel print path
 * that would drift out of sync. Capture is <b>installed lazily</b>: a bot with no interested plugin pays
 * nothing, and the install is idempotent so several plugins can ask for it.
 *
 * <p>The buffer is a fixed-size ring. Console output is unbounded and a monitoring page only ever shows the
 * tail, so the oldest line is evicted rather than growing the heap - the same reasoning as
 * {@code BotRuntime}'s chat history, which this class complements: that one holds parsed chat, this one
 * holds the raw log stream.
 *
 * <p>Sequence numbers are monotonic and never reused, which is what makes polling safe: a caller passes the
 * last sequence it rendered and receives only what was appended since, even across evictions.
 */
public final class ConsoleCapture {
   /** Ring capacity in lines; roughly a couple of minutes of busy output, and bounded memory. */
   public static final int CAPACITY = 2000;

   private static final Deque<ConsoleLine> LINES = new ArrayDeque<>(CAPACITY);
   private static int sequence;
   private static WebAppender appender;

   private ConsoleCapture() {
   }

   /**
    * Starts capturing console lines. Safe to call repeatedly and from any thread.
    *
    * <p>Attaching to {@code Logger.addAppender} alone is not enough at runtime: the logger's appender view
    * is derived from its {@code LoggerConfig}, which is built when the configuration is read. A live install
    * has to reach every {@code LoggerConfig} and then force loggers to be rebuilt, or the appender sits in
    * the configuration and never sees an event - which is exactly how this class first shipped.
    *
    * @return true when this call performed the install
    */
   public static synchronized boolean install() {
      if (appender != null) {
         return false;
      }

      try {
         WebAppender created = new WebAppender();
         created.start();
         LoggerContext context = (LoggerContext)LogManager.getContext(false);
         Configuration configuration = context.getConfiguration();
         configuration.addAppender(created);

         // Every configured logger config, not just root: a logger with additivity=false would otherwise
         // never propagate to root and its lines would be invisible on the dashboard.
         configuration.getRootLogger().addAppender(created, null, null);

         for (LoggerConfig loggerConfig : configuration.getLoggers().values()) {
            loggerConfig.addAppender(created, null, null);
         }

         context.updateLoggers();
         appender = created;
         BotRuntime.log().info("[console] capture installed ({} logger config(s))", configuration.getLoggers().size());
         return true;
      } catch (Throwable unavailable) {
         // A missing log4j-core (an embedded launch, a different logging backend) must not break the bot;
         // capture is diagnostics, so it degrades to "no lines" and says so once.
         BotRuntime.log().warn("[console] web console capture unavailable: {}", unavailable.toString());
         return false;
      }
   }

   /** Stops capturing and detaches the appender. Buffered lines stay readable until the next install. */
   public static synchronized void uninstall() {
      WebAppender current = appender;
      appender = null;

      if (current == null) {
         return;
      }

      try {
         LoggerContext context = (LoggerContext)LogManager.getContext(false);
         Configuration configuration = context.getConfiguration();
         configuration.getRootLogger().removeAppender(current.getName());

         for (LoggerConfig loggerConfig : configuration.getLoggers().values()) {
            loggerConfig.removeAppender(current.getName());
         }

         configuration.getAppenders().remove(current.getName());
         context.updateLoggers();
         current.stop();
      } catch (Throwable ignored) {
         // Detaching is best effort: the process is usually shutting down when this runs.
      }
   }

   public static synchronized boolean isInstalled() {
      return appender != null;
   }

   /**
    * Lines appended after {@code afterSeq}, oldest first.
    *
    * @param afterSeq the last sequence the caller has already rendered, or {@code 0} for everything buffered
    * @param limit    maximum lines to return; the newest are returned when more are pending, so a slow reader
    *                 skips ahead instead of falling permanently behind
    */
   public static List<ConsoleLine> since(final int afterSeq, final int limit) {
      List<ConsoleLine> selected = new ArrayList<>();

      synchronized (ConsoleCapture.class) {
         for (ConsoleLine line : LINES) {
            if (line.seq() > afterSeq) {
               selected.add(line);
            }
         }
      }

      if (limit > 0 && selected.size() > limit) {
         return new ArrayList<>(selected.subList(selected.size() - limit, selected.size()));
      }

      return selected;
   }

   /** Highest sequence number issued so far; safe to hand out before any line exists. */
   public static synchronized int sequence() {
      return sequence;
   }

   private static synchronized void record(final LogEvent event) {
      String message;

      try {
         message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
      } catch (Throwable broken) {
         message = "<unformattable message>";
      }

      Throwable thrown = event.getThrown();
      ConsoleLine line = new ConsoleLine(
         ++sequence,
         event.getTimeMillis(),
         event.getLevel() == null ? "INFO" : event.getLevel().name(),
         event.getLoggerName(),
         message,
         thrown == null ? null : thrown.getClass().getName()
      );

      if (LINES.size() >= CAPACITY) {
         LINES.removeFirst();
      }

      LINES.addLast(line);
   }

   /** The appender itself; private because only {@link #install()} should ever create one. */
   private static final class WebAppender extends AbstractAppender {
      WebAppender() {
         super("EmlConsoleCapture", null, null, true, null);
      }

      @Override
      public void append(final LogEvent event) {
         try {
            record(event);
         } catch (Throwable ignored) {
            // Observing the log must never be able to break the code being logged.
         }
      }
   }
}
