package eml.template;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action;

/**
 * Donor bodies for {@code ClientCommonPacketListenerImpl}: resource-pack negotiation without a GUI and
 * without a resource reload.
 *
 * <p>Why vanilla cannot be kept: {@code handleResourcePackPush} either pushes the pack into
 * {@code DownloadedPackSource} (which downloads and then asks {@code Minecraft} to reload its resources -
 * a renderer-bound pipeline that does not exist headless) or opens a confirmation screen whose buttons only
 * a player can click. In both cases the server waits for a {@code ServerboundResourcePackPacket} that never
 * arrives, so a server with {@code require-resource-pack} kicks the bot.
 *
 * <p>The answer here is the honest one for a client that never applies pack contents: accept immediately
 * and report {@code SUCCESSFULLY_LOADED} (the terminal status the server waits for). A pack that was
 * already answered on this connection skips {@code ACCEPTED}. {@code DECLINED} is only used for optional
 * packs when the policy says so, since declining a required pack disconnects the player.
 */
public final class ResourcePackHandling {
   private ResourcePackHandling() {
   }

   /** Body of {@code ClientCommonPacketListenerImpl.handleResourcePackPush}. */
   public static void handlePush(final ClientCommonPacketListenerImpl self, final ClientboundResourcePackPushPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, self, self.minecraft.packetProcessor());
      UUID id = packet.id();

      // Validated inline: a copied donor body runs inside the target class, so it may only call PUBLIC
      // members of other classes - a private helper here would fail with IllegalAccessError at runtime.
      boolean validUrl;
      try {
         String protocol = java.net.URI.create(packet.url()).toURL().getProtocol();
         validUrl = "http".equals(protocol) || "https".equals(protocol);
      } catch (RuntimeException | java.net.MalformedURLException e) {
         validUrl = false;
      }

      if (!validUrl) {
         self.send(new ServerboundResourcePackPacket(id, Action.INVALID_URL));
         return;
      }

      if (eml.hooks.BotResourcePacks.policy() == eml.hooks.BotResourcePacks.Policy.DECLINE && !packet.required()) {
         self.send(new ServerboundResourcePackPacket(id, Action.DECLINED));
         return;
      }

      if (!eml.hooks.BotResourcePacks.markSeen(id)) {
         self.send(new ServerboundResourcePackPacket(id, Action.ACCEPTED));
      }

      self.send(new ServerboundResourcePackPacket(id, Action.SUCCESSFULLY_LOADED));
   }

   /** Body of {@code ClientCommonPacketListenerImpl.handleResourcePackPop}: nothing was applied, nothing unloads. */
   public static void handlePop(final ClientCommonPacketListenerImpl self, final ClientboundResourcePackPopPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, self, self.minecraft.packetProcessor());
      Optional<UUID> id = packet.id();
      if (id.isPresent()) {
         eml.hooks.BotResourcePacks.forget(id.get());
      } else {
         eml.hooks.BotResourcePacks.clear();
      }
   }
}
