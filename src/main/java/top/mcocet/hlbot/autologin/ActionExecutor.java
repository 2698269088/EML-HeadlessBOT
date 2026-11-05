package top.mcocet.hlbot.autologin;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.hlbot.plugins.BotApi;

/**
 * Translates parsed actions into bot calls. Command and chat go through {@link BotApi#sendCommand} /
 * {@link BotApi#sendChat} (safe from any thread); inventory/hotbar actions are posted to the client thread
 * where world state is legal to touch.
 */
final class ActionExecutor {
   private static final Logger LOGGER = LoggerFactory.getLogger("AutoLogin");
   private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

   private final BotApi api;

   ActionExecutor(final BotApi api) {
      this.api = api;
   }

   void execute(final Action action, final Map<String, String> vars, final RuleContext ctx, final boolean debug) {
      switch (action.kind()) {
         case COMMAND -> {
            String command = stripSlash(interpolate(action.value(), vars)).trim();
            if (!command.isEmpty()) {
               this.api.sendCommand(command);
               debugLog(debug, "action -> command: /{}", redactSecrets(command, vars));
            }
         }
         case CHAT -> {
            String message = interpolate(action.value(), vars);
            if (!message.isBlank()) {
               this.api.sendChat(message);
               debugLog(debug, "action -> chat: {}", redactSecrets(message, vars));
            }
         }
         case STAGE -> {
            String stage = interpolate(action.value(), vars).trim();
            ctx.setStage(stage);
            debugLog(debug, "action -> stage: {}", stage);
         }
         case SET_VARIABLE -> {
            for (Map.Entry<String, String> entry : action.variables().entrySet()) {
               String value = interpolate(entry.getValue(), vars);
               ctx.setVariable(entry.getKey(), value);
               debugLog(debug, "action -> setVariable: {}={}", entry.getKey(),
                  "password".equalsIgnoreCase(entry.getKey()) ? "******" : value);
            }
         }
         case LOG -> LOGGER.info("[AutoLogin] {}", interpolate(action.value(), vars));
         case SELECT_SLOT -> {
            int slot = clampSlot(action.argInt("slot", 0));
            this.api.runOnClient(() -> {
               boolean selected = this.api.controls().selectSlot(slot);
               debugLog(debug, "action -> selectSlot: {}, result={}", slot, selected);
            });
         }
         case USE_ITEM -> this.api.runOnClient(() -> {
            boolean used = this.api.player() != null && this.api.controls().useItem();
            debugLog(debug, "action -> useItem: MAIN_HAND, result={}", used);
         });
         case CLOSE_CONTAINER -> this.api.runOnClient(() -> {
            boolean closed = this.api.inventory().close();
            debugLog(debug, "action -> closeContainer, result={}", closed);
         });
         case CONTAINER_BUTTON -> {
            int button = action.argInt("button", 0);
            this.api.runOnClient(() -> {
               boolean clicked = this.api.inventory().button(button);
               debugLog(debug, "action -> containerButton: {}, result={}", button, clicked);
            });
         }
         case CONTAINER_CLICK -> {
            int slot = action.argInt("slot", 0, vars);
            int button = action.argInt("button", 0, vars);
            this.api.runOnClient(() -> {
               boolean clicked = button == 1
                  ? this.api.inventory().rightClick(slot)
                  : this.api.inventory().leftClick(slot);
               debugLog(debug, "action -> containerClick: slot={}, button={}, result={}", slot, button, clicked);
            });
         }
         case NONE -> debugLog(debug, "action -> no-op (missing or unknown action)");
      }
   }

   private static void debugLog(final boolean debug, final String format, final Object... args) {
      if (debug) {
         LOGGER.info("[AutoLogin][debug] " + format, args);
      }
   }

   private static String redactSecrets(final String text, final Map<String, String> vars) {
      String password = vars.get("password");
      return password == null || password.isBlank() ? text : text.replace(password, "******");
   }

   /** Replaces {@code {{name}}} with the corresponding variable; unknown names stay as they are. */
   static String interpolate(final String template, final Map<String, String> vars) {
      if (template == null || template.isEmpty() || vars == null) {
         return template;
      }

      Matcher matcher = VAR.matcher(template);
      StringBuilder out = new StringBuilder();
      while (matcher.find()) {
         String key = matcher.group(1);
         String replacement = vars.get(key);
         matcher.appendReplacement(out, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group(0)));
      }

      matcher.appendTail(out);
      return out.toString();
   }

   private static String stripSlash(final String value) {
      return value.startsWith("/") ? value.substring(1) : value;
   }

   private static int clampSlot(final int slot) {
      return Math.max(0, Math.min(8, slot));
   }
}
