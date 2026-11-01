package eml.engine;

/** A patch that cannot be applied, which always means a bug on our side and must abort the launch. */
public class PatchException extends RuntimeException {
   public PatchException(final String message) {
      super(message);
   }

   public PatchException(final String message, final Throwable cause) {
      super(message, cause);
   }
}
