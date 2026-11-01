package top.mcocet.hlbot.plugins;

/**
 * One entry of the server's player list, flattened to plain values.
 *
 * <p>Deliberately not {@code PlayerInfo}: a plugin that wants to show who is online should not have to
 * compile against the Minecraft runtime, and a snapshot decouples the reading thread from the client thread
 * that owns the real list.
 *
 * @param name      profile name
 * @param uuid      profile id, as text
 * @param latencyMs the ping the server reports for this player, in milliseconds
 * @param gameMode  game mode name, or {@code "unknown"} when the server has not sent one yet
 * @param self      true for the bot's own entry
 */
public record PlayerSnapshot(String name, String uuid, int latencyMs, String gameMode, boolean self) {
}
