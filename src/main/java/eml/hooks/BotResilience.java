package eml.hooks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The headless client's answer to the one architectural asymmetry it cannot patch away.
 *
 * <p>Mineflayer never links a renderer, so a renderer null-check can never fire there. This client runs the
 * <em>real</em> vanilla code, so every packet handler that vanilla wrote against a renderer, an audio
 * manager or a GUI is a coupling site. {@code EmlPatches} enumerates the ones a scan could find and the
 * TakeItemEntity crash proved the rest: vanilla's {@code handleTakeItemEntity} stored a render state in a
 * local, a {@code StatementDrop} cannot bound a stored value, and the handler died on
 * {@code getEntityRenderDispatcher()} being null - which vanilla turns into a disconnect.
 *
 * <p>Adding a donor per site is an open-ended job: it grows with the surface of vanilla, not with the
 * protocol, and it only ever covers paths somebody happened to exercise. So the dispatch layer gets a
 * single guard instead (see {@code eml.template.PacketSafetyNet}): when a handler fails with a null that is
 * <em>provably</em> a renderer/audio/GUI object, the packet is skipped with a counted warning instead of
 * dropping the connection. Everything else still takes vanilla's path exactly.
 *
 * <p>The trade-off is stated rather than hidden: the game-state part of that handler ran only partially, so
 * the world model may be missing that one update. A slightly stale slot count is a far smaller loss than a
 * disconnect, and {@link #summary()} makes every skipped packet visible so an undiscovered coupling shows up
 * as a growing counter instead of a silent one.
 */
public final class BotResilience {
   /**
    * Types that are provably null in a headless client: each one is either patched out by
    * {@code EmlPatches} or simply never constructed, because nothing here draws, plays audio or shows a
    * screen. A null dereference naming one of these is coupling, not a bug in the packet's game logic.
    *
    * <p>Matched against the JVM's helpful NPE message ({@code Cannot invoke "X.y(...)" because the return
    * value of "Z" is null"}) and against the frame owner of the throwing instruction, so both the modern
    * message form and an older {@code -XX:-OmitStackTraceInFastThrow} trace classify correctly.
    */
   private static final String[] HEADLESS_NULL_TYPES = {
      "EntityRenderDispatcher", "EntityRenderer", "PlayerRenderer", "ItemRenderer",
      "LevelRenderer", "SectionRenderDispatcher", "GameRenderer", "RenderSystem", "GpuDevice",
      "ParticleEngine", "ParticleRenderType",
      "SoundManager", "SoundEngine", "MusicManager", "SoundBufferLibrary",
      "Gui", "GuiGraphics", "Font", "DebugScreenOverlay", "ToastManager", "Tutorial",
      "MapTextureManager", "Narrator", "MouseHandler", "KeyboardHandler", "Window", "Screen",
      "BlockEntityRenderDispatcher", "RenderBuffers", "EntityRenderStates", "ScoreboardRenderer"
   };

   /** Vanilla's own wording for a receiver that is not there, kept so an older trace still classifies. */
   private static final String[] NULL_RECEIVER_HINTS = {
      "because the return value of", "is null", "Cannot invoke", "Cannot read field", "Cannot load from"
   };

   private static final AtomicLong SKIPPED = new AtomicLong();
   private static final AtomicLong FATAL = new AtomicLong();
   private static final Map<String, AtomicLong> SKIPPED_BY_PACKET = new ConcurrentHashMap<>();
   private static final Map<String, String> LAST_REASON = new ConcurrentHashMap<>();
   private static volatile String lastSummary = "";
   private static volatile long lastSkipAtMillis;

   private BotResilience() {
   }

   /**
    * Whether the safety net is armed. {@code -Deml.resilience.off=true} restores vanilla's behaviour, which
    * is what to use when investigating whether a packet actually failed for a game-state reason.
    */
   public static boolean enabled() {
      return !Boolean.getBoolean("eml.resilience.off");
   }

   /**
    * Called from the patched {@code PacketProcessor$ListenerAndPacket.handle}.
    *
    * @return true when the failure was absorbed (the caller must then skip vanilla's {@code onPacketError})
    */
   public static boolean absorb(final Object packet, final Object listener, final Exception error) {
      if (error == null) {
         return false;
      }

      String reason = headlessNull(error);

      if (reason == null || !enabled()) {
         FATAL.incrementAndGet();
         return false;
      }

      String type = packet == null ? "<null packet>" : packet.getClass().getName();
      SKIPPED.incrementAndGet();
      SKIPPED_BY_PACKET.computeIfAbsent(type, key -> new AtomicLong()).incrementAndGet();
      LAST_REASON.put(type, reason);
      lastReasonSummary(reason, error);
      lastSkipAtMillis = System.currentTimeMillis();

      BotRuntime.log().warn("[bot] skipped {} - headless coupling ({}): {}", simple(type), reason, error);
      return true;
   }

   /**
    * Classifies an exception as renderer/audio/GUI coupling, returning a short reason, or {@code null} when
    * it is anything else. Only {@link NullPointerException} qualifies: every other failure may well be real
    * game-state breakage and must keep vanilla's disconnect behaviour.
    */
   public static String headlessNull(final Throwable error) {
      NullPointerException npe = null;

      for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
         if (cursor instanceof NullPointerException found) {
            npe = found;
            break;
         }

         if (cursor.getCause() == cursor) {
            break;
         }
      }

      if (npe == null) {
         return null;
      }

      String message = npe.getMessage();

      if (message != null) {
         boolean nullish = false;

         for (String hint : NULL_RECEIVER_HINTS) {
            if (message.contains(hint)) {
               nullish = true;
               break;
            }
         }

         if (nullish) {
            for (String type : HEADLESS_NULL_TYPES) {
               if (message.contains(type)) {
                  return type;
               }
            }
         }
      }

      // Message-less NPEs (fast throw, or a JVM that omits the helpful text) fall back to the top frames:
      // the throwing instruction lives in the renderer class in every coupling case seen so far.
      StackTraceElement[] frames = npe.getStackTrace();

      for (int i = 0; i < Math.min(frames.length, 8); i++) {
         String owner = frames[i].getClassName();

         for (String type : HEADLESS_NULL_TYPES) {
            if (owner.endsWith("." + type) || owner.contains("." + type + "$") || owner.endsWith("." + type + "Impl")) {
               return type;
            }
         }
      }

      return null;
   }

   private static void lastReasonSummary(final String reason, final Exception error) {
      StringBuilder text = new StringBuilder(reason).append(" @ ");

      StackTraceElement[] frames = error.getStackTrace();

      for (int i = 0; i < Math.min(frames.length, 4); i++) {
         if (i > 0) {
            text.append(" <- ");
         }

         text.append(frames[i].getClassName()).append(':').append(frames[i].getLineNumber());
      }

      lastSummary = text.toString();
   }

   /** One line per skipped packet type, newest counts first, for {@code botinfo}. */
   public static List<String> summary() {
      List<String> lines = new ArrayList<>();
      long skipped = SKIPPED.get();

      lines.add("resilience : " + (enabled() ? "armed" : "OFF (-Deml.resilience.off)") + ", skipped " + skipped + " packet(s), fatal " + FATAL.get() + " error(s)");

      if (skipped == 0L) {
         return lines;
      }

      if (!lastSummary.isEmpty()) {
         lines.add("last skip  : " + lastSummary + " (" + (System.currentTimeMillis() - lastSkipAtMillis) + " ms ago)");
      }

      SKIPPED_BY_PACKET.entrySet().stream()
         .sorted(Comparator.comparingLong((Map.Entry<String, AtomicLong> entry) -> entry.getValue().get()).reversed())
         .limit(8)
         .forEach(entry -> lines.add("  " + simple(entry.getKey()) + " x" + entry.getValue().get()
            + (LAST_REASON.containsKey(entry.getKey()) ? " (" + LAST_REASON.get(entry.getKey()) + ")" : "")));

      return lines;
   }

   /** Total skipped packets; also used by the plugin API so automation can react to a degrading world model. */
   public static long skippedCount() {
      return SKIPPED.get();
   }

   private static String simple(final String binaryName) {
      int dot = binaryName.lastIndexOf('.');
      return dot < 0 ? binaryName : binaryName.substring(dot + 1);
   }
}
