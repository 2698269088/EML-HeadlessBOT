package top.mcocet.hlbot.nav;

/**
 * Console output for the navigator, expressed as localisation keys plus arguments.
 *
 * <p>The navigator does not import {@code net.minecraft} - not even the console classes - so it cannot look
 * a message up itself. It emits the key and the arguments; whoever installs it resolves them.
 */
public interface NavOutput {
   /** One ordinary line; {@code key} is a {@code .lang} key such as {@code nav.started}. */
   void line(String key, Object... args);

   /** One error line (bright red in the console). */
   void error(String key, Object... args);
}
