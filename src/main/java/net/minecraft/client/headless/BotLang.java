package net.minecraft.client.headless;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Console localisation. Resolution order: explicit code (bot.yml {@code language} or
 * the {@code --language} flag) → OS locale detection → {@code en_us} fallback. Missing
 * keys fall back to en_us, then to the key itself. Language packs are UTF-8
 * {@code key=value} files bundled at {@code /lang/<code>.lang}; currently shipped:
 * en_us, zh_cn, zh_tw, ru_ru, ja_jp.
 */
public final class BotLang {
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
   private static final List<String> SUPPORTED = List.of("en_us", "zh_cn", "zh_tw", "ru_ru", "ja_jp");
   private static final String DEFAULT_LANGUAGE = "en_us";
   private static final Map<String, String> FALLBACK = loadPack(DEFAULT_LANGUAGE);
   private static Map<String, String> active = FALLBACK;
   private static String code = DEFAULT_LANGUAGE;

   private BotLang() {
   }

   /** Selects the active language: explicit code (config/flag), otherwise OS detection. */
   public static void init(final String requested) {
      String selected = requested == null || requested.isBlank()
         ? detectSystemLanguage()
         : requested.toLowerCase(Locale.ROOT).replace('-', '_');
      if (!SUPPORTED.contains(selected)) {
         LOGGER.warn("No language pack for '{}', falling back to {}", selected, DEFAULT_LANGUAGE);
         selected = DEFAULT_LANGUAGE;
      }

      code = selected;
      active = DEFAULT_LANGUAGE.equals(selected) ? FALLBACK : loadPack(selected);
      LOGGER.info("Console language: {}", code);
   }

   public static String code() {
      return code;
   }

   public static List<String> supportedLanguages() {
      return SUPPORTED;
   }

   /** Looks up {@code key} and substitutes {0}, {1}, ... with the given arguments. */
   public static String get(final String key, final Object... args) {
      String pattern = active.get(key);
      if (pattern == null) {
         pattern = FALLBACK.getOrDefault(key, key);
      }

      for (int i = 0; i < args.length; i++) {
         pattern = pattern.replace("{" + i + "}", String.valueOf(args[i]));
      }

      return pattern;
   }

   private static String detectSystemLanguage() {
      // "any platform" means the locale can also be stated in the environment, which is how containers,
      // CI images and locale purges (e.g. Debian without the locales package) advertise it; Locale-
      // getDefault() only reflects those variables where the JVM itself maps them, so read them first.
      // '_'/'-' spellings, language_COUNTRY[.UTF-8] and bare language codes all normalise to one form.
      for (String variable : List.of("LANG", "LC_ALL", "LC_MESSAGES")) {
         String value = System.getenv(variable);
         if (value != null && !value.isBlank() && !"C".equals(value) && !"POSIX".equals(value)) {
            Locale locale = parsePosixLocale(value);
            if (!locale.getLanguage().isEmpty()) {
               return fromLocale(locale);
            }
         }
      }

      return fromLocale(Locale.getDefault());
   }

   /** {@code zh_CN.UTF-8}, {@code zh-CN}, {@code en_US@euro}, {@code C.UTF-8} → a usable Locale tag. */
   private static Locale parsePosixLocale(final String value) {
      String tag = value.strip();
      int dot = tag.indexOf('.');
      if (dot >= 0) {
         tag = tag.substring(0, dot);
      }

      int at = tag.indexOf('@');
      if (at >= 0) {
         tag = tag.substring(0, at);
      }

      return toLocale(tag.replace('_', '-'));
   }

   /** {@code Locale.forLanguageTag} plus the legacy-language normalisation AWT used to do by hand. */
   private static Locale toLocale(final String tag) {
      String fixed = tag
         .replaceFirst("^iw-", "he-").replaceFirst("^iw$", "he")
         .replaceFirst("^in-", "id-").replaceFirst("^in$", "id")
         .replaceFirst("^ji-", "yi-").replaceFirst("^ji$", "yi");
      try {
         return Locale.forLanguageTag(fixed);
      } catch (IllegalArgumentException malformed) {
         return Locale.ROOT;
      }
   }

   private static String fromLocale(final Locale locale) {
      return switch (locale.getLanguage()) {
         case "zh" -> "Hant".equalsIgnoreCase(locale.getScript()) || List.of("TW", "HK", "MO").contains(locale.getCountry()) ? "zh_tw" : "zh_cn";
         case "ru" -> "ru_ru";
         case "ja" -> "ja_jp";
         default -> DEFAULT_LANGUAGE;
      };
   }

   private static Map<String, String> loadPack(final String packCode) {
      Map<String, String> entries = new HashMap<>();
      try (InputStream stream = BotLang.class.getResourceAsStream("/lang/" + packCode + ".lang")) {
         if (stream == null) {
            LOGGER.warn("Language pack {} not found", packCode);
            return entries;
         }

         try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
               String trimmed = line.trim();
               if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                  continue;
               }

               int split = trimmed.indexOf('=');
               if (split > 0) {
                  entries.put(trimmed.substring(0, split).trim(), trimmed.substring(split + 1).trim());
               }
            }
         }
      } catch (Exception e) {
         LOGGER.error("Failed to load language pack {}", packCode, e);
      }

      return entries;
   }
}
