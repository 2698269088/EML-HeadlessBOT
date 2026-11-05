package top.mcocet.hlbot.promo.support;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text pipeline for one advertisement line: placeholders, optional digit styling, optional random suffix.
 *
 * <p>Deliberately a bag of pure static functions with no state and no bot reference. The original plugin had
 * these three transformations spread over a sender class, a scheduler and a command executor, which is why
 * "is the suffix applied in private mode?" could not be answered without reading all three. Here the order
 * is visible in one place and every caller gets the same result.
 */
public final class AdText {
   /** Runs of digits, used by the mathematical-bold substitution. */
   private static final Pattern DIGITS = Pattern.compile("\\d+");

   /** Unambiguous alphabet: no 0/O, 1/l/I, so a human retyping a line cannot get it wrong. */
   private static final String SUFFIX_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

   private AdText() {
   }

   /**
    * Substitutes the placeholders the config documents.
    *
    * @param name    player name for {@code #name#}; may be {@code null} in public mode
    * @param bot     this bot's own name
    * @param players online player count
    */
   public static String fill(final String template, final String name, final String bot, final int players) {
      if (template == null || template.isEmpty()) {
         return "";
      }

      String filled = template
         .replace("#name#", name == null ? "" : name)
         .replace("#bot#", bot == null ? "" : bot)
         .replace("#players#", String.valueOf(players));

      // #time# is local wall-clock; an advertisement that shows a clock has to look current, and the ad
      // loop can run for days, so it is resolved per send rather than cached.
      return filled.replace("#time#", java.time.LocalTime.now()
         .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
   }

   /** Convenience overload using a placeholder map, for callers that resolve their own values. */
   public static String fill(final String template, final Map<String, String> values) {
      String filled = template == null ? "" : template;

      for (Map.Entry<String, String> entry : values.entrySet()) {
         filled = filled.replace("#" + entry.getKey() + "#", entry.getValue() == null ? "" : entry.getValue());
      }

      return filled;
   }

   /**
    * Replaces every run of at least {@code minDigits} digits with mathematical-bold digits.
    *
    * <p>Same feature as the original plugin: it makes a phone number or a URL tail visually distinct, which
     * is what an advertiser typically wants. Off by default because the Unicode block is not in every
    * server's font, where it degrades to empty boxes.
    */
   public static String mathFont(final String text, final int minDigits) {
      if (text == null || text.isEmpty() || minDigits <= 0) {
         return text;
      }

      Matcher matcher = DIGITS.matcher(text);
      StringBuilder out = new StringBuilder(text.length() + 16);

      while (matcher.find()) {
         String run = matcher.group();
         String replacement = run.length() >= minDigits ? bold(run) : run;
         matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
      }

      matcher.appendTail(out);
      return out.toString();
   }

   private static String bold(final String digits) {
      StringBuilder out = new StringBuilder(digits.length() * 2);

      for (int index = 0; index < digits.length(); index++) {
         char digit = digits.charAt(index);

         if (digit >= '0' && digit <= '9') {
            // U+1D7CE..U+1D7D7 MATHEMATICAL BOLD DIGIT ZERO..NINE
            out.appendCodePoint(0x1D7CE + (digit - '0'));
         } else {
            out.append(digit);
         }
      }

      return out.toString();
   }

   /** A short random tail, used to make repeated lines unique. */
   public static String suffix(final int length) {
      if (length <= 0) {
         return "";
      }

      StringBuilder out = new StringBuilder(length);

      for (int index = 0; index < length; index++) {
         out.append(SUFFIX_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(SUFFIX_ALPHABET.length())));
      }

      return out.toString();
   }

   /** Lower-cases for case-insensitive name comparison, where Minecraft names are compared as text. */
   public static String key(final String name) {
      return name == null ? "" : name.toLowerCase(Locale.ROOT);
   }
}
