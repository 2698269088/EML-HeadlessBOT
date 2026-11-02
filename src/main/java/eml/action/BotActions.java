package eml.action;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import com.mojang.blaze3d.platform.DisplayData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.User;
import net.minecraft.client.headless.ViewDistance;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * The bot-facing action API, written against the ORIGINAL vanilla public API.
 *
 * <p>The old source-tree build grew these methods onto {@code Minecraft} itself; with the ASM route we
 * never add members to vanilla classes, so everything lives here. Members that vanilla keeps private and
 * that have no public accessor ({@code pendingConnection}, {@code missTime}) are reached reflectively -
 * once-per-connection / per-click costs, and the field name is checked at class load so a rename fails
 * loudly.
 */
public final class BotActions {
   private static final Field PENDING_CONNECTION;
   private static final Field MISS_TIME;
   /** {@code OptionInstance.values} - the private value set that carries an integer option's own range. */
   private static final Field OPTION_VALUES;

   static {
      PENDING_CONNECTION = field(Minecraft.class, "pendingConnection");
      MISS_TIME = field(Minecraft.class, "missTime");
      OPTION_VALUES = field(OptionInstance.class, "values");
   }

   private BotActions() {
   }

   private static Field field(final Class<?> owner, final String name) {
      try {
         Field field = owner.getDeclaredField(name);
         field.setAccessible(true);
         return field;
      } catch (ReflectiveOperationException e) {
         throw new IllegalStateException(
            "vanilla field '" + name + "' is gone - the ASM route needs this patch point updated for this Minecraft version", e);
      }
   }

   // ==================== session bootstrap ====================

   /** Offline-mode user identity, derived from the name exactly like the vanilla singleplayer client. */
   public static User offlineUser(final String username) {
      return new User(username, offlineUuid(username), "0", Optional.empty(), Optional.empty());
   }

   public static UUID offlineUuid(final String username) {
      return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
   }

   /**
    * Builds the launcher-style config. The display block is inert: the patched constructor never
    * creates a window, it only reads the folders and the user block.
    */
   public static GameConfig gameConfig(
      final java.io.File gameDirectory,
      final java.io.File assetDirectory,
      final User user,
      final String launchVersion,
      final String versionType,
      final boolean offline,
      final Proxy proxy
   ) {
      GameConfig.UserData userData = new GameConfig.UserData(user, proxy);
      DisplayData display = new DisplayData(854, 480, OptionalInt.empty(), OptionalInt.empty(), false);
      GameConfig.FolderData folders = new GameConfig.FolderData(
         gameDirectory,
         gameDirectory.toPath().resolve("resourcepacks").toFile(),
         assetDirectory,
         null
      );
      GameConfig.GameData game = new GameConfig.GameData(false, launchVersion, versionType, false, false, false, false, offline);
      GameConfig.QuickPlayData quickPlay = new GameConfig.QuickPlayData(null, GameConfig.QuickPlayVariant.DISABLED);
      return new GameConfig(userData, display, folders, game, quickPlay);
   }

   // ==================== connection plumbing ====================

   @Nullable
   public static Connection pendingConnection(final Minecraft client) {
      try {
         return (Connection)PENDING_CONNECTION.get(client);
      } catch (IllegalAccessException e) {
         throw new IllegalStateException(e);
      }
   }

   public static void setPendingConnection(final Minecraft client, final @Nullable Connection connection) {
      try {
         PENDING_CONNECTION.set(client, connection);
      } catch (IllegalAccessException e) {
         throw new IllegalStateException(e);
      }
   }

   // ==================== player actions ====================

   public static void swing(final Minecraft client, final InteractionHand hand) {
      if (client.player != null) {
         client.player.swing(hand);
      }
   }

   public static boolean dropItem(final Minecraft client, final boolean entireStack) {
      LocalPlayer player = client.player;
      if (player == null || player.isSpectator()) {
         return false;
      }

      if (player.drop(entireStack)) {
         player.swing(InteractionHand.MAIN_HAND);
         return true;
      }

      return false;
   }

   public static void selectSlot(final Minecraft client, final int slot) {
      if (client.player != null && slot >= 0 && slot < 9) {
         client.player.getInventory().setSelectedSlot(slot);
      }
   }

   public static void look(final Minecraft client, final float yaw, final float pitch) {
      if (client.player != null) {
         client.player.setYRot(yaw);
         client.player.setXRot(pitch);
      }
   }

   /** {@code Minecraft.setLocalGameMode}: the client-side view only; the server must allow the change. */
   public static void setLocalGameMode(final Minecraft client, final GameType mode) {
      MultiPlayerGameMode gameMode = client.gameMode;
      if (gameMode != null) {
         gameMode.setLocalMode(mode);
      }
   }

   /**
    * Applies a view distance and tells the server, so it streams the matching radius. Vanilla only sends the
    * information packet when the option changes through the GUI - which a headless bot never opens - so
    * without the explicit broadcast the server keeps the radius it got at join time.
    *
    * <p>Two ceilings apply, and the narrower one is the option's own: {@code Options.renderDistance} is
    * {@code IntRange(2, demo ? 32 : 16)} in this version, and setting a value outside it does not throw - it
    * silently keeps the previous one. So the value is clamped to the range the option reports, and read back
    * afterwards, because "what did this call achieve" has to be answered by the option itself.
    *
    * @return the radius actually in effect afterwards
    */
   public static int applyViewDistance(final Minecraft client, final int chunks) {
      int requested = net.minecraft.util.Mth.clamp(chunks, ViewDistance.MIN,
         Math.min(ViewDistance.MAX, viewDistanceCeiling(client)));
      client.options.renderDistance().set(requested);
      int applied = client.options.renderDistance().get();

      if (applied != requested) {
         eml.hooks.BotRuntime.log().debug("view distance {} is not accepted by this client, in effect {}",
            requested, applied);
      }

      ClientPacketListener connection = client.getConnection();
      if (connection != null) {
         connection.broadcastClientInformation(client.options.buildPlayerInformation());
      }

      return applied;
   }

   /**
    * The largest radius this client's option accepts (2..16 non-demo, 2..32 in demo mode). Both the field that
    * holds the option's value set and the range class itself are package-private in vanilla, so the ceiling is
    * read reflectively - the same trade-off as {@code pendingConnection}: one call per view-distance change,
    * and a missing member degrades to the protocol ceiling instead of failing at runtime (the caller reads the
    * option back afterwards, so the applied value stays truthful either way).
    */
   private static int viewDistanceCeiling(final Minecraft client) {
      try {
         Object values = OPTION_VALUES.get(client.options.renderDistance());
         Method maxInclusive = values.getClass().getMethod("maxInclusive");
         maxInclusive.setAccessible(true);
         Object ceiling = maxInclusive.invoke(values);
         return ceiling instanceof Integer value ? value : ViewDistance.MAX;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
         return ViewDistance.MAX;
      }
   }

   public static boolean destroyBlock(final Minecraft client, final BlockPos pos) {
      return client.gameMode != null && client.gameMode.destroyBlock(pos);
   }

   /** Left click: attack the crosshair entity, otherwise start breaking the targeted block. */
   public static boolean startAttack(final Minecraft client) {
      LocalPlayer player = client.player;
      MultiPlayerGameMode gameMode = client.gameMode;
      if (missTime(client) > 0 || player == null || gameMode == null || client.hitResult == null || player.isHandsBusy()) {
         return false;
      }

      HitResult hit = client.hitResult;
      if (hit.getType() == HitResult.Type.ENTITY) {
         gameMode.attack(player, ((EntityHitResult)hit).getEntity());
         player.swing(InteractionHand.MAIN_HAND);
         return true;
      }

      if (hit.getType() == HitResult.Type.BLOCK) {
         BlockHitResult blockHit = (BlockHitResult)hit;
         BlockPos pos = blockHit.getBlockPos();
         Direction direction = blockHit.getDirection();
         if (client.level == null) {
            return false;
         }

         BlockState state = client.level.getBlockState(pos);
         if (state.isAir()) {
            return false;
         }

         gameMode.stopDestroyBlock();
         boolean destroyed = gameMode.startDestroyBlock(pos, direction);
         if (destroyed) {
            player.swing(InteractionHand.MAIN_HAND);
         }

         return destroyed;
      }

      gameMode.stopDestroyBlock();
      return false;
   }

   /** Held / released left click: continue or stop breaking the targeted block. */
   public static void continueAttack(final Minecraft client, final boolean down) {
      LocalPlayer player = client.player;
      MultiPlayerGameMode gameMode = client.gameMode;
      if (!down) {
         setMissTime(client, 0);
      }

      if (player == null || gameMode == null || client.level == null) {
         return;
      }

      if (missTime(client) <= 0 && !player.isUsingItem() && down && client.hitResult != null && client.hitResult.getType() == HitResult.Type.BLOCK) {
         BlockHitResult blockHit = (BlockHitResult)client.hitResult;
         BlockPos pos = blockHit.getBlockPos();
         if (!client.level.getBlockState(pos).isAir()) {
            Direction direction = blockHit.getDirection();
            if (gameMode.continueDestroyBlock(pos, direction)) {
               player.swing(InteractionHand.MAIN_HAND);
            }
         }
      } else if (!down) {
         gameMode.stopDestroyBlock();
      }
   }

   /** Releases a held item (finishes eating / drawing a bow / blocking). */
   public static void stopUseItem(final Minecraft client) {
      if (client.player != null && client.gameMode != null && client.player.isUsingItem()) {
         client.gameMode.releaseUsingItem(client.player);
      }
   }

   public static boolean isConnected(final Minecraft client) {
      ClientPacketListener play = client.getConnection();
      return play != null || pendingConnection(client) != null;
   }

   private static int missTime(final Minecraft client) {
      try {
         return MISS_TIME.getInt(client);
      } catch (IllegalAccessException e) {
         throw new IllegalStateException(e);
      }
   }

   private static void setMissTime(final Minecraft client, final int value) {
      try {
         MISS_TIME.setInt(client, value);
      } catch (IllegalAccessException e) {
         throw new IllegalStateException(e);
      }
   }
}
