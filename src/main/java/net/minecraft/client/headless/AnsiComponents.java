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
 * map to their SGR codes. Obfuscated text has no terminal equivalent, so only its control
 * code is removed. Component styles are reapplied at segment boundaries while literal legacy
 * formatting carries across them until a colour/reset code; a final reset protects later output.
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
      LegacyStyle legacy = new LegacyStyle();
      component.visit((style, text) -> {
         appendSegment(out, style, text, legacy);
         return Optional.empty();
      }, Style.EMPTY);
      return out.append(RESET).toString();
   }

   /** Some servers send legacy codes as literal text instead of converting them to component styles. */
   private static void appendSegment(final StringBuilder out, final Style style, final String text,
                                     final LegacyStyle legacy) {
      appendStyle(out, style, legacy);
      for (int i = 0; i < text.length(); i++) {
         char current = text.charAt(i);
         if (current != '§' || i + 1 >= text.length()) {
            out.append(current);
            continue;
         }

         char code = Character.toLowerCase(text.charAt(i + 1));
         if (!applyLegacy(legacy, code)) {
            out.append(current);
            continue;
         }

         i++;
         appendStyle(out, style, legacy);
      }
   }

   private static boolean applyLegacy(final LegacyStyle style, final char code) {
      Integer color = legacyColor(code);
      if (color != null) {
         style.color = color;
         style.bold = false;
         style.italic = false;
         style.underlined = false;
         style.strikethrough = false;
         return true;
      }

      switch (code) {
         case 'k' -> {
            return true;
         }
         case 'l' -> style.bold = true;
         case 'm' -> style.strikethrough = true;
         case 'n' -> style.underlined = true;
         case 'o' -> style.italic = true;
         case 'r' -> {
            style.color = null;
            style.bold = false;
            style.italic = false;
            style.underlined = false;
            style.strikethrough = false;
         }
         default -> {
            return false;
         }
      }

      return true;
   }

   private static Integer legacyColor(final char code) {
      return switch (code) {
         case '0' -> 0x000000;
         case '1' -> 0x0000AA;
         case '2' -> 0x00AA00;
         case '3' -> 0x00AAAA;
         case '4' -> 0xAA0000;
         case '5' -> 0xAA00AA;
         case '6' -> 0xFFAA00;
         case '7' -> 0xAAAAAA;
         case '8' -> 0x555555;
         case '9' -> 0x5555FF;
         case 'a' -> 0x55FF55;
         case 'b' -> 0x55FFFF;
         case 'c' -> 0xFF5555;
         case 'd' -> 0xFF55FF;
         case 'e' -> 0xFFFF55;
         case 'f' -> 0xFFFFFF;
         default -> null;
      };
   }

   private static void appendStyle(final StringBuilder out, final Style style, final LegacyStyle legacy) {
      out.append(RESET);
      TextColor componentColor = style.getColor();
      Integer rgb = legacy.color != null ? legacy.color : componentColor == null ? null : componentColor.getValue();
      if (rgb != null) {
         out.append("\u001B[38;2;")
            .append((rgb >> 16) & 0xFF)
            .append(';')
            .append((rgb >> 8) & 0xFF)
            .append(';')
            .append(rgb & 0xFF)
            .append('m');
      }

      if (legacy.bold || style.isBold()) {
         out.append("\u001B[1m");
      }

      if (legacy.italic || style.isItalic()) {
         out.append("\u001B[3m");
      }

      if (legacy.underlined || style.isUnderlined()) {
         out.append("\u001B[4m");
      }

      if (legacy.strikethrough || style.isStrikethrough()) {
         out.append("\u001B[9m");
      }
   }

   private static final class LegacyStyle {
      private Integer color;
      private boolean bold;
      private boolean italic;
      private boolean underlined;
      private boolean strikethrough;
   }
}
