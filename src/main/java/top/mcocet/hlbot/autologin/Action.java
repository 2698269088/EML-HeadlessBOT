package top.mcocet.hlbot.autologin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One rule action. The action kind is decided by the single key present in its YAML map:
 *
 * <pre>
 *   command        = "login {{password}}"
 *   chat           = "hello"
 *   stage          = "LOGGED_IN"
 *   setVariable    = { key = "value", ... }
 *   log            = "text"
 *   selectSlot     = 0                 # 0-8
 *   useItem        = "MAIN_HAND"
 *   closeContainer = 0
 *   containerButton = { containerId = 0, button = 1 }
 *   containerClick  = { slot = 11, button = 0 }
 *   dialogInput     = { key = password, value = "{{password}}" }
 *   dialogClick     = "0"
 *   dialogCancel    = true
 * </pre>
 *
 * String fields support {@code {{name}}} interpolation against session variables and captures.
 */
final class Action {
   enum Kind {
      COMMAND, CHAT, STAGE, SET_VARIABLE, LOG, SELECT_SLOT, USE_ITEM, CLOSE_CONTAINER, CONTAINER_BUTTON, CONTAINER_CLICK,
      DIALOG_INPUT, DIALOG_CLICK, DIALOG_CANCEL, NONE
   }

   private final Kind kind;
   private final String value;
   private final Map<String, String> variables;
   private final Map<String, Object> args;

   private Action(final Kind kind, final String value, final Map<String, String> variables, final Map<String, Object> args) {
      this.kind = kind;
      this.value = value;
      this.variables = variables;
      this.args = args;
   }

   static Action from(final Object raw) {
      Map<String, Object> m = YamlUtil.map(raw);

      if (m.containsKey("command")) {
         return new Action(Kind.COMMAND, YamlUtil.text(m.get("command"), ""), null, null);
      }

      if (m.containsKey("chat")) {
         return new Action(Kind.CHAT, YamlUtil.text(m.get("chat"), ""), null, null);
      }

      if (m.containsKey("stage")) {
         return new Action(Kind.STAGE, YamlUtil.text(m.get("stage"), ""), null, null);
      }

      if (m.containsKey("log")) {
         return new Action(Kind.LOG, YamlUtil.text(m.get("log"), ""), null, null);
      }

      if (m.containsKey("setVariable")) {
         Map<String, String> variables = new LinkedHashMap<>();
         for (Map.Entry<String, Object> entry : YamlUtil.map(m.get("setVariable")).entrySet()) {
            variables.put(entry.getKey(), YamlUtil.text(entry.getValue(), ""));
         }

         return new Action(Kind.SET_VARIABLE, null, variables, null);
      }

      if (m.containsKey("selectSlot")) {
         Map<String, Object> args = new LinkedHashMap<>();
         args.put("slot", numberAt(m.get("selectSlot"), "slot", 0));
         return new Action(Kind.SELECT_SLOT, null, null, args);
      }

      if (m.containsKey("useItem")) {
         Map<String, Object> args = new LinkedHashMap<>();
         args.put("hand", handOf(m.get("useItem")));
         return new Action(Kind.USE_ITEM, null, null, args);
      }

      if (m.containsKey("closeContainer")) {
         Map<String, Object> args = new LinkedHashMap<>();
         args.put("containerId", numberAt(m.get("closeContainer"), "containerId", 0));
         return new Action(Kind.CLOSE_CONTAINER, null, null, args);
      }

      if (m.containsKey("containerButton")) {
         Map<String, Object> sub = YamlUtil.map(m.get("containerButton"));
         Map<String, Object> args = new LinkedHashMap<>();
         if (sub.containsKey("containerId")) {
            args.put("containerId", YamlUtil.integer(sub.get("containerId"), 0));
         }

         args.put("button", YamlUtil.integer(sub.get("button"), 0));
         return new Action(Kind.CONTAINER_BUTTON, null, null, args);
      }

      if (m.containsKey("containerClick")) {
         Map<String, Object> sub = YamlUtil.map(m.get("containerClick"));
         Map<String, Object> args = new LinkedHashMap<>();
         if (sub.containsKey("containerId")) {
            args.put("containerId", YamlUtil.integer(sub.get("containerId"), 0));
         }

         args.put("slot", sub.getOrDefault("slot", 0));
         args.put("button", sub.getOrDefault("button", 0));
         return new Action(Kind.CONTAINER_CLICK, null, null, args);
      }

      if (m.containsKey("dialogInput")) {
         Map<String, Object> sub = YamlUtil.map(m.get("dialogInput"));
         Map<String, Object> args = new LinkedHashMap<>();
         args.put("key", YamlUtil.text(sub.get("key"), ""));
         return new Action(Kind.DIALOG_INPUT, YamlUtil.text(sub.get("value"), ""), null, args);
      }

      if (m.containsKey("dialogClick")) {
         return new Action(Kind.DIALOG_CLICK, YamlUtil.text(m.get("dialogClick"), ""), null, null);
      }

      if (m.containsKey("dialogCancel")) {
         return new Action(Kind.DIALOG_CANCEL, null, null, null);
      }

      return new Action(Kind.NONE, null, null, null);
   }

   private static int numberAt(final Object value, final String field, final int fallback) {
      if (value instanceof Number number) {
         return number.intValue();
      }

      return YamlUtil.integer(YamlUtil.map(value).get(field), fallback);
   }

   private static String handOf(final Object value) {
      if (value instanceof String hand) {
         return hand;
      }

      Object hand = YamlUtil.map(value).get("hand");
      return hand == null ? "MAIN_HAND" : String.valueOf(hand);
   }

   Kind kind() {
      return this.kind;
   }

   String value() {
      return this.value;
   }

   Map<String, String> variables() {
      return this.variables;
   }

   Map<String, Object> args() {
      return this.args;
   }

   int argInt(final String key, final int fallback) {
      return this.argInt(key, fallback, Map.of());
   }

   int argInt(final String key, final int fallback, final Map<String, String> vars) {
      if (this.args == null) {
         return fallback;
      }

      Object value = this.args.get(key);
      if (value instanceof Number number) {
         return number.intValue();
      }

      return value == null ? fallback : YamlUtil.integer(ActionExecutor.interpolate(String.valueOf(value), vars), fallback);
   }

   boolean hasArg(final String key) {
      return this.args != null && this.args.containsKey(key);
   }

   String argString(final String key, final String fallback) {
      if (this.args == null) {
         return fallback;
      }

      Object value = this.args.get(key);
      return value == null ? fallback : String.valueOf(value);
   }
}
