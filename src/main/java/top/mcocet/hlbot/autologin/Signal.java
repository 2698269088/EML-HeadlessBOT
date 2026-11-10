package top.mcocet.hlbot.autologin;

/**
 * A normalised signal fed to the AutoLogin rule engine. Chat, connection and container events are all
 * translated into this shape before any rule runs, so the engine never depends on a concrete event type.
 */
public final class Signal {
   public enum Type {
      SYSTEM_CHAT,
      PUBLIC_CHAT,
      PRIVATE_CHAT,
      OVERLAY,
      TITLE,
      SERVER_CHANGE,
      DISCONNECT,
      WINDOW_OPEN,
      WINDOW_SLOT,
      DIALOG_OPEN,
      DIALOG_CLOSE
   }

   private final Type type;
   private final String text;
   private final String sender;
   private final boolean overlay;
   private final Integer containerId;
   private final Integer slot;

   public Signal(final Type type, final String text) {
      this(type, text, null, false, null);
   }

   public Signal(final Type type, final String text, final String sender, final boolean overlay) {
      this(type, text, sender, overlay, null);
   }

   public Signal(final Type type, final String text, final String sender, final boolean overlay, final Integer containerId) {
      this(type, text, sender, overlay, containerId, null);
   }

   public Signal(final Type type, final String text, final String sender, final boolean overlay,
                 final Integer containerId, final Integer slot) {
      this.type = type;
      this.text = text == null ? "" : text;
      this.sender = sender;
      this.overlay = overlay;
      this.containerId = containerId;
      this.slot = slot;
   }

   public Type type() {
      return this.type;
   }

   public String text() {
      return this.text;
   }

   public String sender() {
      return this.sender;
   }

   public boolean overlay() {
      return this.overlay;
   }

   public Integer containerId() {
      return this.containerId;
   }

   public Integer slot() {
      return this.slot;
   }

   @Override
   public String toString() {
      return "Signal{type=" + this.type + ", overlay=" + this.overlay
         + (this.sender != null ? ", sender=" + this.sender : "")
         + (this.containerId != null ? ", containerId=" + this.containerId : "")
         + (this.slot != null ? ", slot=" + this.slot : "")
         + ", text='" + this.text + "'}";
   }
}
