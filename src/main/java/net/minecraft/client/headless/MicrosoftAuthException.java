package net.minecraft.client.headless;

/**
 * Thrown when the Microsoft account flow cannot produce a usable Minecraft session: a network
 * failure, an expired/rejected device code, an account without Minecraft ownership, and so on.
 */
public final class MicrosoftAuthException extends Exception {
   public MicrosoftAuthException(final String message) {
      super(message);
   }

   public MicrosoftAuthException(final String message, final Throwable cause) {
      super(message, cause);
   }
}
