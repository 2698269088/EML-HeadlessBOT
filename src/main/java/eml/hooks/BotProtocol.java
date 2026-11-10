package eml.hooks;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.ProtocolInfo;
import top.mcocet.hlbot.plugins.ProtocolBridge;

/**
 * Process-wide protocol bridge registry. Donor bodies in {@code eml.template.ProtocolBridgePatch} call
 * this class from inside {@code PacketDecoder}, {@code PacketEncoder} and {@code ClientIntentionPacket},
 * so every method here is public and side-effect light: with no bridge registered, the codec and the
 * handshake version pass through unchanged.
 */
public final class BotProtocol {
   private static final List<ProtocolBridge> BRIDGES = new CopyOnWriteArrayList<>();
   private static final Map<String, Integer> RESOLVED = new ConcurrentHashMap<>();

   private BotProtocol() {
   }

   public static void register(final ProtocolBridge bridge) {
      if (bridge != null) {
         BRIDGES.add(bridge);
      }
   }

   public static void unregister(final ProtocolBridge bridge) {
      if (bridge != null) {
         BRIDGES.remove(bridge);
      }
   }

   /** Records the protocol number a status ping reported for a server, keyed by host:port. */
   public static void noteResolved(final String host, final int port, final int protocol) {
      if (protocol > 0) {
         RESOLVED.put(key(host, port), protocol);
      }
   }

   /** The last protocol number a status ping reported, or null. */
   public static Integer resolved(final String host, final int port) {
      return RESOLVED.get(key(host, port));
   }

   /** Resolves the wire protocol to send in the handshake; unchanged when no bridge is registered. */
   public static int resolveProtocol(final String host, final int port, final int nativeVersion) {
      if (BRIDGES.isEmpty()) {
         return nativeVersion;
      }

      int current = RESOLVED.getOrDefault(key(host, port), nativeVersion);

      for (ProtocolBridge bridge : BRIDGES) {
         int candidate = bridge.protocolVersion(host, port, current);
         if (candidate > 0) {
            current = candidate;
         }
      }

      return current;
   }

   /** Wraps an inbound codec's protocol info; each registered bridge sees the previous result in order. */
   @SuppressWarnings({"rawtypes", "unchecked"})
   public static ProtocolInfo wrapInbound(final ProtocolInfo info) {
      ProtocolInfo current = info;

      for (ProtocolBridge bridge : BRIDGES) {
         current = bridge.wrapInbound(current);
      }

      return current;
   }

   /** Wraps an outbound codec's protocol info; each registered bridge sees the previous result in order. */
   @SuppressWarnings({"rawtypes", "unchecked"})
   public static ProtocolInfo wrapOutbound(final ProtocolInfo info) {
      ProtocolInfo current = info;

      for (ProtocolBridge bridge : BRIDGES) {
         current = bridge.wrapOutbound(current);
      }

      return current;
   }

   /** Called from the patched {@code Connection.configurePacketHandler} once the pipeline is assembled. */
   public static void onPipeline(final Connection connection, final Object pipeline) {
      if (!(pipeline instanceof ChannelPipeline channelPipeline)) {
         return;
      }

      for (ProtocolBridge bridge : BRIDGES) {
         bridge.onPipeline(connection, channelPipeline);
      }
   }

   private static String key(final String host, final int port) {
      return host.toLowerCase(Locale.ROOT) + ':' + port;
   }
}
