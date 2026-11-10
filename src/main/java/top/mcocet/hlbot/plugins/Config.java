package top.mcocet.hlbot.plugins;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * A minimal YAML configuration file, backed by {@code plugins/<name>/config.yml}. This is the framework
 * primitive that stops every plugin from re-wiring SnakeYAML by hand (and, in doing so, accidentally
 * writing outside the game directory): {@link Plugin#getConfig()} hands one out, {@link #save()} and
 * {@link #reload()} round-trip it, and dot-paths address nested keys.
 *
 * <p>Values keep their YAML types ({@code String}/{@code Number}/{@code Boolean}/{@code List}/{@code Map});
 * the typed getters coerce and fall back to a default when a key is absent or unparsable. Nothing here
 * throws at get/set time - a broken config file yields empty defaults and a logged warning, because a
 * plugin should degrade, not crash the bot, over its own settings.
 */
public final class Config {
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

   private final File file;
   private Map<String, Object> root;
   /** False until the file has actually been read once; {@link #save()} refuses to write before that. */
   private boolean loaded;

   Config(final File file) {
      this.file = file;
      this.root = new LinkedHashMap<>();
   }

   /** Reads the file if present; keeps current contents when it is missing or unreadable. */
   @SuppressWarnings("unchecked")
   void load() {
      this.loaded = true;

      if (!this.file.isFile()) {
         return;
      }

      try (InputStream stream = Files.newInputStream(this.file.toPath())) {
         Object parsed = new Yaml().load(stream);
         if (parsed instanceof Map) {
            this.root = (Map<String, Object>) parsed;
         }
      } catch (Exception e) {
         LOGGER.warn("Could not parse config {}", this.file.getPath(), e);
      }
   }

   public File getFile() {
      return this.file;
   }

   public boolean contains(final String path) {
      return get(path) != null;
   }

   public Object get(final String path) {
      String[] parts = path.split("\\.");
      Object current = this.root;

      for (String part : parts) {
         if (!(current instanceof Map)) {
            return null;
         }
         current = ((Map<?, ?>) current).get(part);
         if (current == null) {
            return null;
         }
      }

      return current;
   }

   /**
    * Sets a value at a dot-path, creating any missing intermediate maps. A {@code null} value removes the
    * key. Does not touch disk - call {@link #save()} to persist.
    */
   @SuppressWarnings("unchecked")
   public void set(final String path, final Object value) {
      String[] parts = path.split("\\.");

      if (parts.length == 1) {
         if (value == null) {
            this.root.remove(path);
         } else {
            this.root.put(path, value);
         }
         return;
      }

      Map<String, Object> cursor = this.root;
      for (int i = 0; i < parts.length - 1; i++) {
         Object next = cursor.get(parts[i]);
         if (!(next instanceof Map)) {
            next = new LinkedHashMap<String, Object>();
            cursor.put(parts[i], next);
         }
         cursor = (Map<String, Object>) next;
      }

      String leaf = parts[parts.length - 1];
      if (value == null) {
         cursor.remove(leaf);
      } else {
         cursor.put(leaf, value);
      }
   }

   public String getString(final String path) {
      return getString(path, null);
   }

   public String getString(final String path, final String fallback) {
      Object value = get(path);
      return value == null ? fallback : String.valueOf(value);
   }

   public boolean getBoolean(final String path, final boolean fallback) {
      Object value = get(path);
      if (value instanceof Boolean bool) {
         return bool;
      }
      return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
   }

   public int getInt(final String path, final int fallback) {
      Object value = get(path);
      if (value instanceof Number number) {
         return number.intValue();
      }
      try {
         return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
      } catch (NumberFormatException e) {
         return fallback;
      }
   }

   public long getLong(final String path, final long fallback) {
      Object value = get(path);
      if (value instanceof Number number) {
         return number.longValue();
      }
      try {
         return value == null ? fallback : Long.parseLong(String.valueOf(value).trim());
      } catch (NumberFormatException e) {
         return fallback;
      }
   }

   public double getDouble(final String path, final double fallback) {
      Object value = get(path);
      if (value instanceof Number number) {
         return number.doubleValue();
      }
      try {
         return value == null ? fallback : Double.parseDouble(String.valueOf(value).trim());
      } catch (NumberFormatException e) {
         return fallback;
      }
   }

   @SuppressWarnings("unchecked")
   public List<Object> getList(final String path) {
      Object value = get(path);
      if (value instanceof List) {
         return (List<Object>) value;
      }
      List<Object> single = new ArrayList<>();
      if (value != null) {
         single.add(value);
      }
      return single;
   }

   /** The whole tree, for read-oriented iteration. Mutate through {@link #set} then {@link #save}. */
   public Map<String, Object> getValues() {
      return this.root;
   }

   /**
    * Writes the in-memory tree back to the file.
    *
    * <p>If the file was never read, it is read first: writing a tree that does not contain what is on disk
    * would silently erase the user's settings the first time any command touches a config.
    */
   public void save() {
      if (!this.loaded) {
         load();
      }

      try {
         File parent = this.file.getParentFile();
         if (parent != null) {
            Files.createDirectories(parent.toPath());
         }

         try (Writer writer = Files.newBufferedWriter(this.file.toPath(), StandardCharsets.UTF_8)) {
            new Yaml().dump(this.root, writer);
         }
      } catch (IOException e) {
         LOGGER.warn("Could not save config {}", this.file.getPath(), e);
      }
   }

   /** Re-reads the file from disk, discarding unsaved in-memory changes. */
   public void reload() {
      this.root = new LinkedHashMap<>();
      load();
   }

   /**
    * Seeds the config file from a {@code config.yml} bundled inside the plugin jar, only when the file is
    * not already on disk (so user edits survive restarts). Call once in {@link Plugin#onEnable()}.
    *
    * @return true when a fresh copy was written
    */
   public boolean saveDefaultFromJar(final Plugin owner) {
      if (this.file.isFile()) {
         return false;
      }

      try (InputStream stream = owner.getClass().getClassLoader().getResourceAsStream("config.yml")) {
         if (stream == null) {
            return false;
         }

         Files.createDirectories(this.file.getParentFile().toPath());
         Files.copy(stream, this.file.toPath());
         reload();
         return true;
      } catch (IOException e) {
         LOGGER.warn("Could not extract default config for {}", owner.getName(), e);
         return false;
      }
   }
}
