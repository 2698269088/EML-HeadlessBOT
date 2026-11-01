package net.minecraft.client.headless;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

/**
 * Reads console input on a daemon thread so that a long-lived tick loop never blocks the
 * prompt. Uses the JLine line editor (tab completion, highlighting, history) when a real
 * terminal is available, and falls back to a plain buffered reader otherwise. EOF or an
 * exception simply stops the session.
 */
public final class Console {
   private static final String PROMPT = "> ";
   private final ConsoleIO io;
   private final HeadlessSession session;
   private final BufferedReader reader;

   public Console(final ConsoleIO io, final HeadlessSession session, final BufferedReader reader) {
      this.io = io;
      this.session = session;
      this.reader = reader;
   }

   public void startAsync() {
      Thread thread = new Thread(this::loop, "Console input");
      thread.setDaemon(true);
      thread.start();
   }

   private void loop() {
      this.io.print(BotLang.get("bot.ready"));
      LineReader jline = BotTerminal.lineReader();

      try {
         if (jline != null) {
            String line;
            while ((line = jline.readLine(PROMPT)) != null) {
               this.handle(line);
            }
         } else {
            String line;
            while ((line = this.reader.readLine()) != null) {
               this.handle(line);
            }
         }
      } catch (EndOfFileException | UserInterruptException e) {
         // Ctrl+D / Ctrl+C on the JLine reader: fall through to shutdown.
      } catch (IllegalStateException e) {
         // The terminal was already closed (shutdown hook, or the client thread dying): the input thread
         // loses its reader underneath readLine. Exiting quietly is the normal outcome, not a failure.
      } catch (IOException e) {
         this.io.print(BotLang.get("bot.stdin_closed", e));
      }

      this.session.shutdown();
   }

   private void handle(final String line) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
         return;
      }

      if (Commands.dispatch(this.io, this.session, trimmed)) {
         return;
      }

      if (trimmed.startsWith("/")) {
         this.session.sendCommand(trimmed.substring(1));
         return;
      }

      if (this.session.isConnected()) {
         this.session.sendChat(trimmed);
      } else {
         this.io.printError(BotLang.get("bot.unknown_command", trimmed.split("\\s+")[0]));
      }
   }

   public static BufferedReader standardIn() {
      String encoding = System.getProperty("stdin.encoding");
      Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
      return new BufferedReader(new java.io.InputStreamReader(System.in, charset));
   }
}
