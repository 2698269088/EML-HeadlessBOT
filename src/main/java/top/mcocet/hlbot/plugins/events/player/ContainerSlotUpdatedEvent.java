package top.mcocet.hlbot.plugins.events.player;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/** Fired after a container slot changes, including each populated slot in an initial content packet. */
public class ContainerSlotUpdatedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final AbstractContainerMenu menu;
   private final int slot;
   private final ItemStack item;

   public ContainerSlotUpdatedEvent(final AbstractContainerMenu menu, final int slot, final ItemStack item) {
      this.menu = menu;
      this.slot = slot;
      this.item = item;
   }

   public AbstractContainerMenu getMenu() {
      return this.menu;
   }

   public int getSlot() {
      return this.slot;
   }

   public ItemStack getItem() {
      return this.item;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
