package eml.hooks;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;

/**
 * Diagnostic targets for the {@code Wrap} rules on {@link Connection}.
 *
 * <p>These exist because the headless client's most damaging failure mode is silent: an exception thrown
 * while a login/configuration packet is handled ends up inside a {@code CompletableFuture}, the channel is
 * closed, and the server only ever reports a bare "Disconnected". The two places every such path must pass
 * are {@code Connection.exceptionCaught} and {@code Connection.disconnect}, and both are wrapped here. An
 * exception is always logged; disconnects are logged only while {@code debug.connect} is on, and a deliberate
 * disconnect or shutdown not at all - normal teardown is not a failure to diagnose.
 *
 * <p>Every hook starts with a switch test and swallows its own failures: a diagnostic that can throw into
 * the network pipeline would turn a logged failure into a crash.
 */
public final class BotDebug {
   /** How many caller frames to record when tracing who closed a connection. */
   private static final int CALLER_FRAMES = 4;

   private BotDebug() {
   }

   /**
    * Observation hook for {@code Connection.channelRead0(ChannelHandlerContext, Packet)}.
    *
    * <p>The netty parameter types are deliberately erased to {@code Object} in the generated call: the bot
    * compiles against the client jar only, and netty is not a declared dependency, so a hook signature
    * naming {@code ChannelHandlerContext} could not be written at all. Passing a reference where the
    * descriptor says {@code java/lang/Object} is valid without a cast.
    */
   public static void onInboundPacket(final Connection self, final Object context, final Object packet) {
      if (!eml.util.Debug.packets() || packet == null) {
         return;
      }

      trace("in  " + packet.getClass().getSimpleName() + "  [" + state(self) + "]");
   }

   /**
    * Observation hook for {@code Connection.exceptionCaught(ChannelHandlerContext, Throwable)}: the one
    * place where a packet-handling failure is still an ordinary throwable with a full stack. Logged at WARN
    * whether or not debugging is on, because losing it is what made the login failure undiagnosable.
    */
   public static void onConnectionException(final Connection self, final Object context, final Object cause) {
      if (!(cause instanceof Throwable error)) {
         return;
      }

      if (eml.util.Debug.errors()) {
         BotRuntime.log().warn("[debug] connection {} raised {}", state(self), cause.getClass().getSimpleName(), error);
      } else {
         // Still never silent, but without the (potentially large) trace.
         BotRuntime.log().warn("[debug] connection {} raised {}: {}", state(self), error.getClass().getName(), String.valueOf(error.getMessage()));
      }
   }

   /** Observation hook for {@code Connection.disconnect(Component)}: one summary line, no caller frames. */
   public static void onDisconnect(final Connection self, final Component reason) {
      // The reason also feeds DisconnectEvent, which is announced from the client thread rather than here.
      BotRuntime.noteDropReason(reason);

      if (quiet()) {
         return;
      }

      BotRuntime.log().info("[debug] disconnect ({}): {}", state(self), reason == null ? "<none>" : reason.getString());
   }

   /** Observation hook for {@code Connection.disconnect(DisconnectionDetails)}: the full line, plus who asked. */
   public static void onDisconnectDetails(final Connection self, final DisconnectionDetails details) {
      if (details == null) {
         return;
      }

      Component reason = details.reason();
      BotRuntime.noteDropReason(reason);

      if (quiet()) {
         return;
      }

      BotRuntime.log().info("[debug] disconnect ({}): {} (report {})", state(self),
         reason == null ? "<none>" : reason.getString(), details.report().map(Object::toString).orElse("<none>"));
      traceCallers("disconnect");
   }

   /**
    * A deliberate disconnect or a shutdown is normal operation, not a failure to diagnose, so it logs
    * nothing. Unexpected drops are logged only while {@code debug.connect} is on: the two disconnect
    * overloads share one summary plus one caller trace between them, instead of each printing its own
    * reason and frames.
    */
   private static boolean quiet() {
      return BotRuntime.userDisconnectRequested() || BotRuntime.shutdownRequested() || !eml.util.Debug.connect();
   }

   private static String state(final Connection self) {
      try {
         return (self.isConnected() ? "open" : "closed") + "@" + self.getRemoteAddress();
      } catch (Throwable unavailable) {
         return "unknown";
      }
   }

   /**
    * Records who asked for the disconnect. This is the difference between "the server kicked us" and "our
    * own code tore the channel down", which is otherwise invisible when the trigger is a swallowed
    * exception.
    */
   private static void traceCallers(final String marker) {
      try {
         String trace = StackWalker.getInstance().walk(stream -> stream
            .filter(frame -> {
               String className = frame.getClassName();
               return !className.startsWith("eml.")
                  && !className.startsWith("io.netty.")
                  && !className.equals(BotDebug.class.getName());
            })
            .limit(CALLER_FRAMES)
            .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber())
            .collect(java.util.stream.Collectors.joining(" <- ", "called by: ", "")));
   
         if (trace.length() > "called by: ".length()) {
            BotRuntime.log().info("[debug] {} {}", marker, trace);
         }
      } catch (Throwable unavailable) {
         // Diagnostics never break the path they observe.
      }
   }

   private static void trace(final String message) {
      try {
         BotRuntime.log().info("[debug] {}", message);
      } catch (Throwable unavailable) {
         // ignore
      }
   }
}
