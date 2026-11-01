package net.minecraft.client.headless;

import java.util.List;
import org.slf4j.Logger;

/**
 * Serialised console output. Message lines go through log4j so they share the standard
 * "[time] [thread/level]:" prefix and severity colours with engine diagnostics; the
 * {@link JLineLogAppender} renders them above the JLine input line, so prompt handling
 * and input redraw are entirely JLine's job now (no manual prompt logic lives here).
 */
public final class ConsoleIO {
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
   private static final String ERROR_COLOR = "\u001B[91m";
   private static final String RESET = "\u001B[0m";

   public synchronized void print(final String message) {
      LOGGER.info(message);
   }

   /** Prints an error line in bright red (usage mistakes, failed commands, connection errors). */
   public synchronized void printError(final String message) {
      LOGGER.info(ERROR_COLOR + message + RESET);
   }

   public synchronized void printBlock(final List<String> lines) {
      for (String line : lines) {
         LOGGER.info(line);
      }
   }

   public synchronized void printf(final String format, final Object... args) {
      this.print(String.format(format, args));
   }

   public void printlnRaw(final String message) {
      LOGGER.info(message);
   }
}
