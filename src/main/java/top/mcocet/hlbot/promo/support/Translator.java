package top.mcocet.hlbot.promo.support;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Key/value translations loaded from {@code /lang/<code>.lang} inside the plugin jar.
 *
 * <p>The bot has its own language machinery for engine strings, but a plugin should not depend on it: this
 * table is loaded from the plugin's own resources, falls back to {@code zh_cn}, and finally to the key
 * itself. A missing or broken file therefore yields readable output instead of exceptions, which matters
 * because console output is the only way to diagnose the plugin when its own messages are broken.
 */
public final class Translator {
   private static final String FALLBACK = "zh_cn";

   private final Map<String, String> table = new LinkedHashMap<>();
   private final String code;

   public Translator(final ClassLoader loader, final String language) {
      String requested = language == null || language.isBlank() ? FALLBACK : language.trim();
      this.code = requested;
      this.load(loader, requested);

      if (this.table.isEmpty() && !FALLBACK.equals(requested)) {
         this.load(loader, FALLBACK);
      }
   }

   private void load(final ClassLoader loader, final String language) {
      // Bundled under /promo/lang/ so the ported resources never collide with the bot's own lang packs.
      String resource = "/promo/lang/" + language + ".lang";

      try (InputStream stream = loader == null
         ? Translator.class.getResourceAsStream(resource)
         : loader.getResourceAsStream("promo/lang/" + language + ".lang")) {
         if (stream == null) {
            return;
         }

         for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
               continue;
            }

            int equals = trimmed.indexOf('=');

            if (equals > 0) {
               this.table.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
            }
         }
      } catch (Exception unreadable) {
         // Empty table: every lookup returns its key, which is ugly but never wrong.
      }
   }

   /** The language actually loaded, for the console banner. */
   public String language() {
      return this.code;
   }

   /**
    * Looks a key up and substitutes {@code {0}}, {@code {1}}, ... in order.
    *
    * <p>Positional replacement rather than {@code String.format} on purpose: advertisement text is full of
    * {@code %} and {@code §} sequences, and a single stray percent would make formatting throw exactly when
    * a message is being built.
    */
   public String get(final String key, final Object... arguments) {
      String pattern = this.table.getOrDefault(key, key);

      if (arguments == null || arguments.length == 0) {
         return pattern;
      }

      String result = pattern;

      for (int index = 0; index < arguments.length; index++) {
         result = result.replace("{" + index + "}", String.valueOf(arguments[index]));
      }

      return result;
   }

   /** Number of loaded keys, used by {@code /promo epa status} to prove the file was found. */
   public int size() {
      return this.table.size();
   }
}
