package net.minecraft.client.headless;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.slf4j.Logger;

/**
 * JLine-backed console terminal (the xinbot approach): one system terminal plus a
 * {@link LineReader} with tab completion, live typo highlighting and persistent history.
 * JLine handles raw mode on both Windows (JNA/JANSI) and Unix (termios), so no
 * platform-specific code lives here.
 *
 * <ul>
 *   <li><b>Tab</b> completes console command names (first word only)</li>
 *   <li>Buffer is tinted bright blue while it can still become a valid command,
 *       bright red as soon as nothing matches</li>
 *   <li>Log lines print above the input line via {@link JLineLogAppender}</li>
 * </ul>
 *
 * <p>When no real terminal is available (pipes, containers, IDE runs) {@link #init(Path)} falls back to a
 * {@link DumbTerminal} — plain line reading, no colours, no completion redraw — so every feature stays
 * functional on any host. The command history is pinned to {@code <gameDir>/.eml_history}: JLine's
 * default would file it under {@code ~/.jline/} in the user's home, outside the bot directory.
 */
public final class BotTerminal {
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
   private static final int BRIGHT_BLUE = 12;
   private static final int BRIGHT_RED = 9;

   private static Terminal terminal;
   private static LineReader lineReader;

   private BotTerminal() {
   }

   /** Builds the terminal and wires the log appender to it. Safe to call once at startup. */
   public static void init(final Path gameDirectory) {
      try {
         terminal = TerminalBuilder.builder()
            .system(true)
            .encoding(consoleCharset())
            .build();
         // Persistent history INSIDE the game directory: JLine's default history file lives at
         // ~/.jline/history_<app>.bak, which would read and write outside the bot's own folder.
         // HISTORY_FILE is the builder-documented hook - the DefaultHistory the builder installs
         // loads/saves exactly this path (atomic temp-and-swap writes), so no widget rewiring is needed.
         Path history = gameDirectory.toAbsolutePath().normalize().resolve(".eml_history");
         Files.createDirectories(history.getParent());
         lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .appName("eml")
            .variable(LineReader.HISTORY_FILE, history)
            .option(LineReader.Option.CASE_INSENSITIVE, true)
            .option(LineReader.Option.COMPLETE_IN_WORD, true)
            .completer(new BotCompleter())
            .highlighter(new BotHighlighter())
            .build();
         JLineLogAppender.setLineReader(lineReader);
      } catch (Exception e) {
         if (terminal == null) {
            LOGGER.warn("JLine terminal unavailable, falling back to plain console input: {}", e.toString());
         } else {
            // The system terminal exists but the LineReader could not be built: keep a DumbTerminal
            // (plain lines, no ANSI) so the console still gets a functional reader and colour-safe
            // output instead of degrading to raw escape sequences on unknown consoles.
            LOGGER.warn("JLine line editor unavailable, using a dumb terminal: {}", e.toString());
            lineReader = LineReaderBuilder.builder().terminal(terminal).build();
            JLineLogAppender.setLineReader(lineReader);
         }
      }
   }

   /** True when colour escape sequences can be handed to the output (JLine will downgrade them). */
   public static boolean colorsSupported() {
      return terminal != null && !(terminal instanceof DumbTerminal);
   }

   public static LineReader lineReader() {
      return lineReader;
   }

   /** Detaches the appender and restores the terminal state. Idempotent. */
   public static void close() {
      JLineLogAppender.setLineReader(null);
      if (terminal != null) {
         try {
            terminal.close();
         } catch (IOException ignored) {
         }

         terminal = null;
         lineReader = null;
      }
   }

   /** Charset of the real console (GBK on Chinese Windows, UTF-8 elsewhere). */
   private static Charset consoleCharset() {
      String encoding = System.getProperty("stdout.encoding");
      return encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
   }

   /** Completes the first word against the console command registry (built-ins + plugins). */
   private static final class BotCompleter implements Completer {
      @Override
      public void complete(final LineReader reader, final ParsedLine line, final List<Candidate> candidates) {
         String buffer = line.line();
         int space = buffer.indexOf(' ');
         if (space >= 0 && line.cursor() > space) {
            return;
         }

         String prefix = (space < 0 ? buffer : buffer.substring(0, space)).toLowerCase(Locale.ROOT);
         for (String match : Commands.complete(prefix)) {
            candidates.add(new Candidate(match));
         }
      }
   }

   /** Bright blue while the buffer can still become a command, bright red once it cannot. */
   private static final class BotHighlighter implements Highlighter {
      @Override
      public AttributedString highlight(final LineReader reader, final String buffer) {
         String trimmed = buffer.stripLeading();
         boolean valid = trimmed.isEmpty() || trimmed.startsWith("/");
         if (!valid) {
            String firstWord = trimmed.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            valid = Commands.hasCommandStartingWith(firstWord);
         }

         return new AttributedString(buffer, AttributedStyle.DEFAULT.foreground(valid ? BRIGHT_BLUE : BRIGHT_RED));
      }
   }
}
