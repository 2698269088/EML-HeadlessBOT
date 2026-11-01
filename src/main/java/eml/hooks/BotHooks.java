package eml.hooks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundGameRuleValuesPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.util.Util;
import top.mcocet.hlbot.plugins.BotPlugins;
import top.mcocet.hlbot.plugins.events.player.PlayerDeathEvent;

/** Static targets for the {@code Wrap} rules: one method per patched vanilla entry point. */
public final class BotHooks {
   private BotHooks() {
   }

   /**
    * Runs BEFORE {@code ClientPacketListener.handlePlayerCombatKill}.
    *
    * <p>The vanilla body still decides what happens: with the death screen disabled server-side it
    * respawns the player inline (nothing to do here), otherwise it calls {@code setScreen}, which the
    * headless donor turns into an inert assignment. So auto-respawn is only scheduled when the server
    * actually asked for a death screen.
    */
   public static void onCombatKill(final ClientPacketListener self, final ClientboundPlayerCombatKillPacket packet) {
      Minecraft client = Minecraft.getInstance();
      LocalPlayer player = client.player;
      if (player == null || packet.playerId() != player.getId()) {
         return;
      }

      BotPlugins.fire(new PlayerDeathEvent(packet.message()));

      if (!player.shouldShowDeathScreen()) {
         return; // vanilla handlePlayerCombatKill respawns synchronously in this case
      }

      net.minecraft.client.headless.BotConfig config = net.minecraft.client.headless.BotConfig.instance();
      if (config != null && config.autoRespawn()) {
         BotRuntime.log().info(net.minecraft.client.headless.BotLang.get("bot.died_auto", packet.message().getString()));
         BotRuntime.scheduleRespawn(20);
      } else {
         BotRuntime.log().info(net.minecraft.client.headless.BotLang.get("bot.died", packet.message().getString()));
      }
   }

   /**
    * Runs on {@code ClientPacketListener.handlePongResponse}: the server echoes our request timestamp,
    * so the difference is the round-trip time. Samples from vanilla's own probes are equally valid.
    */
   public static void onPongResponse(final ClientPacketListener self, final ClientboundPongResponsePacket packet) {
      BotRuntime.recordPingMs(Util.getMillis() - packet.time());
   }

   // ==================== plugin events ====================
   //
   // Each hook below is wired by eml.agent.EmlPatches to the named vanilla handler. They stay cheap: 
   // BotPlugins.fire() returns immediately when nobody listens, so an unused event costs one static call per
   // packet. Subjects that a packet only references by entity id are resolved through the level, and are
   // skipped rather than announced as null. Events whose subject cannot be obtained without guessing at an
   // unverified accessor are deliberately NOT wired (see the list in EmlPatches#events).

   /** Wired AFTER {@code handleAddEntity}: the entity has to exist before it can be announced. */
   public static void onAddEntity(final ClientPacketListener self, final ClientboundAddEntityPacket packet) {
      net.minecraft.world.entity.Entity entity = entityOf(self, packet.getId());

      if (entity != null) {
         top.mcocet.hlbot.plugins.BotPlugins.fire(new top.mcocet.hlbot.plugins.events.entity.EntityJoinLevelEvent(entity));
      }
   }

   /** Runs BEFORE {@code handleRemoveEntities}, while the entities can still be resolved. */
   public static void onRemoveEntities(final ClientPacketListener self, final ClientboundRemoveEntitiesPacket packet) {
      // The packet's list is a fastutil IntList; binding it to the List interface here is deliberate, since
      // fastutil is on the runtime classpath but is not a declared compile dependency.
      java.util.List<Integer> ids = packet.getEntityIds();

      for (Integer id : ids) {
         net.minecraft.world.entity.Entity entity = entityOf(self, id);

         if (entity != null) {
            top.mcocet.hlbot.plugins.BotPlugins.fire(new top.mcocet.hlbot.plugins.events.entity.EntityLeaveLevelEvent(entity));
         }
      }
   }

   /** Runs BEFORE {@code handlePlayerInfoRemove}, while the entry is still in the player list. */
   public static void onPlayerInfoRemove(final ClientPacketListener self, final ClientboundPlayerInfoRemovePacket packet) {
      for (java.util.UUID id : packet.profileIds()) {
         net.minecraft.client.multiplayer.PlayerInfo info = self.getPlayerInfo(id);

         if (info != null) {
            top.mcocet.hlbot.plugins.BotPlugins.fire(new top.mcocet.hlbot.plugins.events.player.PlayerLeftEvent(info));
         }
      }
   }

   public static void onBlockUpdate(final ClientPacketListener self, final ClientboundBlockUpdatePacket packet) {
      top.mcocet.hlbot.plugins.BotPlugins.fire(
         new top.mcocet.hlbot.plugins.events.level.BlockUpdatedEvent(packet.getPos(), packet.getBlockState()));
   }

   public static void onLevelChunkWithLight(final ClientPacketListener self, final ClientboundLevelChunkWithLightPacket packet) {
      top.mcocet.hlbot.plugins.BotPlugins.fire(new top.mcocet.hlbot.plugins.events.level.ChunkLoadedEvent(packet.getX(), packet.getZ()));
   }

   public static void onForgetLevelChunk(final ClientPacketListener self, final ClientboundForgetLevelChunkPacket packet) {
      top.mcocet.hlbot.plugins.BotPlugins.fire(
         new top.mcocet.hlbot.plugins.events.level.ChunkUnloadedEvent(packet.pos().x(), packet.pos().z()));
   }

   public static void onExplosion(final ClientPacketListener self, final ClientboundExplodePacket packet) {
      top.mcocet.hlbot.plugins.BotPlugins.fire(new top.mcocet.hlbot.plugins.events.level.ExplosionEvent(packet.center(), packet.radius()));
   }

   public static void onSetHealth(final ClientPacketListener self, final ClientboundSetHealthPacket packet) {
      top.mcocet.hlbot.plugins.BotPlugins.fire(
         new top.mcocet.hlbot.plugins.events.player.HealthChangedEvent(packet.getHealth(), packet.getFood(), packet.getSaturation()));
   }

   public static void onSetExperience(final ClientPacketListener self, final ClientboundSetExperiencePacket packet) {
      top.mcocet.hlbot.plugins.BotPlugins.fire(
         new top.mcocet.hlbot.plugins.events.player.ExperienceChangedEvent(
            packet.getExperienceProgress(), packet.getTotalExperience(), packet.getExperienceLevel()));
   }

   public static void onSetHeldSlot(final ClientPacketListener self, final ClientboundSetHeldSlotPacket packet) {
      top.mcocet.hlbot.plugins.BotPlugins.fire(new top.mcocet.hlbot.plugins.events.player.HeldItemChangedEvent(packet.slot()));
   }

   public static void onContainerClose(final ClientPacketListener self, final ClientboundContainerClosePacket packet) {
      top.mcocet.hlbot.plugins.BotPlugins.fire(
         new top.mcocet.hlbot.plugins.events.player.ContainerClosedEvent(packet.getContainerId()));
   }

   public static void onUpdateRecipes(final ClientPacketListener self, final ClientboundUpdateRecipesPacket packet) {
      top.mcocet.hlbot.plugins.BotPlugins.fire(new top.mcocet.hlbot.plugins.events.recipe.RecipesUpdatedEvent(
         packet.itemSets().size(), packet.stonecutterRecipes().entries().size()));
   }

   public static void onGameRuleValues(final ClientPacketListener self, final ClientboundGameRuleValuesPacket packet) {
      top.mcocet.hlbot.plugins.BotPlugins.fire(new top.mcocet.hlbot.plugins.events.server.GameRuleUpdateEvent(packet.values()));
   }

   /**
    * Observation hook for {@code handleBossUpdate}. The GUI overlay statement inside that handler was removed
    * by a StatementDrop, which has to be registered FIRST: once this rule renames the body, the drop would
    * look at the dispatcher and find nothing to remove.
    */
   public static void onBossUpdate(final ClientPacketListener self, final ClientboundBossEventPacket packet) {
      top.mcocet.hlbot.plugins.BotPlugins.fire(new top.mcocet.hlbot.plugins.events.server.BossBarEvent(packet));
   }

   public static void onRespawn(final ClientPacketListener self, final ClientboundRespawnPacket packet) {
      top.mcocet.hlbot.plugins.BotPlugins.fire(new top.mcocet.hlbot.plugins.events.connection.RespawnEvent(
         packet.commonPlayerSpawnInfo().dimension(),
         packet.shouldKeep(ClientboundRespawnPacket.KEEP_ALL_DATA)));
   }

   private static net.minecraft.world.entity.Entity entityOf(final ClientPacketListener self, final int id) {
      net.minecraft.world.level.Level level = self.getLevel();
      return level == null ? null : level.getEntity(id);
   }
}
