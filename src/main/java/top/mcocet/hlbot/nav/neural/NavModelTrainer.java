package top.mcocet.hlbot.nav.neural;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Offline trainer for the bundled navigation brain. Run from the repository root after changing the expert
 * policy or the features:
 *
 * <pre>
 *   javac -encoding UTF-8 -d build/neural src/main/java/top/mcocet/hlbot/nav/neural/*.java
 *   java -cp build/neural top.mcocet.hlbot.nav.neural.NavModelTrainer
 * </pre>
 *
 * <p>Writes {@code src/main/resources/models/navigation.net}, then reloads it from disk and prints example
 * decisions. {@code verify} as the first argument instead loads the model through the same classpath resource
 * the navigator uses, which is how the packaged jar is checked.
 */
public final class NavModelTrainer {
   private static final Random RANDOM = new Random(20260206L);
   private static final String[] LABELS = {"normal", "wide", "dig", "swim", "fly", "ride", "tunnel", "stop"};

   private record Sample(double[] x, int y) {
   }

   /** What a training run from live experience produced. */
   public record TrainReport(int trips, int samples, double trainAccuracy, double devAccuracy) {
   }

   private NavModelTrainer() {
   }

   public static void main(final String[] args) throws Exception {
      if (args.length > 0 && "verify".equals(args[0])) {
         verify();
         return;
      }

      if (args.length >= 3 && "train".equals(args[0])) {
         TrainReport report = trainFromExperience(Path.of(args[1]), Path.of(args[2]));
         System.out.printf(Locale.ROOT, "[train] trips=%d samples=%d train acc=%.3f dev acc=%.3f -> %s%n",
            report.trips(), report.samples(), report.trainAccuracy(), report.devAccuracy(), args[2]);
         return;
      }

      Path output = Path.of(args.length > 0 ? args[0] : "src/main/resources/models");
      Files.createDirectories(output);
      train(output.resolve("navigation.net"));
   }

   /**
    * The expert the network clones. Means of travel first: the elytra for a very long, safe trip; a vehicle
    * for a medium trip when one stands nearby (a boat is what a wet corridor wants); swimming when the road
    * ahead is mostly water and nothing better is available. Then the walking styles: a wider search for long
    * open ground, digging for broken or repeatedly failing terrain, a tunnel as the last resort, and a clean
    * stop when even digging is forbidden.
    */
   private static String expert(final NavBrain.Situation s) {
      if (s.planFailures() >= 3) {
         return s.digAllowed() ? "tunnel" : "stop";
      }

      if (s.planFailures() >= 1 && s.openness() < 0.35 && s.digAllowed()) {
         return "dig";
      }

      if (s.elytraEquipped() && s.horizontalDistance() > 160.0 && s.corridorHazard() < 0.02) {
         return "fly";
      }

      if (s.vehicleNearby() && s.horizontalDistance() > 48.0 && s.corridorWater() > 0.4) {
         return "ride";
      }

      if (s.vehicleNearby() && s.horizontalDistance() > 64.0 && s.corridorOpenness() > 0.5) {
         return "ride";
      }

      if (s.corridorWater() > 0.3 && s.horizontalDistance() < 160.0) {
         return "swim";
      }

      if (s.hazardRatio() > 0.02 || s.waterRatio() > 0.15 || s.corridorHazard() > 0.02) {
         return "normal";
      }

      if (s.horizontalDistance() > 96.0 && s.openness() > 0.55) {
         return "wide";
      }

      if (s.openness() < 0.2 && s.digAllowed()) {
         return "dig";
      }

      return "normal";
   }

   private static void train(final Path file) throws IOException {
      String[] labels = LABELS;
      List<Sample> train = new ArrayList<>();
      List<Sample> dev = new ArrayList<>();
      List<Sample> all = expertSamples(8000, labels);

      for (int i = 0; i < all.size(); i++) {
         (i % 10 == 0 ? dev : train).add(all.get(i));
      }

      TinyNet net = new TinyNet(NavBrain.features(examples().get(0)).length, 20, labels);
      initialise(net);
      train(net, train, 400, 0.05, 32);
      System.out.printf(Locale.ROOT, "[nav] train=%d dev=%d, train acc=%.3f, dev acc=%.3f%n",
         train.size(), dev.size(), accuracy(net, train), accuracy(net, dev));
      write(file, net);

      TinyNet reloaded = TinyNet.read(Files.newBufferedReader(file, StandardCharsets.UTF_8));
      for (NavBrain.Situation s : examples()) {
         TinyNet.Prediction p = reloaded.predict(NavBrain.features(s));
         System.out.printf(Locale.ROOT, "[nav] dist=%.0f open=%.2f waterAhead=%.2f elytra=%b vehicle=%b failures=%d -> %s (%.2f)%n",
            s.horizontalDistance(), s.openness(), s.corridorWater(), s.elytraEquipped(), s.vehicleNearby(),
            s.planFailures(), p.label(), p.confidence());
      }
   }

   /** Random situations labelled by the expert; also mixed into live training to prevent forgetting. */
   private static List<Sample> expertSamples(final int count, final String[] labels) {
      List<Sample> samples = new ArrayList<>();

      for (int i = 0; i < count; i++) {
         NavBrain.Situation s = new NavBrain.Situation(
            RANDOM.nextDouble() * 400.0,
            RANDOM.nextDouble() * 40.0,
            RANDOM.nextBoolean(),
            RANDOM.nextInt(5),
            RANDOM.nextDouble(),
            RANDOM.nextDouble() * 0.08,
            RANDOM.nextDouble() * 0.4,
            RANDOM.nextInt(5),
            RANDOM.nextDouble() < 0.8,
            RANDOM.nextInt(120),
            RANDOM.nextDouble() < 0.4,
            RANDOM.nextDouble() < 0.4,
            RANDOM.nextDouble(),
            RANDOM.nextDouble() * 0.7,
            RANDOM.nextDouble() * 0.08
         );
         samples.add(new Sample(NavBrain.features(s), List.of(labels).indexOf(expert(s))));
      }

      return samples;
   }

   /**
    * Trains a new model from a live experience log (the D/E lines {@link NavExperience} writes). Only trips
    * that ended in an arrival contribute samples - the file is behavioural cloning of what WORKED, not a
    * record of what to avoid. Decisions made by a successful rescue count double, and a matching number of
    * expert samples is mixed in so the model does not forget the basics after a handful of trips. The dev
    * split is by trip, never sharing one trip between train and dev.
    */
   public static TrainReport trainFromExperience(final Path experience, final Path output) throws IOException {
      java.util.Map<Long, List<String[]>> decisions = new java.util.LinkedHashMap<>();
      java.util.Map<Long, String> outcomes = new java.util.HashMap<>();

      for (String line : Files.readAllLines(experience, StandardCharsets.UTF_8)) {
         if (line.isBlank()) {
            continue;
         }

         String[] parts = line.split("\t");

         if (parts.length >= 4 && "D".equals(parts[0])) {
            decisions.computeIfAbsent(Long.parseLong(parts[1]), key -> new ArrayList<>()).add(parts);
         } else if (parts.length >= 3 && "E".equals(parts[0])) {
            outcomes.put(Long.parseLong(parts[1]), parts[2]);
         }
      }

      List<Sample> train = new ArrayList<>();
      List<Sample> dev = new ArrayList<>();
      int trips = 0;
      int index = 0;

      for (java.util.Map.Entry<Long, List<String[]>> entry : decisions.entrySet()) {
         index++;

         if (!"arrived".equals(outcomes.get(entry.getKey()))) {
            continue;
         }

         trips++;
         List<Sample> target = index % 10 == 0 ? dev : train;

         for (String[] d : entry.getValue()) {
            double[] features = new double[d.length - 4];
            for (int i = 4; i < d.length; i++) {
               features[i - 4] = Double.parseDouble(d[i]);
            }

            int y = List.of(LABELS).indexOf(d[3]);
            if (y < 0) {
               continue;
            }

            Sample sample = new Sample(features, y);
            target.add(sample);

            if ("rescue".equals(d[2])) {
               target.add(sample);
            }
         }
      }

      if (train.size() < 20) {
         int decisionLines = decisions.values().stream().mapToInt(List::size).sum();
         throw new IOException("not enough successful experience: " + train.size() + " sample(s) from " + trips
            + " arrived trip(s); the log has " + decisionLines + " decision line(s) and " + outcomes.size()
            + " outcome line(s). Turn on 'goto neural on' and 'goto neural record on', then let some trips"
            + " actually ARRIVE (failed or manually stopped trips are not training samples)");
      }

      train.addAll(expertSamples(Math.max(500, train.size()), LABELS));
      TinyNet net = new TinyNet(train.get(0).x().length, 20, LABELS);
      initialise(net);
      train(net, train, 300, 0.03, 32);
      double trainAccuracy = accuracy(net, train);
      double devAccuracy = dev.isEmpty() ? trainAccuracy : accuracy(net, dev);
      write(output, net);
      return new TrainReport(trips, train.size(), trainAccuracy, devAccuracy);
   }

   private static List<NavBrain.Situation> examples() {
      return List.of(
         // Long, open, safe, wings on: fly.
         new NavBrain.Situation(300, 5, true, 0, 0.8, 0.0, 0.0, 0, true, 0, true, false, 0.8, 0.0, 0.0),
         // Same trip without wings: a wider walk.
         new NavBrain.Situation(300, 5, true, 0, 0.8, 0.0, 0.0, 0, true, 0, false, false, 0.8, 0.0, 0.0),
         // A river crossing with a boat at hand: ride.
         new NavBrain.Situation(80, 2, false, 0, 0.5, 0.0, 0.1, 0, true, 0, false, true, 0.4, 0.6, 0.0),
         // A river crossing without one: swim.
         new NavBrain.Situation(80, 2, false, 0, 0.5, 0.0, 0.1, 0, true, 0, false, false, 0.4, 0.6, 0.0),
         // Medium trip on open ground with a horse nearby: ride.
         new NavBrain.Situation(90, 3, false, 0, 0.7, 0.0, 0.0, 0, true, 0, false, true, 0.7, 0.0, 0.0),
         // Short walk: normal.
         new NavBrain.Situation(20, 3, false, 0, 0.7, 0.0, 0.0, 0, true, 0, false, false, 0.7, 0.0, 0.0),
         // Broken terrain after a failure: dig.
         new NavBrain.Situation(60, 10, false, 1, 0.1, 0.0, 0.0, 1, true, 40, false, false, 0.1, 0.0, 0.0),
         // Planning keeps failing: tunnel, or stop when digging is forbidden.
         new NavBrain.Situation(150, 5, true, 2, 0.3, 0.0, 0.0, 3, true, 60, false, false, 0.3, 0.0, 0.0),
         new NavBrain.Situation(150, 5, true, 2, 0.3, 0.0, 0.0, 3, false, 60, false, false, 0.3, 0.0, 0.0),
         // Long trip with wings but a hazardous corridor: no flight.
         new NavBrain.Situation(300, 5, true, 0, 0.8, 0.0, 0.0, 0, true, 0, true, false, 0.8, 0.0, 0.05)
      );
   }

   /** Loads the model through the same resource path the navigator uses and prints example decisions. */
   private static void verify() {
      NavBrain brain = NavBrain.load();
      System.out.println("[verify] navigation.net loaded=" + (brain != null));

      if (brain == null) {
         return;
      }

      for (NavBrain.Situation s : examples()) {
         System.out.printf(Locale.ROOT, "[verify] dist=%.0f waterAhead=%.2f elytra=%b vehicle=%b failures=%d -> %s%n",
            s.horizontalDistance(), s.corridorWater(), s.elytraEquipped(), s.vehicleNearby(), s.planFailures(), brain.decide(s));
      }
   }

   // ==================== training loop ====================

   private static void initialise(final TinyNet net) {
      for (int i = 0; i < net.w1.length; i++) {
         net.w1[i] = (float) RANDOM.nextGaussian(0.0, Math.sqrt(2.0 / net.inputs));
      }

      for (int i = 0; i < net.w2.length; i++) {
         net.w2[i] = (float) RANDOM.nextGaussian(0.0, Math.sqrt(2.0 / net.hidden));
      }
   }

   private static double accuracy(final TinyNet net, final List<Sample> data) {
      int correct = 0;

      for (Sample sample : data) {
         if (net.predict(sample.x()).index() == sample.y()) {
            correct++;
         }
      }

      return data.isEmpty() ? 0.0 : (double) correct / data.size();
   }

   /** Mini-batch SGD with momentum on softmax cross-entropy, plus a little L2 to keep the weights tame. */
   private static void train(final TinyNet net, final List<Sample> data, final int epochs, final double learningRate, final int batchSize) {
      float[] vw1 = new float[net.w1.length];
      float[] vb1 = new float[net.b1.length];
      float[] vw2 = new float[net.w2.length];
      float[] vb2 = new float[net.b2.length];
      float[] gw1 = new float[net.w1.length];
      float[] gb1 = new float[net.b1.length];
      float[] gw2 = new float[net.w2.length];
      float[] gb2 = new float[net.b2.length];
      List<Sample> shuffled = new ArrayList<>(data);
      int outputs = net.labels.length;

      for (int epoch = 0; epoch < epochs; epoch++) {
         Collections.shuffle(shuffled, RANDOM);
         double lr = learningRate / (1.0 + 0.01 * epoch);

         for (int start = 0; start < shuffled.size(); start += batchSize) {
            int end = Math.min(shuffled.size(), start + batchSize);
            Arrays.fill(gw1, 0.0f);
            Arrays.fill(gb1, 0.0f);
            Arrays.fill(gw2, 0.0f);
            Arrays.fill(gb2, 0.0f);

            for (int s = start; s < end; s++) {
               Sample sample = shuffled.get(s);
               double[] x = sample.x();
               float[] hiddenOut = new float[net.hidden];

               for (int h = 0; h < net.hidden; h++) {
                  double sum = net.b1[h];
                  int base = h * net.inputs;
                  for (int i = 0; i < net.inputs; i++) {
                     sum += net.w1[base + i] * x[i];
                  }
                  hiddenOut[h] = (float) Math.tanh(sum);
               }

               float[] probs = net.probabilities(x);
               double[] dz2 = new double[outputs];
               for (int o = 0; o < outputs; o++) {
                  dz2[o] = probs[o] - (o == sample.y() ? 1.0 : 0.0);
               }

               for (int o = 0; o < outputs; o++) {
                  int base = o * net.hidden;
                  for (int h = 0; h < net.hidden; h++) {
                     gw2[base + h] += (float) (dz2[o] * hiddenOut[h]);
                  }
                  gb2[o] += (float) dz2[o];
               }

               for (int h = 0; h < net.hidden; h++) {
                  double dh = 0.0;
                  for (int o = 0; o < outputs; o++) {
                     dh += net.w2[o * net.hidden + h] * dz2[o];
                  }
                  dh *= 1.0 - hiddenOut[h] * hiddenOut[h];
                  int base = h * net.inputs;
                  for (int i = 0; i < net.inputs; i++) {
                     gw1[base + i] += (float) (dh * x[i]);
                  }
                  gb1[h] += (float) dh;
               }
            }

            double scale = 1.0 / (end - start);
            update(net.w1, vw1, gw1, lr, scale);
            update(net.b1, vb1, gb1, lr, scale);
            update(net.w2, vw2, gw2, lr, scale);
            update(net.b2, vb2, gb2, lr, scale);
         }
      }
   }

   private static void update(final float[] weights, final float[] velocity, final float[] gradient, final double lr, final double scale) {
      for (int i = 0; i < weights.length; i++) {
         double g = gradient[i] * scale + 1.0e-4 * weights[i];
         velocity[i] = (float) (0.9 * velocity[i] - lr * g);
         weights[i] += velocity[i];
      }
   }

   private static void write(final Path file, final TinyNet net) throws IOException {
      try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
         out.println("tiny-net 1");
         out.println("inputs " + net.inputs);
         out.println("hidden " + net.hidden);
         out.println("labels " + String.join(",", net.labels));
         weights(out, "w1", net.w1);
         weights(out, "b1", net.b1);
         weights(out, "w2", net.w2);
         weights(out, "b2", net.b2);
      }

      System.out.println("[write] " + file + " (" + Files.size(file) + " bytes)");
   }

   private static void weights(final PrintWriter out, final String name, final float[] values) {
      out.print(name);
      for (float value : values) {
         out.print(" " + String.format(Locale.ROOT, "%.6g", value));
      }
      out.println();
   }
}
