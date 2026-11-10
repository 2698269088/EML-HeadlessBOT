package top.mcocet.hlbot.nav.neural;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A tiny fully-connected network: dense input, one tanh hidden layer, softmax output. The weights ship as a
 * plain-text resource inside the bot jar, so a planning decision costs a few thousand multiply-adds and no
 * library at all. Trained offline by {@link NavModelTrainer}; nothing here learns at runtime.
 *
 * <p>Text format, one section per line: {@code tiny-net 1}, {@code inputs N}, {@code hidden N},
 * {@code labels a,b,c}, then {@code w1}/{@code b1}/{@code w2}/{@code b2} with space-separated floats.
 */
final class TinyNet {
   final int inputs;
   final int hidden;
   final String[] labels;
   /** Row-major [hidden][inputs]. */
   final float[] w1;
   final float[] b1;
   /** Row-major [labels.length][hidden]. */
   final float[] w2;
   final float[] b2;

   TinyNet(final int inputs, final int hidden, final String[] labels) {
      this.inputs = inputs;
      this.hidden = hidden;
      this.labels = labels;
      this.w1 = new float[hidden * inputs];
      this.b1 = new float[hidden];
      this.w2 = new float[labels.length * hidden];
      this.b2 = new float[labels.length];
   }

   /** A label and its softmax probability. */
   record Prediction(String label, int index, float confidence) {
   }

   Prediction predict(final double[] input) {
      float[] probabilities = probabilities(input);
      int best = 0;

      for (int i = 1; i < probabilities.length; i++) {
         if (probabilities[i] > probabilities[best]) {
            best = i;
         }
      }

      return new Prediction(this.labels[best], best, probabilities[best]);
   }

   float[] probabilities(final double[] input) {
      if (input.length != this.inputs) {
         throw new IllegalArgumentException("expected " + this.inputs + " features, got " + input.length);
      }

      float[] hiddenOut = new float[this.hidden];

      for (int h = 0; h < this.hidden; h++) {
         double sum = this.b1[h];
         int base = h * this.inputs;

         for (int i = 0; i < this.inputs; i++) {
            sum += this.w1[base + i] * input[i];
         }

         hiddenOut[h] = (float) Math.tanh(sum);
      }

      int outputs = this.labels.length;
      float[] out = new float[outputs];
      double max = Double.NEGATIVE_INFINITY;
      double[] logits = new double[outputs];

      for (int o = 0; o < outputs; o++) {
         double sum = this.b2[o];
         int base = o * this.hidden;

         for (int h = 0; h < this.hidden; h++) {
            sum += this.w2[base + h] * hiddenOut[h];
         }

         logits[o] = sum;
         max = Math.max(max, sum);
      }

      double total = 0.0;

      for (int o = 0; o < outputs; o++) {
         out[o] = (float) Math.exp(logits[o] - max);
         total += out[o];
      }

      for (int o = 0; o < outputs; o++) {
         out[o] /= (float) total;
      }

      return out;
   }

   /** Loads a bundled model, or null when the resource is absent or malformed (the caller then uses rules). */
   static TinyNet load(final String resource) {
      try (InputStream stream = TinyNet.class.getResourceAsStream(resource)) {
         if (stream == null) {
            return null;
         }

         return read(new InputStreamReader(stream, StandardCharsets.UTF_8));
      } catch (IOException | RuntimeException e) {
         return null;
      }
   }

   static TinyNet read(final Reader source) throws IOException {
      BufferedReader reader = new BufferedReader(source);
      List<String> lines = new ArrayList<>();

      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
         if (!line.isBlank()) {
            lines.add(line.trim());
         }
      }

      if (!lines.isEmpty() && lines.get(0).startsWith("tiny-net") && !"tiny-net 1".equals(lines.get(0))) {
         throw new IOException("unsupported model format: " + lines.get(0));
      }

      int inputs = -1;
      int hidden = -1;
      String[] labels = null;

      for (String line : lines) {
         if (line.startsWith("inputs ")) {
            inputs = Integer.parseInt(line.substring(7).trim());
         } else if (line.startsWith("hidden ")) {
            hidden = Integer.parseInt(line.substring(7).trim());
         } else if (line.startsWith("labels ")) {
            labels = line.substring(7).trim().split(",");
         }
      }

      if (inputs <= 0 || hidden <= 0 || labels == null || labels.length < 2) {
         throw new IOException("model header is incomplete");
      }

      TinyNet net = new TinyNet(inputs, hidden, labels);

      for (String line : lines) {
         if (line.startsWith("w1 ")) {
            fill(net.w1, line.substring(3));
         } else if (line.startsWith("b1 ")) {
            fill(net.b1, line.substring(3));
         } else if (line.startsWith("w2 ")) {
            fill(net.w2, line.substring(3));
         } else if (line.startsWith("b2 ")) {
            fill(net.b2, line.substring(3));
         }
      }

      return net;
   }

   private static void fill(final float[] target, final String text) throws IOException {
      String[] parts = text.trim().split("\\s+");

      if (parts.length != target.length) {
         throw new IOException(String.format(Locale.ROOT, "expected %d weights, got %d", target.length, parts.length));
      }

      for (int i = 0; i < parts.length; i++) {
         target[i] = Float.parseFloat(parts[i]);
      }
   }
}
