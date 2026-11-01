package net.minecraft.client.headless;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Renders a chat {@link Component} to a string with embedded ANSI escape sequences, so
 * server chat keeps its colours in the console (vanilla §-codes and RGB colours alike).
 *
 * <p>Colours are emitted as 24-bit true-colour sequences ({@code ESC[38;2;r;g;bm}), which
 * Windows Terminal and every modern conhost support; bold/italic/underline/strikethrough
 * map to their SGR codes. Obfuscated text has no terminal equivalent and is rendered
 * as-is. Every segment starts with a reset so styles never bleed into the next one, and
 * the whole string ends with a reset to protect whatever prints after it.
 *
 * <p>The log file appender strips these sequences with a {@code %replace} pattern, so
 * {@code logs/latest.log} stays plain text.
 */
public final class AnsiComponents {
   private static final String RESET = "\u001B[0m";

   private AnsiComponents() {
   }

   public static String render(final Component component) {
      StringBuilder out = new StringBuilder();
      component.visit((style, text) -> {
         appendStyle(out, style);
         out.append(text);
         return Optional.empty();
      }, Style.EMPTY);
      return out.append(RESET).toString();
   }

   private static void appendStyle(final StringBuilder out, final Style style) {
      out.append(RESET);
      TextColor color = style.getColor();
      if (color != null) {
         int rgb = color.getValue();
         out.append("\u001B[38;2;")
            .append((rgb >> 16) & 0xFF)
            .append(';')
            .append((rgb >> 8) & 0xFF)
            .append(';')
            .append(rgb & 0xFF)
            .append('m');
      }

      if (style.isBold()) {
         out.append("\u001B[1m");
      }

      if (style.isItalic()) {
         out.append("\u001B[3m");
      }

      if (style.isUnderlined()) {
         out.append("\u001B[4m");
      }

      if (style.isStrikethrough()) {
         out.append("\u001B[9m");
      }
   }
}
