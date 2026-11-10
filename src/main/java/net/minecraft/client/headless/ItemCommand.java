package net.minecraft.client.headless;

import java.util.List;
import java.util.Locale;

/** Selects an inventory stack and performs one normal left/right click with it. */
public final class ItemCommand implements Commands.Command {
   private static final String USAGE = "item <0-35> <left|right>";

   @Override
   public String name() {
      return "item";
   }

   @Override
   public String usage() {
      return USAGE + " | item help";
   }

   @Override
   public String description() {
      return BotLang.get("cmd.item.desc");
   }

   @Override
   public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
      if (args.size() == 1 && ("help".equalsIgnoreCase(args.get(0)) || "?".equals(args.get(0)))) {
         io.printBlock(List.of(BotLang.get("item.help.usage"), BotLang.get("item.help.slots")));
         return;
      }
      if (args.size() != 2) {
         io.printError(BotLang.get("bot.usage", this.usage()));
         return;
      }

      final int slot;
      try {
         slot = Integer.parseInt(args.get(0));
      } catch (NumberFormatException error) {
         io.printError(BotLang.get("bot.usage", this.usage()));
         return;
      }

      String action = args.get(1).toLowerCase(Locale.ROOT);
      boolean left = switch (action) {
         case "left", "l", "attack" -> true;
         case "right", "r", "use" -> false;
         default -> throw new IllegalArgumentException(BotLang.get("bot.usage", this.usage()));
      };

      session.client().execute(() -> {
         if (session.client().player == null || session.client().getConnection() == null) {
            io.printError(BotLang.get("bot.not_connected"));
            return;
         }

         eml.action.BotControls controls = new eml.action.BotControls(session::clientNow);
         if (!controls.selectInventorySlot(slot)) {
            io.printError(BotLang.get("bot.usage", this.usage()));
            return;
         }

         boolean performed = left ? eml.action.BotActions.startAttack(session.client()) : controls.useItem();
         if (performed) {
            io.print("[item] slot " + slot + " " + (left ? "left" : "right"));
         } else {
            io.printError(BotLang.get("bot.action_failed", "item " + (left ? "left" : "right"), "no target"));
         }
      });
   }
}
