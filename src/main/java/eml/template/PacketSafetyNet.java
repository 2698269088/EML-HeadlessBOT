package eml.template;

import net.minecraft.ReportedException;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import eml.hooks.BotResilience;

/**
 * Donor body for {@code PacketProcessor$ListenerAndPacket.handle} - the single point every inbound packet
 * passes through before vanilla decides to tear the connection down.
 *
 * <p>Vanilla's body is four statements and one catch:
 * <pre>
 *   if (listener.shouldHandleMessage(packet)) {
 *       try { packet.handle(listener); }
 *       catch (Exception e) {
 *           if (e instanceof ReportedException re &amp;&amp; re.getCause() instanceof OutOfMemoryError)
 *               throw PacketUtils.makeReportedException(e, packet, listener);
 *           listener.onPacketError(packet, e);      // &lt;- kick + disconnect report
 *       }
 *   }
 * </pre>
 * The only change is one call: {@link BotResilience#absorb} gets the first look at the exception and vetoes
 * the disconnect when the failure is a null renderer/audio/GUI object (see that class for why that cannot be
 * patched away site by site). {@code onPacketError} still runs for every other failure, so a real
 * game-state breakage keeps vanilla's behaviour of stopping a bot whose world model is no longer trustworthy.
 *
 * <p>Written as a donor rather than a wrapper because the catch block has to be <em>reshaped</em>: a
 * {@code Wrap} runs a hook around the whole method and never sees the exception. The copied body ends up
 * inside the record itself, which is what makes the private record fields and the private outer
 * {@code PacketProcessor.LOGGER} reachable, and what keeps the body free of any access widening.
 */
public final class PacketSafetyNet {
   private PacketSafetyNet() {
   }

   /**
    * Replaces {@code handle()}. The {@code self} parameter becomes {@code this} in the patched record, so the
    * raw type here is deliberate: erasure turns the two accesses into the same {@code GETFIELD}s vanilla
    * emitted, and {@code Packet.handle} into the same {@code INVOKEINTERFACE ...(Ljava/lang/Object;)V}.
    */
   @SuppressWarnings({"rawtypes", "unchecked"})
   public static void handle(final PacketProcessor.ListenerAndPacket self) {
      PacketListener listener = self.listener;
      Packet packet = self.packet;

      if (!listener.shouldHandleMessage(packet)) {
         return;
      }

      try {
         packet.handle(listener);
      } catch (Exception error) {
         if (error instanceof ReportedException reported && reported.getCause() instanceof OutOfMemoryError) {
            throw PacketUtils.makeReportedException(error, packet, listener);
         }

         // Absorbed => the packet is skipped and the connection survives; otherwise vanilla's disconnect.
         if (!BotResilience.absorb(packet, listener, error)) {
            listener.onPacketError(packet, error);
         }
      }
   }
}
