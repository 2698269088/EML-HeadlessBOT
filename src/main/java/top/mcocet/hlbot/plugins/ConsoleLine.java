package top.mcocet.hlbot.plugins;

/**
 * One captured console line, as plain data so a plugin can render it without importing anything from
 * {@code net.minecraft} or log4j.
 *
 * @param seq        monotonic sequence number; poll with the last value you saw to get only new lines
 * @param timeMillis wall-clock time the event was logged
 * @param level      log level name ({@code INFO}, {@code WARN}, ...)
 * @param logger     logger name, which identifies the subsystem that wrote the line
 * @param message    the formatted message
 * @param error      class name of the throwable that came with the line, or {@code null}
 */
public record ConsoleLine(int seq, long timeMillis, String level, String logger, String message, String error) {
}
