package top.mcocet.hlbot.plugins;

import java.util.List;

/**
 * A console command written by a plugin.
 *
 * <p>This is the plugin-facing shape of {@code net.minecraft.client.headless.Commands.Command}: the same
 * name/usage/description contract, but the signature carries no game types at all. Output goes through
 * {@link BotApi#print}/{@link BotApi#printError}, and {@link BotApi#registerCommand(BotCommand, Plugin)}
 * adapts it for the console (keeping the fault isolation of {@link PluginManager}).
 */
public interface BotCommand {
   String name();

   String usage();

   String description();

   void execute(List<String> args);
}
