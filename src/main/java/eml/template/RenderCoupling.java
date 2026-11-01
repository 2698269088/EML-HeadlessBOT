package eml.template;

import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.entity.Entity;

/**
 * Donors that neutralise render/GPU side effects reachable from code the headless client still calls.
 *
 * <p>These are deliberately NOT the obvious rendering entry points (those already live inside methods the
 * lifecycle donors replaced). They are callbacks that a game-logic path can reach by accident, which is
 * worse than a direct call because the failure appears far from its cause.
 */
public final class RenderCoupling {
   private RenderCoupling() {
   }

   /**
    * {@code Options.applyGraphicsPreset(GraphicsPreset)}: every {@code OptionInstance.set(...)} can reach
    * it through the option callbacks ({@code setGraphicsPresetToCustom} → here → {@code
    * GraphicsPreset.apply} → {@code RenderSystem.getDevice()}), so even setting the view distance threw
    * {@code IllegalStateException: Can't getDevice() before it was initialized} on a headless client.
    *
    * <p>Dropping the body is safe: a graphics preset only assigns render-quality options (particles,
    * terrain quality, mipmaps, entity shadows, cloud style), all of which are consumed by the renderer
    * that this client never constructs. The {@code graphicsPreset} option value itself is still written by
    * the caller, so {@code options.txt} keeps the user's selection.
    */
   public static void noopGraphicsPreset(final Options self, final GraphicsPreset preset) {
      // intentionally empty: the whole method only configures the GPU pipeline
   }

   /**
    * {@code Minecraft.getCameraEntity()}: vanilla asks the render camera
    * ({@code gameRenderer.getMainCamera().entity()}), which crashes headless because {@code gameRenderer}
    * is null. A headless client is permanently first person, so the camera entity IS the local player;
    * returning null before login is safe because {@code Minecraft.pick} already guards for it.
    */
   public static Entity cameraEntity(final Minecraft self) {
      return self.player;
   }

   /**
    * {@code Minecraft.setCameraEntity(Entity)}: writes into the render camera and refreshes a post effect,
    * both GPU-only. Kept as a no-op rather than a stored field because {@link #cameraEntity} derives the
    * value from the player, and because the vanilla call site ({@code updateLevelInEngines(null)}) only
    * wants the renderer to forget an entity that no renderer exists to draw.
    */
   public static void noopSetCameraEntity(final Minecraft self, final Entity cameraEntity) {
      // intentionally empty: nothing to synchronise without a render camera
   }

   /**
    * {@code Minecraft.isEnforceUnicode()}: returns {@code options.forceUnicodeFont().get()}, which is
    * read back into the {@code guiScale} option's range validator DURING {@code Options.<init>} - before
    * the {@code Minecraft.options} field is assigned. Vanilla logs "Failed to load options" and keeps the
    * defaults, so the old source-tree build simply answered false; headless has no font rendering to
    * enforce either way.
    */
   public static boolean noUnicodeEnforcement(final Minecraft self) {
      return false;
   }

   /**
    * {@code Minecraft.getDebugOverlay()}: vanilla forwards to {@code gui.getDebugOverlay()}, and {@code gui}
    * is null headless. {@code ClientPacketListener}'s constructor calls it, so login could not even build the
    * play listener. The replacement is a standalone instance (see {@code BotRuntime#debugOverlay}).
    */
   public static net.minecraft.client.gui.components.DebugScreenOverlay debugOverlay(final Minecraft self) {
      return eml.hooks.BotRuntime.debugOverlay(self);
   }

   /**
    * {@code OptionInstance.ClampingLazyMaxIntRange.maxInclusive()}: the lazy half of this range asks the
    * {@code Window} for the GUI scale limit, and {@code Minecraft.getWindow()} is null headless - so loading
    * {@code options.txt} aborted with "Failed to load options" and the file's values were thrown away
    * ({@code Options.load} swallows the failure, which is why it only looked like a warning).
    *
    * <p>Answering with the option's own encodable maximum keeps validation intact; the screen-size derived
    * tightening only ever mattered for a window that does not exist here.
    */
   public static int encodableMaxInclusive(final net.minecraft.client.OptionInstance.ClampingLazyMaxIntRange self) {
      return self.encodableMaxInclusive();
   }

   /**
    * {@code ClientPacketListener.handleTabListCustomisation(ClientboundTabListPacket)}: the whole body after
    * the thread check is {@code gui.getTabList().setHeader/setFooter}. Servers send this on join, so leaving
    * it in place would disconnect every bot the moment it announced a tab header.
    *
    * <p>Written as a body replacement rather than a statement removal because the header argument is built by
    * a ternary ({@code isEmpty() ? null : header}) - the branches make the statement unbounded, and the
    * overlay state it fills has no headless consumer anyway.
    */
   public static void noopTabList(
      final net.minecraft.client.multiplayer.ClientPacketListener self,
      final net.minecraft.network.protocol.game.ClientboundTabListPacket packet
   ) {
      net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet, self, self.minecraft.packetProcessor());
      // header and footer existed only to draw the player list
   }

   /**
    * {@code DebugScreenOverlay.<init>(Minecraft)}: the vanilla constructor asks
    * {@code RenderSystem.getSequentialBuffer(...)} for a crosshair index buffer and then fills a vertex
    * buffer, which threw "Rendersystem called from wrong thread" the moment the play listener asked for the
    * overlay - a headless client has no render thread at all.
    *
    * <p>Replaced rather than deleted, because {@code ClientPacketListener}'s constructor stores
    * {@code getPingLogger()} into a {@code PingDebugMonitor} and later reads {@code showNetworkCharts()};
    * both are plain state, so keeping a real object preserves behaviour while the GPU handles stay null.
    * The chart objects are also left null: they exist only to be drawn.
    */
   public static void headlessDebugOverlay(
      final net.minecraft.client.gui.components.DebugScreenOverlay self,
      final Minecraft minecraft
   ) {
      self.minecraft = minecraft;
      self.font = minecraft.font;
      self.frameTimeLogger = new net.minecraft.util.debugchart.LocalSampleLogger(1);
      self.tickTimeLogger = new net.minecraft.util.debugchart.LocalSampleLogger(net.minecraft.util.debugchart.TpsDebugDimensions.values().length);
      self.pingLogger = new net.minecraft.util.debugchart.LocalSampleLogger(1);
      self.bandwidthLogger = new net.minecraft.util.debugchart.LocalSampleLogger(1);
      self.remoteSupportingLoggers = java.util.Map.of(
         net.minecraft.util.debugchart.RemoteDebugSampleType.TICK_TIME, self.tickTimeLogger
      );
      self.crosshairIndicies = null;
      self.crosshairBuffer = null;
      self.fpsChart = null;
      self.tpsChart = null;
      self.pingChart = null;
      self.bandwidthChart = null;
      // ProfilerPieChart has a public getter (getProfilerPieChart) that is not render-only, so it is built
      // rather than left null; its constructor only stores the font reference, which stays null here.
      self.profilerPieChart = new net.minecraft.client.gui.components.debugchart.ProfilerPieChart(self.font);
   }
}
