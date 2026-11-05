package net.minecraft.client.headless;

/**
 * How much of the world the bot asks the server to stream.
 *
 * <p>Two modes, because they answer different questions:
 *
 * <ul>
 *   <li>{@code auto} (the default) - follow the server. Nothing is forced at startup, and once the login
 *       packet names the server's radius ({@code ClientboundLoginPacket.chunkRadius}) the client adopts it
 *       and re-announces itself, so the loaded world matches what the server is willing to send. This needs
 *       no knowledge of the deployment, which is why it is the default;</li>
 *   <li>a number 2..32 - ask for that radius. Two ceilings still apply above it: vanilla caps the effective
 *       distance at the server's own view distance, and the client option itself accepts 2..16 on a normal
 *       (non-demo) launch, silently keeping its previous value for anything higher. The bot therefore reads
 *       the option back after setting it and reports what it actually got.</li>
 * </ul>
 *
 * <p>{@code auto} is not free: a server configured for 16 chunks sends 16, and a headless client pays for
 * every chunk it keeps. Cap it with a number when memory or bandwidth is the binding constraint.
 */
public record ViewDistance(boolean auto, int chunks) {
   /** Vanilla refuses to report less than 2 chunks. */
   public static final int MIN = 2;

   /**
    * The protocol's widest reported radius, used to clamp what a user may write. It is not the ceiling that
    * wins in practice - the client option is narrower (see the class comment) - but clamping to it keeps the
    * configured number inside the one range every Minecraft version has accepted.
    */
   public static final int MAX = 32;

   /**
    * Follow the server. {@link #chunks()} carries {@link #MAX} so that an accidental read can only ask for
    * too much (which vanilla caps) rather than too little, but no fixed request is made in this mode.
    */
   public static final ViewDistance AUTO = new ViewDistance(true, MAX);

   /**
    * Parses {@code auto} / {@code server} / blank as {@link #AUTO}, and anything else as a chunk count clamped
    * into {@link #MIN}..{@link #MAX}. An unusable value falls back to {@code auto} instead of failing: a typo
    * in an optional setting must not keep the bot out of a server.
    */
   public static ViewDistance parse(final String raw) {
      if (raw == null || raw.isBlank()) {
         return AUTO;
      }

      String value = raw.trim();
      if ("auto".equalsIgnoreCase(value) || "server".equalsIgnoreCase(value)) {
         return AUTO;
      }

      try {
         return fixed(Integer.parseInt(value));
      } catch (NumberFormatException e) {
         return AUTO;
      }
   }

   public static ViewDistance fixed(final int chunks) {
      return new ViewDistance(false, Math.max(MIN, Math.min(MAX, chunks)));
   }

   /** The value written back to {@code bot.yml}: {@code auto} or the chunk count. */
   public String render() {
      return this.auto ? "auto" : String.valueOf(this.chunks);
   }

   /** One-line description for {@code status} and the launch log. */
   public String describe() {
      return this.auto ? "auto (follow the server)" : "fixed at " + this.chunks + " chunk(s)";
   }
}
