package top.mcocet.hlbot.plugins.events.player;

import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the server opens a container (chest, furnace, villager trade, ...) and its
 * menu object has been created. Contents arrive afterwards as slot update packets; a
 * plugin that needs the full contents should react one tick later.
 */
public class ContainerOpenedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final AbstractContainerMenu menu;
   private final Component title;

   public ContainerOpenedEvent(final AbstractContainerMenu menu, final Component title) {
      this.menu = menu;
      this.title = title;
   }

   public AbstractContainerMenu getMenu() {
      return this.menu;
   }

   public Component getTitle() {
      return this.title;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
