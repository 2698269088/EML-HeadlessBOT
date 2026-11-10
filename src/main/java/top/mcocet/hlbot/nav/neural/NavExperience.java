package top.mcocet.hlbot.nav.neural;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Experience log for offline training, one line per event in plain TSV:
 *
 * <ul>
 *   <li>{@code D <trip> <stage> <label> <f1..f15>} - a neural decision, with the exact normalised feature
 *       vector the network saw, so retraining never has to reconstruct it;</li>
 *   <li>{@code E <trip> <outcome> <seconds>} - how that trip ended.</li>
 * </ul>
 *
 * <p>The trainer groups by trip and keeps only decisions that led to an arrival, which makes the file an
 * auditable record as well as a dataset. Writes are best-effort: a full disk must not break navigation.
 */
public final class NavExperience implements Closeable {
   private final BufferedWriter writer;

   public NavExperience(final File file) throws IOException {
      File parent = file.getParentFile();
      if (parent != null) {
         parent.mkdirs();
      }

      this.writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
         StandardOpenOption.CREATE, StandardOpenOption.APPEND);
   }

   public synchronized void decision(final long trip, final String stage, final String label, final double[] features) {
      StringBuilder line = new StringBuilder("D\t").append(trip).append('\t').append(stage).append('\t').append(label);
      for (double feature : features) {
         line.append('\t').append(String.format(Locale.ROOT, "%.5f", feature));
      }
      write(line);
   }

   public synchronized void finish(final long trip, final String outcome, final long seconds) {
      write(new StringBuilder("E\t").append(trip).append('\t').append(outcome).append('\t').append(seconds));
   }

   private void write(final StringBuilder line) {
      try {
         this.writer.write(line.toString());
         this.writer.newLine();
      } catch (IOException ignored) {
         // Experience is optional; never fail a trip over it.
      }
   }

   public synchronized void flush() {
      try {
         this.writer.flush();
      } catch (IOException ignored) {
      }
   }

   @Override
   public synchronized void close() {
      try {
         this.writer.close();
      } catch (IOException ignored) {
      }
   }
}
