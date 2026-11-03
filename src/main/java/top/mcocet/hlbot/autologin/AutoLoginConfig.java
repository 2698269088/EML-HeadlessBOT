package top.mcocet.hlbot.autologin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Parsed {@code autologin.yml}. Top-level keys mirror XinConnect: {@code enabled}, {@code debug},
 * {@code credentials}, {@code variables}, {@code templates} and {@code rules}. The {@code password}
 * credential may be left blank here to fall back to {@code bot.yml}'s {@code password}. Entries under
 * {@code servers} replace the templates/rules for one exact host or host:port and overlay the other settings.
 */
final class AutoLoginConfig {
   private final boolean enabled;
   private final boolean debug;
   private final Map<String, String> credentials;
   private final Map<String, String> variables;
   private final List<Rule> rules;
   private final List<ServerProfile> servers;

   private AutoLoginConfig(final boolean enabled, final boolean debug, final Map<String, String> credentials,
                           final Map<String, String> variables, final List<Rule> rules,
                           final List<ServerProfile> servers) {
      this.enabled = enabled;
      this.debug = debug;
      this.credentials = credentials;
      this.variables = variables;
      this.rules = rules;
      this.servers = servers;
   }

   static AutoLoginConfig load(final File file) {
      if (!file.isFile()) {
         return empty();
      }

      try (InputStream stream = Files.newInputStream(file.toPath())) {
         Object root = new Yaml().load(stream);
         Map<String, Object> map = YamlUtil.map(root);

         boolean enabled = map.containsKey("enabled") ? YamlUtil.bool(map.get("enabled"), true) : true;
         boolean debug = YamlUtil.bool(map.get("debug"), false);
         Map<String, String> credentials = flat(map.get("credentials"));
         Map<String, String> variables = flat(map.get("variables"));
         List<Rule> rules = parseRules(map);

         List<ServerProfile> servers = new ArrayList<>();
         for (Map.Entry<String, Object> entry : YamlUtil.map(map.get("servers")).entrySet()) {
            Map<String, Object> profile = YamlUtil.map(entry.getValue());
            servers.add(new ServerProfile(
               entry.getKey(),
               profile.containsKey("enabled") ? YamlUtil.bool(profile.get("enabled"), true) : null,
               profile.containsKey("debug") ? YamlUtil.bool(profile.get("debug"), false) : null,
               flat(profile.get("variables")),
               parseRules(profile)
            ));
         }

         return new AutoLoginConfig(enabled, debug, credentials, variables, rules, servers);
      } catch (Exception failed) {
         throw new IllegalStateException("cannot parse " + file.getName() + ": " + failed.getMessage(), failed);
      }
   }

   static AutoLoginConfig empty() {
      return new AutoLoginConfig(false, false, Map.of(), Map.of(), List.of(), List.of());
   }

   private static List<Rule> parseRules(final Map<String, Object> map) {
      List<Rule> rules = new ArrayList<>();
      for (String template : YamlUtil.strings(map.get("templates"))) {
         rules.addAll(TemplateLibrary.expand(template));
      }

      Object custom = map.get("rules");
      if (custom instanceof List<?> list) {
         int index = 0;
         for (Object item : list) {
            rules.add(Rule.from(item, index++));
         }
      }

      return rules;
   }

   private static Map<String, String> flat(final Object value) {
      Map<String, String> out = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : YamlUtil.map(value).entrySet()) {
         out.put(entry.getKey(), YamlUtil.text(entry.getValue(), ""));
      }

      return out;
   }

   Selection select(final String host, final int port) {
      ServerProfile selected = null;
      for (ServerProfile profile : this.servers) {
         if (profile.matches(host, port)) {
            selected = profile;
            break;
         }
      }

      if (selected == null) {
         return new Selection(this.enabled, this.debug, this.credentials, this.variables, this.rules, "default");
      }

      Map<String, String> selectedVariables = new LinkedHashMap<>(this.variables);
      selectedVariables.putAll(selected.variables());

      return new Selection(
         selected.enabled() == null ? this.enabled : selected.enabled(),
         selected.debug() == null ? this.debug : selected.debug(),
         this.credentials,
         selectedVariables,
         selected.rules(),
         selected.address()
      );
   }

   boolean enabled() {
      return this.enabled;
   }

   boolean debug() {
      return this.debug;
   }

   Map<String, String> credentials() {
      return this.credentials;
   }

   Map<String, String> variables() {
      return this.variables;
   }

   List<Rule> rules() {
      return this.rules;
   }

   record Selection(boolean enabled, boolean debug, Map<String, String> credentials,
                    Map<String, String> variables, List<Rule> rules, String profile) {
   }

   private record ServerProfile(String address, Boolean enabled, Boolean debug,
                                Map<String, String> variables, List<Rule> rules) {
      boolean matches(final String host, final int port) {
         String normalizedHost = normalizeHost(host);
         if (normalizedHost.isEmpty()) {
            return false;
         }

         String expected = this.address.trim().toLowerCase(Locale.ROOT);
         if (expected.equals(normalizedHost) || expected.equals("[" + normalizedHost + "]")) {
            return true;
         }

         return port > 0 && (expected.equals(normalizedHost + ":" + port)
            || expected.equals("[" + normalizedHost + "]:" + port));
      }
   }

   private static String normalizeHost(final String host) {
      String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
      if (value.length() > 1 && value.startsWith("[") && value.endsWith("]")) {
         return value.substring(1, value.length() - 1);
      }

      return value;
   }
}
