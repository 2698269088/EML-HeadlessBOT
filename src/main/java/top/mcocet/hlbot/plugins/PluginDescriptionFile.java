package top.mcocet.hlbot.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of a plugin's {@code plugin.yml}. Required keys: {@code name},
 * {@code main}. Optional: {@code version}, {@code description}, {@code apiVersion},
 * {@code author}/{@code authors}, {@code depend}/{@code depends} (hard: must load before this one),
 * {@code softdepend}/{@code softdepends} (used first if present; absence is only logged).
 */
public final class PluginDescriptionFile {
   private final String name;
   private final String main;
   private final String version;
   private final String description;
   private final String apiVersion;
   private final List<String> authors;
   private final List<String> depend;
   private final List<String> softDepend;

   private PluginDescriptionFile(
      final String name,
      final String main,
      final String version,
      final String description,
      final String apiVersion,
      final List<String> authors,
      final List<String> depend,
      final List<String> softDepend
   ) {
      this.name = name;
      this.main = main;
      this.version = version;
      this.description = description;
      this.apiVersion = apiVersion;
      this.authors = authors;
      this.depend = depend;
      this.softDepend = softDepend;
   }

   @SuppressWarnings("unchecked")
   public static PluginDescriptionFile fromYaml(final Map<String, Object> map) throws InvalidPluginException {
      if (map == null) {
         throw new InvalidPluginException("plugin.yml is empty");
      }

      Object name = map.get("name");
      Object main = map.get("main");
      if (name == null || main == null) {
         throw new InvalidPluginException("plugin.yml must define 'name' and 'main'");
      }

      List<String> authors = new ArrayList<>();
      Object author = map.get("author");
      Object authorList = map.get("authors");
      if (author != null) {
         authors.add(String.valueOf(author));
      }

      if (authorList instanceof List) {
         for (Object entry : (List<Object>) authorList) {
            authors.add(String.valueOf(entry));
         }
      }

      List<String> depend = new ArrayList<>();
      collectNames(map.getOrDefault("depend", map.get("depends")), depend);

      List<String> softDepend = new ArrayList<>();
      collectNames(map.getOrDefault("softdepend", map.get("softdepends")), softDepend);

      return new PluginDescriptionFile(
         String.valueOf(name),
         String.valueOf(main),
         String.valueOf(map.getOrDefault("version", "1.0.0")),
         map.get("description") != null ? String.valueOf(map.get("description")) : "",
         map.get("apiVersion") != null ? String.valueOf(map.get("apiVersion")) : "",
         List.copyOf(authors),
         List.copyOf(depend),
         List.copyOf(softDepend)
      );
   }

   /** Accepts a single string or a list of them, as YAML readers commonly return both shapes. */
   private static void collectNames(final Object value, final List<String> out) {
      if (value instanceof List) {
         for (Object entry : (List<Object>) value) {
            if (entry != null) {
               out.add(String.valueOf(entry));
            }
         }
      } else if (value != null) {
         out.add(String.valueOf(value));
      }
   }

   public String getName() {
      return this.name;
   }

   /** Fully qualified class name of the {@link Plugin} subclass. */
   public String getMain() {
      return this.main;
   }

   public String getVersion() {
      return this.version;
   }

   public String getDescription() {
      return this.description;
   }

   public String getApiVersion() {
      return this.apiVersion;
   }

   public List<String> getAuthors() {
      return this.authors;
   }

   /** Names of plugins that must be enabled before this one. */
   public List<String> getDepend() {
      return this.depend;
   }

   /** Names of plugins used if present, but not required. */
   public List<String> getSoftDepend() {
      return this.softDepend;
   }

   public String getFullName() {
      return this.name + " v" + this.version;
   }
}
