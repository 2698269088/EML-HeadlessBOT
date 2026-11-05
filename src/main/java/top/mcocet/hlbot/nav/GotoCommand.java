package top.mcocet.hlbot.nav;

import java.util.List;
import top.mcocet.hlbot.plugins.BotCommand;

/**
 * {@code goto} - the console entry point of the built-in navigator.
 *
 * <p>Written against {@link BotCommand} rather than the console's own {@code Command} interface, so this
 * package never imports {@code net.minecraft}: the command only hands its tokens to the navigator, and the
 * console adapter that registers it resolves the description key and prints the reply.
 *
 * <p>Shapes: two coordinates walk to the surface of that column, three walk to a cell, {@code fly} glides with
 * the elytra, {@code ride} uses the nearest boat/minecart/horse, {@code smart} picks the means by distance
 * (elytra for a long trip, a vehicle for a medium one, walking otherwise, each falling back to the next),
 * {@code stop} and {@code status} control and inspect the trip. {@code ~}/{@code ~n} follow the vanilla
 * convention, {@code nodig} plans around blocks instead of breaking them.
 */
public final class GotoCommand implements BotCommand {
   /** One line in {@code help}; the whole grammar lives behind {@code goto help} and in {@link #FULL_USAGE}. */
   public static final String USAGE = "goto <x> <y> <z> | goto help";

   /** Everything {@code goto} accepts: printed by a malformed request. */
   public static final String FULL_USAGE = "goto <x> <y> <z> | goto <x> <z> | goto fly <x> <y> <z>"
      + " | goto ride <x> <z> | goto smart <x> <y> <z> | goto stop | goto status [nodig]";

   /** One lang key per subcommand, each holding its usage and one sentence: what {@code goto help} lists. */
   public static final String[] HELP_KEYS = {
      "goto.help.walk", "goto.help.fly", "goto.help.ride", "goto.help.smart",
      "goto.help.nodig", "goto.help.stop", "goto.help.status", "goto.help.help"
   };

   @Override
   public String name() {
      return "goto";
   }

   @Override
   public String usage() {
      return USAGE;
   }

   /** A {@code .lang} key; the console adapter localises command descriptions. */
   @Override
   public String description() {
      return "cmd.goto.desc";
   }

   @Override
   public void execute(final List<String> args) {
      Navigator.INSTANCE.enqueueCommand(args);
   }
}
