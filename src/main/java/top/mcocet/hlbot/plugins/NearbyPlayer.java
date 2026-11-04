package top.mcocet.hlbot.plugins;

/**
 * One player standing near the bot, flattened to plain values.
 *
 * <p>The bot's own {@link PlayerSnapshot} list is the tab list: it says who is online, not where. This record
 * is the spatial counterpart - the answer to "is somebody next to me right now", which is what a plugin that
 * reacts to players (greeting, emoting, retreating, trading) needs. Positions come from the entities the
 * client already tracks, so they are as fresh as the last movement packet.
 *
 * @param name     profile name
 * @param uuid     profile id, as text
 * @param x        feet position on the X axis
 * @param y        feet position on the Y axis
 * @param z        feet position on the Z axis
 * @param distance straight-line distance to the bot, in blocks
 * @param sneaking true while that player holds sneak
 */
public record NearbyPlayer(String name, String uuid, double x, double y, double z, double distance, boolean sneaking) {
}
