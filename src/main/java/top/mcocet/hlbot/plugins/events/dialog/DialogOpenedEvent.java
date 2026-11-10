package top.mcocet.hlbot.plugins.events.dialog;

import eml.action.BotDialogs;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/** Fired after a server dialog has been captured on the client thread. */
public final class DialogOpenedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();
   private final BotDialogs.Snapshot snapshot;

   public DialogOpenedEvent(final BotDialogs.Snapshot snapshot) {
      this.snapshot = snapshot;
   }

   public BotDialogs.Snapshot getSnapshot() {
      return this.snapshot;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
