package top.mcocet.hlbot.plugins.events.dialog;

import eml.action.BotDialogs;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/** Fired when a dialog closes, is replaced, or its connection disappears. */
public final class DialogClosedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();
   private final BotDialogs.Snapshot snapshot;
   private final String reason;

   public DialogClosedEvent(final BotDialogs.Snapshot snapshot, final String reason) {
      this.snapshot = snapshot;
      this.reason = reason;
   }

   public BotDialogs.Snapshot getSnapshot() {
      return this.snapshot;
   }

   public String getReason() {
      return this.reason;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
