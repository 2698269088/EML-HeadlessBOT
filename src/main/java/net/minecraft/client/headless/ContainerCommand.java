package net.minecraft.client.headless;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.item.ItemStack;

/** Console operations for the currently open server-controlled container menu. */
public final class ContainerCommand implements Commands.Command {
   private static final String USAGE = "container <info|left|right|move|shift|drop|swap|offhand|collect|button|close>";

   @Override
   public String name() {
      return "container";
   }

   @Override
   public String usage() {
      return USAGE + " | container help";
   }

   @Override
   public String description() {
      return BotLang.get("cmd.container.desc");
   }

   @Override
   public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
      String operation = args.isEmpty() ? "info" : args.get(0).toLowerCase(Locale.ROOT);
      if ("help".equals(operation) || "?".equals(operation)) {
         help(io);
         return;
      }

      session.client().execute(() -> {
         eml.action.BotInventory inventory = new eml.action.BotInventory(session::clientNow);
         if (!inventory.isOpen()) {
            io.printError(BotLang.get("container.not_open"));
            return;
         }

         try {
            switch (operation) {
               case "info", "show", "list" -> info(io, inventory);
               case "left", "l" -> result(io, operation, inventory.leftClick(intArg(args, 1)));
               case "right", "r" -> result(io, operation, inventory.rightClick(intArg(args, 1)));
               case "move", "mv" -> result(io, operation, inventory.move(intArg(args, 1), intArg(args, 2)));
               case "shift", "quick" -> result(io, operation, inventory.shiftClick(intArg(args, 1)));
               case "drop" -> result(io, operation, inventory.drop(intArg(args, 1),
                  args.size() > 2 && "all".equalsIgnoreCase(args.get(2))));
               case "swap" -> {
                  int hotbar = intArg(args, 2);
                  if (hotbar < 0 || hotbar > 8) {
                     throw new IllegalArgumentException(BotLang.get("bot.usage", "container swap <slot> <hotbar 0-8>"));
                  }
                  result(io, operation, inventory.swapWithHotbar(intArg(args, 1), hotbar));
               }
               case "offhand", "f" -> result(io, operation, inventory.swapWithOffhand(intArg(args, 1)));
               case "collect", "double" -> result(io, operation, inventory.collect(intArg(args, 1)));
               case "button" -> result(io, operation, inventory.button(intArg(args, 1)));
               case "dropcarried" -> result(io, operation, inventory.dropCarried());
               case "close" -> result(io, operation, inventory.close());
               default -> io.printError(BotLang.get("bot.usage", this.usage()));
            }
         } catch (IllegalArgumentException error) {
            io.printError(error.getMessage());
         }
      });
   }

   private static void info(final ConsoleIO io, final eml.action.BotInventory inventory) {
      List<String> lines = new ArrayList<>();
      lines.add("containerId=" + inventory.containerId() + ", stateId=" + inventory.stateId()
         + ", containerSlots=" + inventory.containerSlotCount() + ", menuSlots=" + inventory.slotCount());
      ItemStack carried = inventory.carried();
      lines.add("carried: " + (carried.isEmpty() ? "-" : carried.getDisplayName().getString() + " x" + carried.getCount()));
      List<ItemStack> contents = inventory.contents();
      for (int slot = 0; slot < contents.size(); slot++) {
         ItemStack stack = contents.get(slot);
         if (!stack.isEmpty()) {
            lines.add("  [" + slot + "] " + stack.getDisplayName().getString() + " x" + stack.getCount());
         }
      }

      io.printBlock(lines);
   }

   private static void result(final ConsoleIO io, final String operation, final boolean success) {
      if (success) {
         io.print("[container] " + operation + " ok");
      } else {
         io.printError(BotLang.get("container.failed", operation));
      }
   }

   private static int intArg(final List<String> args, final int index) {
      if (args.size() <= index) {
         throw new IllegalArgumentException(BotLang.get("bot.usage", USAGE));
      }

      try {
         return Integer.parseInt(args.get(index));
      } catch (NumberFormatException error) {
         throw new IllegalArgumentException(BotLang.get("bot.usage", USAGE));
      }
   }

   private static void help(final ConsoleIO io) {
      io.printBlock(List.of(
         BotLang.get("container.help.usage"),
         BotLang.get("container.help.click"),
         BotLang.get("container.help.move"),
         BotLang.get("container.help.other"),
         BotLang.get("container.help.note")
      ));
   }
}
