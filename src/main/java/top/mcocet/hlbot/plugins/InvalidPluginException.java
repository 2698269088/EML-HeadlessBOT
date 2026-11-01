package top.mcocet.hlbot.plugins;

/** Thrown when a plugin jar is malformed or fails to load. */
public class InvalidPluginException extends Exception {
   public InvalidPluginException(final String message) {
      super(message);
   }

   public InvalidPluginException(final String message, final Throwable cause) {
      super(message, cause);
   }
}
