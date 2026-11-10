package net.minecraft.client.headless;

import java.util.List;
import java.util.Locale;

/** Console access to the currently captured server dialog. */
public final class DialogCommand implements Commands.Command {
   private static final String USAGE = "dialog [info|help|set <key> <value...>|click <index|label|action>|cancel]";

   @Override
   public String name() {
      return "dialog";
   }

   @Override
   public String usage() {
      return USAGE;
   }

   @Override
   public String description() {
      return BotLang.get("cmd.dialog.desc");
   }

   @Override
   public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
      String operation = args.isEmpty() ? "info" : args.get(0).toLowerCase(Locale.ROOT);
      session.client().execute(() -> {
         try {
            switch (operation) {
               case "info", "show", "parse" -> info(io);
               case "set", "input" -> set(io, args);
               case "click", "press" -> click(io, args);
               case "cancel", "close" -> cancel(io);
               case "help", "?" -> help(io);
               default -> io.printError("[dialog] usage: " + USAGE);
            }
         } catch (IllegalArgumentException error) {
            io.printError("[dialog] " + error.getMessage());
         }
      });
   }

   private static void help(final ConsoleIO io) {
      io.printBlock(List.of(
         BotLang.get("dialog.help.header"),
         BotLang.get("dialog.help.info"),
         BotLang.get("dialog.help.set"),
         BotLang.get("dialog.help.click"),
         BotLang.get("dialog.help.cancel"),
         BotLang.get("dialog.help.example"),
         BotLang.get("dialog.help.help")
      ));
   }

   private static void info(final ConsoleIO io) {
      List<String> lines = eml.action.BotDialogs.INSTANCE.describe();
      if (lines.isEmpty()) {
         io.print("[dialog] none");
         return;
      }

      io.printBlock(lines);
   }

   private static void set(final ConsoleIO io, final List<String> args) {
      if (args.size() < 3) {
         io.printError("[dialog] usage: dialog set <key> <value...>");
         return;
      }

      String key = args.get(1);
      String value = String.join(" ", args.subList(2, args.size()));
      if ("\"\"".equals(value)) {
         value = "";
      }

      eml.action.BotDialogs.INSTANCE.setInput(key, value);
      io.print("[dialog] input '" + key + "' updated");
   }

   private static void click(final ConsoleIO io, final List<String> args) {
      if (args.size() < 2) {
         io.printError("[dialog] usage: dialog click <index|label|action>");
         return;
      }

      String target = String.join(" ", args.subList(1, args.size()));
      eml.action.BotDialogs.INSTANCE.click(target);
      io.print("[dialog] clicked '" + target + "'");
   }

   private static void cancel(final ConsoleIO io) {
      if (!eml.action.BotDialogs.INSTANCE.cancel()) {
         io.printError("[dialog] no cancellable dialog is open");
         return;
      }

      io.print("[dialog] cancelled");
   }
}
