package eml.agent;

import java.util.ArrayList;
import java.util.List;
import eml.engine.Rules;
import eml.engine.Rules.BodyReplace;
import eml.engine.Rules.CallDrop;
import eml.engine.Rules.Wrap;
import eml.template.AmbientSoundHandling;
import eml.template.ChatListenerRouting;
import eml.template.ClientLevelEffects;
import eml.template.ClientLoginHandling;
import eml.template.ClientPacketHandling;
import eml.template.LevelLoadReadiness;
import eml.template.MinecraftLifecycle;
import eml.template.PacketSafetyNet;
import eml.template.RenderCoupling;
import eml.template.ResourcePackHandling;

/**
 * The headless patch inventory.
 *
 * <p>Organised in two layers so the risky part can be validated first:
 * <ol>
 *   <li><b>lifecycle</b> - makes the client boot and log in without a GPU: constructor, main loop,
 *       tick, level switching, screen no-ops, chat routing.</li>
 *   <li><b>events</b> - the plugin-facing wrappers (death, entity, chunk, health, ...). Enabled by
 *       default, but each is a thin observation wrapper that cannot break the vanilla path.</li>
 * </ol>
 *
 * <p>Every entry here is a hard requirement: {@code Donor}/{@code CallDrop}/{@code Wrap} throw when
 * the expected member is missing, so a Minecraft update that renames or reshapes something fails at
 * launch with the exact rule name instead of misbehaving silently.
 */
public final class EmlPatches {
   private static final String MINECRAFT = "net/minecraft/client/Minecraft";
   private static final String CHAT_LISTENER = "net/minecraft/client/multiplayer/chat/ChatListener";
   private static final String PACKET_LISTENER = "net/minecraft/client/multiplayer/ClientPacketListener";
   private static final String COMMON_LISTENER = "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl";
   private static final String OPTIONS = "net/minecraft/client/Options";
   private static final String OPTION_RANGE = "net/minecraft/client/OptionInstance$ClampingLazyMaxIntRange";
   private static final String DEBUG_OVERLAY = "net/minecraft/client/gui/components/DebugScreenOverlay";
   private static final String CHUNK_CACHE = "net/minecraft/client/multiplayer/ClientChunkCache";
   private static final String CONNECTION = "net/minecraft/network/Connection";
   private static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
   private static final String LOCAL_PLAYER = "net/minecraft/client/player/LocalPlayer";
   private static final String GAME_MODE = "net/minecraft/client/multiplayer/MultiPlayerGameMode";
   private static final String ADVANCEMENTS = "net/minecraft/client/multiplayer/ClientAdvancements";
   private static final String WAITING_FOR_CHUNK = "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForPlayerChunk";
   /**
    * The record every inbound packet is dispatched through. It is package-private in vanilla, which is fine:
    * the donor body is copied into it, so it runs with that class's own access.
    */
   private static final String PACKET_ENTRY = "net/minecraft/network/PacketProcessor$ListenerAndPacket";
   private static final String UNDERWATER_AMBIENCE = "net/minecraft/client/resources/sounds/UnderwaterAmbientSoundHandler";
   private static final String BIOME_AMBIENCE = "net/minecraft/client/resources/sounds/BiomeAmbientSoundsHandler";

   private EmlPatches() {
   }

   public static List<Rules.Patch> register() {
      List<Rules.Patch> patches = new ArrayList<>();
      lifecycle(patches);
      chat(patches);
      resourcePacks(patches);
      renderCoupling(patches);
      events(patches);
      diagnostics(patches);
      resilience(patches);
      pendingCallDrops(patches);
      return patches;
   }

   /** Distinct patched classes, in registration order (used by the offline patch pre-flight check). */
   public static List<String> owners() {
      List<String> owners = new ArrayList<>();

      for (Rules.Patch patch : register()) {
         if (!owners.contains(patch.owner)) {
            owners.add(patch.owner);
         }
      }

      return owners;
   }

   /** Layer 1: the client lifecycle, minus window/GL/sound/GUI. */
   private static void lifecycle(final List<Rules.Patch> out) {
      out.add(new BodyReplace(MINECRAFT, "<init>", "(Lnet/minecraft/client/main/GameConfig;)V", MinecraftLifecycle.class, "init"));
      out.add(new BodyReplace(MINECRAFT, "run", "()V", MinecraftLifecycle.class, "run"));
      // setScreenAndShow() calls it, and the play listener screens for a frame while handing over the level.
      out.add(new BodyReplace(MINECRAFT, "renderFrame", "(Z)V", MinecraftLifecycle.class, "renderFrame"));
      out.add(new BodyReplace(MINECRAFT, "runTick", "(Z)V", MinecraftLifecycle.class, "runTick"));
      out.add(new BodyReplace(MINECRAFT, "tick", "()V", MinecraftLifecycle.class, "tick"));
      out.add(new BodyReplace(MINECRAFT, "setLevel", "(Lnet/minecraft/client/multiplayer/ClientLevel;)V", MinecraftLifecycle.class, "setLevel"));
      out.add(new BodyReplace(MINECRAFT, "updateLevelInEngines", "(Lnet/minecraft/client/multiplayer/ClientLevel;)V", MinecraftLifecycle.class, "updateLevelInEngines"));
      out.add(new BodyReplace(MINECRAFT, "updateLevelInEngines", "(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", MinecraftLifecycle.class, "updateLevelInEnginesWithSound"));
      out.add(new BodyReplace(MINECRAFT, "setScreen", "(Lnet/minecraft/client/gui/screens/Screen;)V", MinecraftLifecycle.class, "setScreen"));
      // Level teardown before a reconfiguration: vanilla resets the game renderer, clears the narrator and
      // pokes the GUI, all of which are null headless. Reached from handleConfigurationStart (transfer/reload).
      out.add(new BodyReplace(MINECRAFT, "clearClientLevel", "(Lnet/minecraft/client/gui/screens/Screen;)V", MinecraftLifecycle.class, "clearClientLevel"));
      out.add(new BodyReplace(MINECRAFT, "close", "()V", MinecraftLifecycle.class, "close"));
      out.add(new BodyReplace(MINECRAFT, "destroy", "()V", MinecraftLifecycle.class, "destroy"));
   }

   /** Layer 1b: chat lines go to the console and the plugin event instead of the GUI. */
   private static void chat(final List<Rules.Patch> out) {
      out.add(new BodyReplace(CHAT_LISTENER, "handlePlayerChatMessage", null, ChatListenerRouting.class, "playerMessage"));
      out.add(new BodyReplace(CHAT_LISTENER, "handleDisguisedChatMessage", null, ChatListenerRouting.class, "disguisedMessage"));
      out.add(new BodyReplace(CHAT_LISTENER, "handleSystemMessage", null, ChatListenerRouting.class, "systemMessage"));
      out.add(new BodyReplace(CHAT_LISTENER, "handleOverlay", null, ChatListenerRouting.class, "overlayMessage"));
      out.add(new BodyReplace(CHAT_LISTENER, "handleChatMessageError", null, ChatListenerRouting.class, "messageError"));
   }

   /**
    * Layer 1c: resource-pack negotiation. Vanilla either downloads the pack and reloads the resource
    * manager (renderer-bound) or opens a confirmation screen nobody can click headless - in both cases the
    * server never receives the status packet it waits for on a {@code require-resource-pack} server.
    */
   private static void resourcePacks(final List<Rules.Patch> out) {
      out.add(new BodyReplace(COMMON_LISTENER, "handleResourcePackPush",
         "(Lnet/minecraft/network/protocol/common/ClientboundResourcePackPushPacket;)V",
         ResourcePackHandling.class, "handlePush"));
      out.add(new BodyReplace(COMMON_LISTENER, "handleResourcePackPop",
         "(Lnet/minecraft/network/protocol/common/ClientboundResourcePackPopPacket;)V",
         ResourcePackHandling.class, "handlePop"));
   }

   /**
    * Layer 1c: render/GUI statements reachable from code the bot still needs. Every entry here came from
    * the offline CouplingScan over the vanilla jar (class, method, null-field), so the list is complete
    * for the boot/login/world-load paths rather than crash-driven.
    *
    * <p>{@code ClientPacketListener.<init>} is the important one: it reads {@code minecraft.gui.getChat()}
    * to restore chat state, so constructing the login listener threw inside the handshake's future chain -
    * which is why the server saw a bare "Disconnected" with nothing on the client side.
    */
   private static void renderCoupling(final List<Rules.Patch> out) {
      out.add(new BodyReplace(OPTIONS, "applyGraphicsPreset", "(Lnet/minecraft/client/GraphicsPreset;)V", RenderCoupling.class, "noopGraphicsPreset"));
      // The render camera does not exist headless: getCameraEntity asked gameRenderer for it, which every
      // tick does through Minecraft.pick, and setCameraEntity is called from updateLevelInEngines.
      out.add(new BodyReplace(MINECRAFT, "getCameraEntity", "()Lnet/minecraft/world/entity/Entity;", RenderCoupling.class, "cameraEntity"));
      out.add(new BodyReplace(MINECRAFT, "setCameraEntity", "(Lnet/minecraft/world/entity/Entity;)V", RenderCoupling.class, "noopSetCameraEntity"));
      out.add(new BodyReplace(MINECRAFT, "isEnforceUnicode", "()Z", RenderCoupling.class, "noUnicodeEnforcement"));
      // The level-load gate asks the renderer whether the player's section is compiled and visible. A headless
      // client has nothing to wait for, and vanilla's own fallbacks (timeout, spectator, out of build height)
      // already answer yes, so this is a semantics match rather than a bypass.
      out.add(new BodyReplace(WAITING_FOR_CHUNK, "isReady", "()Z", LevelLoadReadiness.class, "isReady"));
      // ClientPacketListener.<init> asks for the debug overlay, which vanilla hangs off Gui.
      out.add(new BodyReplace(MINECRAFT, "getDebugOverlay", "()Lnet/minecraft/client/gui/components/DebugScreenOverlay;", RenderCoupling.class, "debugOverlay"));
      // ...and the overlay's own constructor allocates GPU buffers, so it needs a headless body: a real
      // object with plain state and null GPU handles, since nothing here is ever drawn.
      out.add(new BodyReplace(DEBUG_OVERLAY, "<init>", "(Lnet/minecraft/client/Minecraft;)V", RenderCoupling.class, "headlessDebugOverlay"));
      // Every kick and every 'disconnect' runs through this; vanilla's version died on the renderer, the
      // narrator and the social manager, which left the client unable to reconnect afterwards.
      out.add(new BodyReplace(MINECRAFT, "disconnect", "(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", MinecraftLifecycle.class, "disconnect"));
      // LocalPlayer.tick ticks its ambient-sound handlers, which hold a SoundManager captured at construction
      // - null headless. Both bodies only play sounds, so they are replaced instead of dropped.
      out.add(new BodyReplace(UNDERWATER_AMBIENCE, "tick", "()V", AmbientSoundHandling.class, "noopUnderwater"));
      out.add(new BodyReplace(BIOME_AMBIENCE, "tick", "()V", AmbientSoundHandling.class, "noopBiome"));
      // Option validation asks the Window for the GUI scale limit; without this, options.txt never loaded.
      out.add(new BodyReplace(OPTION_RANGE, "maxInclusive", "()I", RenderCoupling.class, "encodableMaxInclusive"));

      String listenerCtor = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/network/Connection;Lnet/minecraft/client/multiplayer/CommonListenerCookie;)V";
      // Every (class, method, field) triple below is exactly what CouplingScan reported for 26.1.2, so the
      // list is complete for the boot/login/world-load paths rather than crash-driven.
      drop(out, PACKET_LISTENER, "<init>", listenerCtor, "gui");
      // handleLogin is ported wholesale (see ClientLoginHandling): its vanilla tail is login-duration toasts,
      // quick play logging and the window title, and each reaches the renderer differently (SystemToast splits
      // text with Minecraft.font). Statement removal cannot express the toast case because the null comes from
      // inside a helper's own argument evaluation.
      out.add(new BodyReplace(PACKET_LISTENER, "handleLogin",
         "(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V", ClientLoginHandling.class, "handleLogin"));
      drop(out, PACKET_LISTENER, "startWaitingForNewLevel", null, "levelRenderer", "gui");
      // handleConfigurationStart needs the whole method: the chat-screen state is stored in the listener cookie
      // (StatementDrop refuses stored values) and minecraft.gui is null headless.
      out.add(new BodyReplace(PACKET_LISTENER, "handleConfigurationStart",
         "(Lnet/minecraft/network/protocol/game/ClientboundStartConfigurationPacket;)V",
         ClientPacketHandling.class, "handleConfigurationStart"));
      drop(out, PACKET_LISTENER, "handleRespawn", null, "levelRenderer");
      drop(out, PACKET_LISTENER, "handleGameEvent", null, "gui");
      drop(out, PACKET_LISTENER, "handleBossUpdate", null, "gui");
      drop(out, PACKET_LISTENER, "handleDeleteChat", null, "gui");
      // Tab header/footer is pure player-list drawing and servers send it on join; the ternary in the argument
      // makes it unbounded as a statement, so the body is replaced (see RenderCoupling#noopTabList).
      out.add(new BodyReplace(PACKET_LISTENER, "handleTabListCustomisation", "(Lnet/minecraft/network/protocol/game/ClientboundTabListPacket;)V", RenderCoupling.class, "noopTabList"));
      drop(out, PACKET_LISTENER, "handleSetEntityPassengersPacket", null, "gui");
      drop(out, PACKET_LISTENER, "handleTitlesClear", null, "gui");
      drop(out, PACKET_LISTENER, "setTitleText", null, "gui");
      drop(out, PACKET_LISTENER, "setSubtitleText", null, "gui");
      drop(out, PACKET_LISTENER, "setActionBarText", null, "gui");
      drop(out, PACKET_LISTENER, "setTitlesAnimation", null, "gui");
      drop(out, PACKET_LISTENER, "handleChunksBiomes", null, "levelRenderer");
      drop(out, PACKET_LISTENER, "handleGameTestHighlightPos", null, "levelRenderer");
      drop(out, PACKET_LISTENER, "lambda$handleLevelChunkWithLight$0", null, "levelRenderer");
      drop(out, PACKET_LISTENER, "handleAnimate", null, "particleEngine");
      drop(out, PACKET_LISTENER, "handleEntityEvent", null, "particleEngine", "gameRenderer");
      // The pickup sound and the ItemPickupParticle are renderer/audio only, but the render state vanilla
      // extracts is STORED in a local - StatementDrop refuses that by design, so the method is ported.
      out.add(new BodyReplace(PACKET_LISTENER, "handleTakeItemEntity",
         "(Lnet/minecraft/network/protocol/game/ClientboundTakeItemEntityPacket;)V",
         ClientPacketHandling.class, "handleTakeItemEntity"));
      drop(out, PACKET_LISTENER, "lambda$openDemoIntroScreen$1", null, "mouseHandler");
      drop(out, PACKET_LISTENER, "lambda$openSignedCommandSendConfirmationWindow$1", null, "keyboardHandler");

      // Accessor-shaped coupling, from the same scan once it knew how to look for accessors. Note what is
      // deliberately ABSENT: getPlayerSocialManager is reachable from handlePlayerInfo*, and that bookkeeping
      // is real network state, so the object is constructed in the constructor donor instead of being dodged.
      drop(out, PACKET_LISTENER, "handleRecipeBookAdd", null, "#getToastManager");
      drop(out, PACKET_LISTENER, "handleContainerSetSlot", null, "#getTutorial");
      drop(out, PACKET_LISTENER, "handleSetCursorItem", null, "#getTutorial");
      drop(out, PACKET_LISTENER, "handleSetPlayerInventory", null, "#getTutorial");
      drop(out, PACKET_LISTENER, "handleEntityEvent", null, "#getSoundManager");
      drop(out, PACKET_LISTENER, "handleStopSoundEvent", null, "#getSoundManager");
      drop(out, PACKET_LISTENER, "postAddEntitySoundInstance", null, "#getSoundManager");
      drop(out, PACKET_LISTENER, "handleMapItemData", null, "#getMapTextureManager");
      drop(out, PACKET_LISTENER, "handleRespawn", null, "#getMusicManager");
      // renderer state extraction was ported wholesale (see ClientPacketHandling)

      drop(out, CHUNK_CACHE, "onLightUpdate", null, "levelRenderer");
      // ClientLevel effects. Where a statement can be bounded it is removed (anchored on the OUTERMOST null
      // value, which is what makes the arguments removable too); where a local keeps the render value alive
      // afterwards, the whole method is replaced instead.
      drop(out, CLIENT_LEVEL, "tick", null, "#getSoundManager");
      out.add(new BodyReplace(CLIENT_LEVEL, "playSound", "(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZJ)V", ClientLevelEffects.class, "noopPlaySound"));
      out.add(new BodyReplace(CLIENT_LEVEL, "doAddParticle", "(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V", ClientLevelEffects.class, "noopAddParticle"));
      drop(out, CLIENT_LEVEL, "addBreakingBlockEffect", null, "particleEngine");
      drop(out, CLIENT_LEVEL, "createFireworks", null, "particleEngine");
      drop(out, CLIENT_LEVEL, "lambda$addDestroyBlockEffect$0", null, "particleEngine");

      // Layer 1d: the in-world paths, which only become reachable after a successful login. Without these the
      // bot dies on its very first movement tick (aiStep) and the mine command would fail mid-break.
      drop(out, LOCAL_PLAYER, "aiStep", null, "#getTutorial");
      drop(out, LOCAL_PLAYER, "crit", null, "particleEngine");
      drop(out, LOCAL_PLAYER, "magicCrit", null, "particleEngine");
      drop(out, LOCAL_PLAYER, "refreshChatAbilities", null, "gui");
      drop(out, LOCAL_PLAYER, "updateTutorialInventoryAction", null, "#getTutorial");
      drop(out, LOCAL_PLAYER, "startRiding", null, "#getSoundManager");
      drop(out, LOCAL_PLAYER, "updateIsUnderwater", null, "#getSoundManager");
      drop(out, LOCAL_PLAYER, "onSyncedDataUpdated", null, "#getSoundManager");
      drop(out, LOCAL_PLAYER, "handlePortalTransitionEffect", null, "#getSoundManager");
      String localPlayerCtor = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/ClientLevel;"
         + "Lnet/minecraft/client/multiplayer/ClientPacketListener;Lnet/minecraft/stats/StatsCounter;"
         + "Lnet/minecraft/client/ClientRecipeBook;Lnet/minecraft/world/entity/player/Input;Z"
         + "Lnet/minecraft/client/multiplayer/chat/ChatAbilities;)V";
      drop(out, LOCAL_PLAYER, "<init>", localPlayerCtor, "#getSoundManager");

      drop(out, GAME_MODE, "startDestroyBlock", null, "#getTutorial");
      drop(out, GAME_MODE, "continueDestroyBlock", null, "#getTutorial", "#getSoundManager");
      drop(out, GAME_MODE, "stopDestroyBlock", null, "#getTutorial");

      // Sound and block-entity paths. These are whole-method replacements, not statement drops: the earlier
      // StatementDrop rules for the same methods reported "matched nothing" (the reads sit in inlined
      // overloads), and a silently inert rule in a hot packet path is worse than a blunt one. Every method
      // here does nothing but hand audio or renderer bookkeeping to objects a headless client cannot have.
      String entitySoundEvent = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V";
      String positionedSound = "(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V";
      String seededEntitySound = "(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V";
      String ridingSound = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V";
      out.add(new BodyReplace(CLIENT_LEVEL, "playSeededSound", seededEntitySound, ClientLevelEffects.class, "noopSeededSound"));
      out.add(new BodyReplace(CLIENT_LEVEL, "playSeededSound", ridingSound, ClientLevelEffects.class, "noopSeededSoundAtEntity"));
      out.add(new BodyReplace(CLIENT_LEVEL, "playLocalSound", entitySoundEvent, ClientLevelEffects.class, "noopLocalSoundAtEntity"));
      out.add(new BodyReplace(CLIENT_LEVEL, "playLocalSound", positionedSound, ClientLevelEffects.class, "noopLocalSoundPositioned"));
      out.add(new BodyReplace(CLIENT_LEVEL, "playPlayerSound", "(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V", ClientLevelEffects.class, "noopPlayerSound"));
      out.add(new BodyReplace(CLIENT_LEVEL, "onBlockEntityAdded", "(Lnet/minecraft/world/level/block/entity/BlockEntity;)V", ClientLevelEffects.class, "noopOnBlockEntityAdded"));

      // ClientLevel also keeps the LevelRenderer it was handed by its constructor, so the sites below are of
      // ITS OWN class rather than Minecraft fields - every block update and section-dirty notification used
      // to kill the client one tick after joining. Each dropped call only tells the renderer that something
      // changed, which is precisely what a client with no renderer cannot act on.
      drop(out, CLIENT_LEVEL, "setBlocksDirty", null, "~levelRenderer");
      drop(out, CLIENT_LEVEL, "sendBlockUpdated", null, "~levelRenderer");
      drop(out, CLIENT_LEVEL, "destroyBlockProgress", null, "~levelRenderer");
      drop(out, CLIENT_LEVEL, "onSectionBecomingNonEmpty", null, "~levelRenderer");
      drop(out, CLIENT_LEVEL, "setSectionDirtyWithNeighbors", null, "~levelRenderer");
      drop(out, CLIENT_LEVEL, "setSectionRangeDirty", null, "~levelRenderer");
      drop(out, PACKET_LISTENER, "handleGameEvent", null, "#getNarrator");
      drop(out, PACKET_LISTENER, "handleSetEntityPassengersPacket", null, "#getNarrator");
      drop(out, PACKET_LISTENER, "handleEntityEvent", null, "#getSoundManager");
      drop(out, ADVANCEMENTS, "update", "(Lnet/minecraft/network/protocol/game/ClientboundUpdateAdvancementsPacket;)V", "#getToastManager");
   }

   /** Adds one StatementDrop rule per named null-typed {@code Minecraft} field used by {@code method}. */
   private static void drop(final List<Rules.Patch> out, final String owner, final String method, final String descriptor, final String... fields) {
      for (String field : fields) {
         // expectedMin 0: a scanned site may only pass the value as an argument, which needs no removal.
         // The rule is still strict about the method existing (requireMethod) and about the resulting bytes
         // verifying, so a stale name or a mis-bounded range fails here instead of at launch.
         out.add(new Rules.StatementDrop(owner, method, descriptor, field, 0));
      }
   }

   /**
    * Layer 2: plugin event wiring. Every entry pairs a vanilla handler with a {@code BotHooks} method whose
    * parameters match it, and hooks are cheap because {@code BotPlugins.fire()} returns immediately when no
    * listener is registered.
    *
    * <p>Deliberately unwired, rather than wired on a guess:
    * <ul>
    *   <li>{@code WinGameEvent} - 26.1.2 has no win/credits packet at all (only
    *       {@code ClientboundPlayerCombatEndPacket}), so there is no entry point to hook.
    *   <li>{@code RecipeUnlockedEvent} - {@code ClientboundPlaceRecipePacket} no longer exists.
    *   <li>{@code PlayerJoinedEvent} - the update packet exposes its entries as a record whose profile
    *       accessor is not one I could confirm; a wrong guess would fire the event with the wrong player.
    *   <li>{@code BookOpenEvent} - needs the book object that only the vanilla body produces, and the
    *       owning field/getter names are unconfirmed.
    *   <li>{@code MobEffect*Event} - the packet exposes effect parts but not the {@code MobEffectInstance}
    *       or the upgrade flag its event carries.
    *   <li>{@code SendChatEvent}, {@code SendCommandEvent} - sender side, fired from
    *       {@code HeadlessSession} rather than from a patch.
    * </ul>
    *
    * <p>Ordering: any {@link Rules.StatementDrop} for a handler must be registered before its wrap here, as
    * the drop would otherwise see only the generated dispatcher.
    */
   private static void events(final List<Rules.Patch> out) {
      if (Boolean.getBoolean("eml.events.off")) {
         return;
      }

      out.add(new Wrap(PACKET_LISTENER, "handlePlayerCombatKill", null, "onCombatKill"));
      out.add(new Wrap(PACKET_LISTENER, "handlePongResponse", null, "onPongResponse"));
      wrap(out, "setTitleText", "onTitleText", true);

      wrap(out, "handleAddEntity", "onAddEntity", true);
      wrap(out, "handleRemoveEntities", "onRemoveEntities", false);
      wrap(out, "handlePlayerInfoRemove", "onPlayerInfoRemove", false);
      wrap(out, "handleBlockUpdate", "onBlockUpdate", false);
      wrap(out, "handleLevelChunkWithLight", "onLevelChunkWithLight", false);
      wrap(out, "handleForgetLevelChunk", "onForgetLevelChunk", false);
      wrap(out, "handleExplosion", "onExplosion", false);
      wrap(out, "handleSetHealth", "onSetHealth", false);
      wrap(out, "handleSetExperience", "onSetExperience", false);
      wrap(out, "handleSetHeldSlot", "onSetHeldSlot", false);
      wrap(out, "handleContainerClose", "onContainerClose", false);
      wrap(out, "handleOpenScreen", "onOpenScreen", true);
      wrap(out, "handleContainerContent", "onContainerSetContent", true);
      wrap(out, "handleContainerSetSlot", "onContainerSetSlot", true);
      wrap(out, "handleUpdateRecipes", "onUpdateRecipes", false);
      wrap(out, "handleGameRuleValues", "onGameRuleValues", false);
      wrap(out, "handleBossUpdate", "onBossUpdate", false);
      wrap(out, "handleRespawn", "onRespawn", false);
   }

   /** Wires a {@code BotHooks} method to a uniquely named {@code ClientPacketListener} handler. */
   private static void wrap(final List<Rules.Patch> out, final String handler, final String hook, final boolean after) {
      out.add(new Wrap(PACKET_LISTENER, handler, null, "eml/hooks/BotHooks", hook, false, after));
   }

   /**
    * Layer 2b: diagnostics. These wrap the two points every connection teardown must pass, which is what
    * makes a swallowed packet-handling failure visible instead of showing up as a bare server-side
    * "Disconnected". The netty-typed ones erase their parameters (see {@link Wrap}); all four hooks start
    * with a switch test, so the cost while debugging is off is one static call and one boolean.
    */
   private static void diagnostics(final List<Rules.Patch> out) {
      if (Boolean.getBoolean("eml.debug.off")) {
         return;
      }

      out.add(new Wrap(CONNECTION, "exceptionCaught", "(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Throwable;)V", eml.hooks.BotDebug.class, "onConnectionException", true));
      out.add(new Wrap(CONNECTION, "disconnect", "(Lnet/minecraft/network/chat/Component;)V", eml.hooks.BotDebug.class, "onDisconnect", false));
      out.add(new Wrap(CONNECTION, "disconnect", "(Lnet/minecraft/network/DisconnectionDetails;)V", eml.hooks.BotDebug.class, "onDisconnectDetails", false));
      // Packet-level tracing is opt-in only, so it is guarded inside the hook rather than here.
      out.add(new Wrap(CONNECTION, "channelRead0", "(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", eml.hooks.BotDebug.class, "onInboundPacket", true));
   }

   /**
    * Layer 3: the dispatch guard. This is the last rule in the inventory on purpose - it is the only one
    * that does not neutralise a specific coupling site, it decides what happens to the sites nobody has
    * found yet.
    *
    * <p>The TakeItemEntity crash is the reason it exists: a render state was stored in a local, which
    * {@code StatementDrop} refuses by design, so the method needed a donor. That works for every site
    * somebody exercises, but vanilla's coupling surface is not enumerable - it is larger than any scan and
    * it grows with every version. So instead of a donor per site, the single funnel every packet passes
    * through now classifies the failure: a null renderer/audio/GUI object skips the packet and counts a
    * warning, anything else keeps vanilla's disconnect ({@code eml.hooks.BotResilience}).
    */
   private static void resilience(final List<Rules.Patch> out) {
      out.add(new BodyReplace(PACKET_ENTRY, "handle", "()V", PacketSafetyNet.class, "handle"));
   }

   /**
    * Render/sound/GUI coupling sites still to neutralise. Each entry is a concrete NPE discovered by
    * running, and {@code eml.tools.CouplingScan} enumerates the remaining candidates mechanically;
    * they are kept here as a working list rather than guessed up front.
    */
   private static void pendingCallDrops(final List<Rules.Patch> out) {
      // Shape of an entry, filled in from CouplingScan output plus runtime stack traces:
      // out.add(new CallDrop(PACKET_LISTENER, "handleLogin", null,
      //     "net/minecraft/client/renderer/LevelRenderer", "allChanged", "()V"));
   }
}
