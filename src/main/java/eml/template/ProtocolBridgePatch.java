package eml.template;

import eml.hooks.BotProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;

/**
 * Donor bodies for the three constructor sites that fix the protocol in place. The bodies run inside the
 * target classes themselves, so the private fields below are legal after the copy.
 */
public final class ProtocolBridgePatch {
   private ProtocolBridgePatch() {
   }

   public static void initDecoder(final PacketDecoder self, final ProtocolInfo protocolInfo) {
      self.protocolInfo = BotProtocol.wrapInbound(protocolInfo);
   }

   public static void initEncoder(final PacketEncoder self, final ProtocolInfo protocolInfo) {
      self.protocolInfo = BotProtocol.wrapOutbound(protocolInfo);
   }

   public static void initIntention(final ClientIntentionPacket self, final int protocolVersion, final String hostName, final int port, final ClientIntent intention) {
      self.protocolVersion = BotProtocol.resolveProtocol(hostName, port, protocolVersion);
      self.hostName = hostName;
      self.port = port;
      self.intention = intention;
   }
}
