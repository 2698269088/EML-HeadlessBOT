package eml.template;

import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.gui.screens.multiplayer.ServerReconfigScreen;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Donor bodies for {@code ClientPacketListener} handlers that mix game state with the renderer or the GUI.
 *
 * <p>Both methods below are cases {@code StatementDrop} cannot fix, for two different reasons:
 * <ul>
 *   <li>{@code handleTakeItemEntity}: vanilla extracts an {@code EntityRenderState} through
 *       {@code Minecraft.getEntityRenderDispatcher()} (null headless) and <em>stores</em> it in a local before
 *       feeding a particle - "value is stored or returned ... needs a donor".</li>
 *   <li>{@code handleConfigurationStart}: vanilla reads {@code minecraft.gui.getChat().storeState()} into the
 *       listener cookie - the same stored-value shape, and {@code gui} is null headless.</li>
 * </ul>
 * Both are ported with the game-state work intact and the render/GUI work replaced by its headless
 * equivalent (nothing to render; a null chat state, which the cookie's consumer explicitly allows).
 */
public final class ClientPacketHandling {
   private ClientPacketHandling() {
   }

   /** Body of {@code ClientPacketListener.handleTakeItemEntity}. */
   public static void handleTakeItemEntity(final ClientPacketListener self, final ClientboundTakeItemEntityPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, self, self.minecraft.packetProcessor());
      Entity from = self.level.getEntity(packet.getItemId());

      if (from == null) {
         return;
      }

      // The pickup sound and the ItemPickupParticle are renderer/audio only (and the sound call is a no-op
      // headless anyway). What matters is the stack bookkeeping below.
      if (from instanceof ItemEntity itemEntity) {
         ItemStack itemStack = itemEntity.getItem();
         if (!itemStack.isEmpty()) {
            itemStack.shrink(packet.getAmount());
         }

         if (itemStack.isEmpty()) {
            self.level.removeEntity(packet.getItemId(), RemovalReason.DISCARDED);
         }
      } else if (!(from instanceof ExperienceOrb)) {
         self.level.removeEntity(packet.getItemId(), RemovalReason.DISCARDED);
      }
   }

   /**
    * Body of {@code ClientPacketListener.handleConfigurationStart}: identical to vanilla except that the
    * chat-screen state handed to the new listener is {@code null} instead of {@code minecraft.gui.getChat()}'s.
    * The receiving side checks it for null before restoring, so reconfiguration (server transfer, reload)
    * still completes instead of NPE-ing on the missing GUI.
    */
   public static void handleConfigurationStart(final ClientPacketListener self, final ClientboundStartConfigurationPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, self, self.minecraft.packetProcessor());
      self.minecraft.getChatListener().flushQueue();
      self.sendChatAcknowledgement();
      self.minecraft.clearClientLevel(new ServerReconfigScreen(ClientPacketListener.RECONFIGURE_SCREEN_MESSAGE, self.connection));
      self.connection.setupInboundProtocol(
         ConfigurationProtocols.CLIENTBOUND,
         new ClientConfigurationPacketListenerImpl(
            self.minecraft,
            self.connection,
            new CommonListenerCookie(
               new LevelLoadTracker(),
               self.localGameProfile,
               self.telemetryManager,
               self.registryAccess,
               self.enabledFeatures,
               self.serverBrand,
               self.serverData,
               self.postDisconnectScreen,
               self.serverCookies,
               null,
               self.customReportDetails,
               self.serverLinks(),
               self.seenPlayers,
               self.seenInsecureChatWarning
            )
         )
      );
      self.send(ServerboundConfigurationAcknowledgedPacket.INSTANCE);
      self.connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
   }
}
