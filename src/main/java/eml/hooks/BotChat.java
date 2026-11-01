package eml.hooks;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import top.mcocet.hlbot.plugins.BotPlugins;
import top.mcocet.hlbot.plugins.events.chat.ChatMessageEvent;

/** Chat fan-out shared by every patched chat entry point: plugin event, then console. */
public final class BotChat {
   private BotChat() {
   }

   /**
    * Fires {@link ChatMessageEvent} and prints the line unless a plugin cancelled it.
    *
    * @return true when a plugin cancelled the message (the donor then skips the vanilla bookkeeping
    *         that only existed to feed the GUI)
    */
   public static boolean handle(final Minecraft client, final boolean fromServer, final String sender, final Component content) {
      ChatMessageEvent event = BotPlugins.fire(new ChatMessageEvent(fromServer, sender, content));
      if (event.isCancelled()) {
         return true;
      }

      BotRuntime.onChatReceived(fromServer, sender, content);
      return false;
   }
}
