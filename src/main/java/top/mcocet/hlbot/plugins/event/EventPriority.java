package top.mcocet.hlbot.plugins.event;

/**
 * Handler execution order, lowest first. {@link #MONITOR} handlers run last and should
 * only observe, never mutate the event outcome.
 */
public enum EventPriority {
   LOWEST,
   LOW,
   NORMAL,
   HIGH,
   HIGHEST,
   MONITOR
}
