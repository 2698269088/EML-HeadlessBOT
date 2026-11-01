package top.mcocet.hlbot.plugins.event;

/**
 * Marks an event whose default outcome can be suppressed. For example, cancelling
 * {@code ChatMessageEvent} stops the message from being printed to the console.
 */
public interface Cancellable {
   boolean isCancelled();

   void setCancelled(boolean cancelled);
}
