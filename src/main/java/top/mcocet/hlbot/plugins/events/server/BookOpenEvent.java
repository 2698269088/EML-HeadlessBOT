package top.mcocet.hlbot.plugins.events.server;

import net.minecraft.world.item.ItemStack;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the server asks the client to show a written book (lectern UIs, rule
 * books, quest text). The full item stack, NBT pages included, is provided.
 */
public class BookOpenEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final ItemStack book;

   public BookOpenEvent(final ItemStack book) {
      this.book = book;
   }

   public ItemStack getBook() {
      return this.book;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
