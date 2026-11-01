package top.mcocet.hlbot.plugins.events.player;

import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the server closes the currently open container. Also fires on the client's
 * own {@code closeContainer()} since the server acknowledges it the same way.
 */
public class ContainerClosedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final int containerId;

   public ContainerClosedEvent(final int containerId) {
      this.containerId = containerId;
   }

   public int getContainerId() {
      return this.containerId;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
