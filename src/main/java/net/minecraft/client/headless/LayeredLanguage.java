package net.minecraft.client.headless;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;

/**
 * Translation overlay for Minecraft component rendering ({@code translatable} contents),
 * stacked on top of the built-in en_us table:
 *
 * <pre>
 * user-provided &lt;gameDir&gt;/lang/&lt;code&gt;.json   (full official table, wins per key)
 * bundled /assets/eml/lang/&lt;code&gt;.json            (common subset shipped with the bot)
 * bundled /assets/minecraft/lang/en_us.json            (vanilla fallback)
 * the translation key itself                           (last resort)
 * </pre>
 *
 * <p>The overlay follows {@link BotLang#code()}, so a Chinese console also renders game
 * content (join/leave lines, death messages, disconnect reasons, ...) in Chinese. Dropping
 * the official {@code zh_cn.json} from a Minecraft client jar into {@code lang/} inside the
 * game directory upgrades the overlay to full coverage without a rebuild.
 */
public final class LayeredLanguage extends Language {
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

   private final Language base;
   private final Map<String, String> overlay;

   private LayeredLanguage(final Language base, final Map<String, String> overlay) {
      this.base = base;
      this.overlay = overlay;
   }

   /** Installs the overlay for {@code code}; does nothing for en_us or when nothing was found. */
   public static void install(final File gameDirectory, final String code) {
      if (code == null || Language.DEFAULT.equals(code)) {
         return;
      }

      Map<String, String> overlay = new HashMap<>();
      // Read from the jar rather than the game directory: this table must survive a deleted gameDir, which
      // is exactly what "mvn clean" does to target/, and the path here used to name a folder that never
      // existed (assets/hlbot vs the shipped assets/eml), so the bundled overlay silently never loaded.
      loadJson(overlay, LayeredLanguage.class.getResourceAsStream("/assets/eml/lang/" + code + ".json"), "bundled " + code);

      File custom = new File(gameDirectory, "lang" + File.separator + code + ".json");
      if (custom.isFile()) {
         try (InputStream stream = Files.newInputStream(custom.toPath())) {
            Language.loadFromJson(stream, overlay::put);
            LOGGER.info("Loaded {} component translations from {}", code, custom.getPath());
         } catch (Exception e) {
            LOGGER.warn("Could not read {}", custom.getPath(), e);
         }
      }

      if (overlay.isEmpty()) {
         LOGGER.warn("No component translations found for {}; game text stays in {}", code, Language.DEFAULT);
         return;
      }

      Language.inject(new LayeredLanguage(Language.getInstance(), Map.copyOf(overlay)));
      LOGGER.info("Component language overlay: {} ({} keys)", code, overlay.size());
   }

   @Override
   public String getOrDefault(final String elementId, final String defaultValue) {
      String value = this.overlay.get(elementId);
      return value != null ? value : this.base.getOrDefault(elementId, defaultValue);
   }

   @Override
   public boolean has(final String elementId) {
      return this.overlay.containsKey(elementId) || this.base.has(elementId);
   }

   @Override
   public boolean isDefaultRightToLeft() {
      return this.base.isDefaultRightToLeft();
   }

   @Override
   public FormattedCharSequence getVisualOrder(final FormattedText logicalOrderText) {
      return this.base.getVisualOrder(logicalOrderText);
   }

   private static void loadJson(final Map<String, String> output, final InputStream stream, final String label) {
      if (stream == null) {
         return;
      }

      try (stream) {
         Language.loadFromJson(stream, output::put);
      } catch (Exception e) {
         LOGGER.warn("Could not load {} translations", label, e);
      }
   }
}
