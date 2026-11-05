package top.mcocet.hlbot.promo.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import top.mcocet.hlbot.plugins.Config;

/**
 * Typed view over the plugin's {@code config.yml}.
 *
 * <p>Every key string in the plugin appears exactly once, here. The original implementation spread ~40 keys
 * over a 600-line Gson reader with hand-written null checks - and {@code root.get("x").getAsInt()} threw a
 * NullPointerException the moment a user deleted one line, which is a poor trade for a file users are
 * invited to edit.
 *
 * <p>Reads go through the framework's {@link Config}, which returns the supplied default for a missing or
 * unparsable value; so a truncated config file degrades to "defaults" instead of failing the plugin. Writes
 * mutate the live tree and are persisted explicitly through {@link #save()} so a command that validates
 * several values cannot leave a half-applied file behind.
 */
public final class PgaSettings {
   /** How a round reaches its audience. */
   public enum Mode {
      PUBLIC, PRIVATE;

      /** Case-insensitive parse used by the command; unknown input yields {@code null} rather than a guess. */
      public static Mode parse(final String raw) {
         if (raw == null) {
            return null;
         }

         try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
         } catch (IllegalArgumentException unknown) {
            return null;
         }
      }
   }

   private final Config config;

   public PgaSettings(final Config config) {
      this.config = config;
   }

   public void save() {
      this.config.save();
   }

   public void reload() {
      this.config.reload();
   }

   public Config raw() {
      return this.config;
   }

   // ==================== scalars ====================

   public boolean enabled() {
      return this.config.getBoolean("enabled", true);
   }

   public void enabled(final boolean value) {
      this.config.set("enabled", value);
   }

   public String language() {
      return this.config.getString("language", "zh_cn");
   }

   // ==================== schedule ====================

   public Mode mode() {
      Mode mode = Mode.parse(this.config.getString("schedule.mode", "public"));
      return mode == null ? Mode.PUBLIC : mode;
   }

   public void mode(final Mode value) {
      this.config.set("schedule.mode", value.name().toLowerCase(Locale.ROOT));
   }

   public int publicIntervalSeconds() {
      return positive("schedule.publicIntervalSeconds", 40);
   }

   public int privateIntervalSeconds() {
      return positive("schedule.privateIntervalSeconds", 10);
   }

   /** The round interval for the active mode. */
   public int roundIntervalSeconds() {
      return this.mode() == Mode.PRIVATE ? this.privateIntervalSeconds() : this.publicIntervalSeconds();
   }

   public void publicIntervalSeconds(final int seconds) {
      this.config.set("schedule.publicIntervalSeconds", seconds);
   }

   public void privateIntervalSeconds(final int seconds) {
      this.config.set("schedule.privateIntervalSeconds", seconds);
   }

   public int gapSeconds() {
      return positive("schedule.gapSeconds", 4);
   }

   public void gapSeconds(final int seconds) {
      this.config.set("schedule.gapSeconds", seconds);
   }

   public int jitterSeconds() {
      return Math.max(0, this.config.getInt("schedule.jitterSeconds", 5));
   }

   public void jitterSeconds(final int seconds) {
      this.config.set("schedule.jitterSeconds", seconds);
   }

   public int gapJitterSeconds() {
      return Math.max(0, this.config.getInt("schedule.gapJitterSeconds", 3));
   }

   public void gapJitterSeconds(final int seconds) {
      this.config.set("schedule.gapJitterSeconds", seconds);
   }

   public boolean randomOne() {
      return this.config.getBoolean("schedule.randomOne", false);
   }

   public void randomOne(final boolean value) {
      this.config.set("schedule.randomOne", value);
   }

   public boolean skipRepeats() {
      return this.config.getBoolean("schedule.skipRepeats", true);
   }

   public void skipRepeats(final boolean value) {
      this.config.set("schedule.skipRepeats", value);
   }

   // ==================== text ====================

   public boolean suffixEnabled() {
      return this.config.getBoolean("text.suffix.enabled", true);
   }

   public void suffixEnabled(final boolean value) {
      this.config.set("text.suffix.enabled", value);
   }

   public int suffixLength() {
      return this.config.getInt("text.suffix.length", 4);
   }

   public void suffixLength(final int length) {
      this.config.set("text.suffix.length", length);
   }

   public boolean mathFont() {
      return this.config.getBoolean("text.mathFont.enabled", false);
   }

   public void mathFont(final boolean value) {
      this.config.set("text.mathFont.enabled", value);
   }

   public boolean burstMathFont() {
      return this.config.getBoolean("text.mathFont.burstMathFont", false);
   }

   public void burstMathFont(final boolean value) {
      this.config.set("text.mathFont.burstMathFont", value);
   }

   public int minDigits() {
      return this.config.getInt("text.mathFont.minDigits", 5);
   }

   public void minDigits(final int value) {
      this.config.set("text.mathFont.minDigits", value);
   }

   // ==================== greeting ====================

   public boolean greetingEnabled() {
      return this.config.getBoolean("greeting.enabled", false);
   }

   public void greetingEnabled(final boolean value) {
      this.config.set("greeting.enabled", value);
   }

   public String greetingFormat() {
      return this.config.getString("greeting.format", "hi，#name#");
   }

   public void greetingFormat(final String format) {
      this.config.set("greeting.format", format);
   }

   // ==================== burst ====================

   public boolean burstEnabled() {
      return this.config.getBoolean("burst.enabled", false);
   }

   public void burstEnabled(final boolean value) {
      this.config.set("burst.enabled", value);
   }

   public int burstIntervalSeconds() {
      return positive("burst.intervalSeconds", 2);
   }

   public void burstIntervalSeconds(final int seconds) {
      this.config.set("burst.intervalSeconds", seconds);
   }

   public int burstDurationSeconds() {
      return positive("burst.durationSeconds", 30);
   }

   public void burstDurationSeconds(final int seconds) {
      this.config.set("burst.durationSeconds", seconds);
   }

   public int burstCheckSeconds() {
      return positive("burst.checkSeconds", 5);
   }

   public boolean burstSync() {
      return this.config.getBoolean("burst.syncWithMain", false);
   }

   public void burstSync(final boolean value) {
      this.config.set("burst.syncWithMain", value);
   }

   // ==================== lists ====================

   public List<String> messages() {
      return this.strings("messages");
   }

   /**
    * The burst list.
    *
    * <p>Three cases, in order: syncing mirrors the main list, an empty burst list falls back to the main list,
    * and otherwise the burst list is used. The fallback matters - an empty list would otherwise mean "bursts
    * send nothing", which reads as a broken feature rather than an unconfigured one.
    */
   public List<String> burstMessages() {
      if (this.burstSync()) {
         return this.messages();
      }

      List<String> burst = this.strings("burstMessages");
      return burst.isEmpty() ? this.messages() : burst;
   }

   public List<String> blacklist() {
      return this.strings("blacklist");
   }

   public void messages(final List<String> values) {
      this.config.set("messages", new ArrayList<>(values));
   }

   public void burstMessages(final List<String> values) {
      this.config.set("burstMessages", new ArrayList<>(values));
   }

   public void blacklist(final List<String> values) {
      this.config.set("blacklist", new ArrayList<>(values));
   }

   /** Sets one message by index, growing the list with blank entries when needed. */
   public boolean setMessage(final int index, final String text) {
      List<String> current = this.messages();

      if (index < 0) {
         return false;
      }

      while (current.size() <= index) {
         current.add("");
      }

      current.set(index, text);
      this.messages(current);
      return true;
   }

   private List<String> strings(final String path) {
      List<String> values = new ArrayList<>();

      for (Object entry : this.config.getList(path)) {
         if (entry != null) {
            values.add(String.valueOf(entry));
         }
      }

      return values;
   }

   /** Reads an int that must be at least 1; a config of 0 or a negative number would stop the campaign. */
   private int positive(final String path, final int fallback) {
      int value = this.config.getInt(path, fallback);
      return value <= 0 ? fallback : value;
   }
}
