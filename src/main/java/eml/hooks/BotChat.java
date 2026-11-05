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
      return handle(client, fromServer, sender, content, fromServer ? ChatMessageEvent.Kind.SYSTEM : ChatMessageEvent.Kind.PUBLIC, null);
   }

   /**
    * Same fan-out with the extra context a plugin needs to tell "who said this" apart from "how the console
    * prefixes it": the vanilla path the line arrived on and, for signed/disguised player chat, the sender's
    * name.
    */
   public static boolean handle(final Minecraft client, final boolean fromServer, final String sender, final Component content,
                                final ChatMessageEvent.Kind kind, final String senderName) {
      ChatMessageEvent event = BotPlugins.fire(new ChatMessageEvent(fromServer, sender, content, kind, senderName));
      if (event.isCancelled()) {
         return true;
      }

      BotRuntime.onChatReceived(fromServer, senderName == null || senderName.isBlank() ? sender : senderName, content);
      return false;
   }
}
