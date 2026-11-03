package top.mcocet.hlbot.promo.command;

import java.util.List;
import java.util.Locale;
import top.mcocet.hlbot.promo.EmlPgaPlugin;
import top.mcocet.hlbot.promo.campaign.AdCampaign;
import top.mcocet.hlbot.promo.campaign.BurstSender;
import top.mcocet.hlbot.promo.config.PgaSettings;
import top.mcocet.hlbot.promo.support.PlayerRoster;
import top.mcocet.hlbot.promo.support.Translator;
import top.mcocet.hlbot.plugins.BotApi;

/**
 * Everything a subcommand needs, plus the argument helpers that keep the subcommand bodies short.
 *
 * <p>The helpers follow one rule: they report the problem and return {@code null}, so a subcommand reads as
 * "parse, validate, act" with no nested conditionals. The original executors each re-implemented
 * "args.length < 2 -> print usage" about thirty times, with three different messages for the same mistake.
 */
public final class CommandContext {
   private final EmlPgaPlugin plugin;
   private final List<String> args;
   private final Output output;

   public CommandContext(final EmlPgaPlugin plugin, final List<String> args, final Output output) {
      this.plugin = plugin;
      this.args = args;
      this.output = output;
   }

   public EmlPgaPlugin plugin() {
      return this.plugin;
   }

   public BotApi api() {
      return this.plugin.api();
   }

   public PgaSettings settings() {
      return this.plugin.settings();
   }

   public PlayerRoster roster() {
      return this.plugin.roster();
   }

   public AdCampaign campaign() {
      return this.plugin.campaign();
   }

   public BurstSender burst() {
      return this.plugin.burst();
   }

   public Translator lang() {
      return this.plugin.lang();
   }

   public Output output() {
      return this.output;
   }

   /** The argument at {@code index}, or {@code null} when there is none. */
   public String arg(final int index) {
      return index >= 0 && index < this.args.size() ? this.args.get(index) : null;
   }

   /** Arguments from {@code from} onward joined with single spaces; empty when the index is past the end. */
   public String tail(final int from) {
      if (from >= this.args.size()) {
         return "";
      }

      return String.join(" ", this.args.subList(from, this.args.size())).trim();
   }

   /** Verifies the minimum argument count, reporting the usage line when it is not met. */
   public boolean requireArgs(final int minimum, final String usage) {
      if (this.args.size() >= minimum) {
         return true;
      }

      this.output.error(this.lang().get("cmd.usage", usage));
      return false;
   }

   /** A required integer of at least {@code minimum}; null when missing, unparsable or too small. */
   public Integer requireInt(final int index, final int minimum, final String usage) {
      String raw = this.arg(index);

      if (raw == null) {
         this.output.error(this.lang().get("cmd.usage", usage));
         return null;
      }

      try {
         int value = Integer.parseInt(raw.trim());

         if (value < minimum) {
            this.output.error(this.lang().get("cmd.number.range", minimum));
            return null;
         }

         return value;
      } catch (NumberFormatException notANumber) {
         this.output.error(this.lang().get("cmd.number.invalid", raw));
         return null;
      }
   }

   /**
    * A required on/off switch.
    *
    * <p>Accepts {@code on/off}, {@code true/false} and {@code enable/disable} because all three appear in the
    * original plugin's documentation and users carry the habit over.
    */
   public Boolean requireToggle(final int index, final String usage) {
      String raw = this.arg(index);

      if (raw == null) {
         this.output.error(this.lang().get("cmd.usage", usage));
         return null;
      }

      switch (raw.trim().toLowerCase(Locale.ROOT)) {
         case "on", "true", "enable", "enabled", "yes" -> {
            return Boolean.TRUE;
         }
         case "off", "false", "disable", "disabled", "no" -> {
            return Boolean.FALSE;
         }
         default -> {
            this.output.error(this.lang().get("cmd.toggle.invalid", raw));
            return null;
         }
      }
   }

   /** Reports the outcome of a setting change and persists it. */
   public void saved(final String messageKey, final Object... arguments) {
      this.settings().save();
      this.output.line(this.lang().get(messageKey, arguments));
   }
}
