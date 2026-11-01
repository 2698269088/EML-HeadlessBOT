package top.mcocet.hlbot.plugins.event;

import top.mcocet.hlbot.plugins.Plugin;

/**
 * One registered {@link EventHandler} method: the owning plugin, the listener instance,
 * its priority bucket, whether it ignores cancelled events, the reflective executor, and
 * the {@link HandlerList} it was registered into (needed for removal).
 */
public record RegisteredListener(Plugin plugin, Listener listener, EventPriority priority, boolean ignoreCancelled, EventExecutor executor, HandlerList handlerList) {
}
