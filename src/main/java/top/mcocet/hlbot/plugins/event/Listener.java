package top.mcocet.hlbot.plugins.event;

/**
 * Marker interface for event listeners. Any class implementing it may declare methods
 * annotated with {@link EventHandler} and pass an instance to
 * {@code BotApi.registerEvents(listener, plugin)}.
 */
public interface Listener {
}
