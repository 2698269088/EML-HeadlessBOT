package top.mcocet.hlbot.plugins;

/**
 * A point-in-time view of the process and the session, flattened to plain values.
 *
 * <p>Every field is something a monitoring page needs and none of them is a game object, so a plugin can
 * poll this from an HTTP worker thread without touching {@code net.minecraft} (which is why the record
 * lives in the plugin API package rather than next to the gatherer).
 *
 * @param heapUsed      live-plus-garbage heap in bytes, the number a JVM reports as "used"
 * @param heapCommitted heap committed to the OS in bytes
 * @param heapMax       maximum heap in bytes
 * @param nonHeapUsed   metaspace and friends in bytes
 * @param threads       live thread count
 * @param processCpu    process CPU load 0..1, or {@code -1} when the JVM cannot report it
 * @param systemCpu     whole-machine CPU load 0..1, or {@code -1} when unknown
 * @param uptimeMillis  milliseconds since the bot process started
 * @param tickCount     client ticks since startup, the bot's own clock
 * @param pingMs        last measured round-trip time, or {@code -1} before the first pong
 * @param loadedClasses classes the JVM has loaded
 * @param skippedPackets inbound packets the headless guard skipped (see {@code BotResilience})
 */
public record BotMetrics(
   long heapUsed,
   long heapCommitted,
   long heapMax,
   long nonHeapUsed,
   int threads,
   double processCpu,
   double systemCpu,
   long uptimeMillis,
   long tickCount,
   long pingMs,
   int loadedClasses,
   long skippedPackets
) {
}
