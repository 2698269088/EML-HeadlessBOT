package top.mcocet.hlbot.plugins;

import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.ProtocolInfo;

/**
 * Plugin-side hook for rewiring the connection protocol. A plugin registers one bridge in
 * {@link Plugin#onLoad()}; it stays active across disconnects and is withdrawn when the plugin unloads.
 *
 * <p>These methods are called from the connection path (Netty event loop), not from the game tick: keep
 * them free of game-state access and of blocking work. The raw {@code ProtocolInfo} type is deliberate -
 * a translated codec deals with {@code Packet<?>} after erasure.
 */
public interface ProtocolBridge {
   /**
    * The protocol number to present to a server. {@code nativeVersion} is the client's own protocol, or a
    * status-ping result when one was obtained for this host. Return {@code nativeVersion} to leave it
    * unchanged, or a positive number to override it.
    */
   int protocolVersion(String host, int port, int nativeVersion);

   /** Returns the protocol info to decode inbound packets with; the identity is a valid implementation. */
   ProtocolInfo wrapInbound(ProtocolInfo protocolInfo);

   /** Returns the protocol info to encode outbound packets with; the identity is a valid implementation. */
   ProtocolInfo wrapOutbound(ProtocolInfo protocolInfo);

   /**
    * Called after the vanilla Netty pipeline for a connection has been configured, before the channel is
    * registered. A bridge may insert its own handlers here; the default is a no-op.
    */
   default void onPipeline(Connection connection, ChannelPipeline pipeline) {
   }
}
