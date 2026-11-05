package top.mcocet.hlbot.autologin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Built-in login-flow templates. A template is just a named list of rules whose actions reference
 * {@code {{password}}} / {@code {{email}}}, so selecting one in {@code autologin.yml} is enough for the
 * common register / login / join cases. Templates are merged with user {@code rules} before sorting.
 */
final class TemplateLibrary {
   private static final Logger LOGGER = LoggerFactory.getLogger("AutoLogin");

   private static final Set<String> NAMES = new LinkedHashSet<>(
      List.of("authme", "register-then-login", "join-login-fallback"));

   private static final String AUTHME = """
      rules:
        - id: tpl-authme-login
          priority: 10
          cooldownMs: 4000
          notStage: [LOGGED_IN]
          when:
            types: [SYSTEM_CHAT, PUBLIC_CHAT]
            contains: ["Please log in", "/login"]
            notContains: ["logged in", "successfully", "registered"]
          actions:
            - command: "login {{password}}"
        - id: tpl-authme-register
          priority: 20
          once: true
          when:
            types: [SYSTEM_CHAT, PUBLIC_CHAT]
            contains: ["Unknown account", "not registered", "/register"]
          actions:
            - command: "register {{password}} {{password}}"
        - id: tpl-authme-done
          priority: 5
          when:
            types: [SYSTEM_CHAT]
            regex: "(?i)(logged in|authentication successful|you are now logged in)"
          actions:
            - stage: LOGGED_IN
            - log: "authme template: logged in"
      """;

   private static final String REGISTER_THEN_LOGIN = """
      rules:
        - id: tpl-register-then-login-register
          priority: 20
          once: true
          when:
            types: [SYSTEM_CHAT, PUBLIC_CHAT]
            contains: ["Unknown account", "not registered", "/register"]
          actions:
            - command: "register {{email}} {{password}} {{password}}"
        - id: tpl-register-then-login-login
          priority: 10
          cooldownMs: 4000
          notStage: [LOGGED_IN]
          when:
            types: [SYSTEM_CHAT, PUBLIC_CHAT]
            contains: ["Please log in", "/login"]
            notContains: ["logged in", "registered"]
          actions:
            - command: "login {{password}}"
        - id: tpl-register-then-login-done
          priority: 5
          when:
            types: [SYSTEM_CHAT]
            regex: "(?i)(logged in|registered successfully|you are now logged in)"
          actions:
            - stage: LOGGED_IN
      """;

   private static final String JOIN_LOGIN_FALLBACK = """
      rules:
        - id: tpl-join-login-fallback
          priority: 60
          cooldownMs: 8000
          notStage: [LOGGED_IN]
          when:
            types: [SERVER_CHANGE]
            equals: GAME
          actions:
            - command: "login {{password}}"
      """;

   private TemplateLibrary() {
   }

   static Set<String> names() {
      return new LinkedHashSet<>(NAMES);
   }

   static List<Rule> expand(final String name) {
      String yaml = switch (name == null ? "" : name.trim()) {
         case "authme" -> AUTHME;
         case "register-then-login" -> REGISTER_THEN_LOGIN;
         case "join-login-fallback" -> JOIN_LOGIN_FALLBACK;
         default -> null;
      };

      if (yaml == null) {
         LOGGER.warn("[AutoLogin] unknown template '{}' (available {})", name, NAMES);
         return List.of();
      }

      try {
         Object root = new Yaml().load(yaml);
         Map<String, Object> map = YamlUtil.map(root);
         List<Rule> rules = new ArrayList<>();
         Object items = map.get("rules");
         if (items instanceof List<?> list) {
            int index = 0;
            for (Object item : list) {
               rules.add(Rule.from(item, index++));
            }
         }

         return rules;
      } catch (Exception failed) {
         LOGGER.error("[AutoLogin] template '{}' failed to parse", name, failed);
         return List.of();
      }
   }
}
