package top.mcocet.hlbot.plugins.event;

/**
 * Base type for all bot events, mirroring the Bukkit event contract: every concrete event
 * class must expose a {@code public static HandlerList getHandlerList()} holding its
 * listeners, and implement {@link #getHandlers()} returning that same list.
 *
 * <p>Events are fired synchronously on the thread that produced them (usually the client
 * game thread). Listeners that touch the level, the player or the connection are therefore
 * already on the right thread; long-running work must be moved to a separate thread by the
 * plugin itself.
 */
public abstract class Event {
   public abstract HandlerList getHandlers();
}
