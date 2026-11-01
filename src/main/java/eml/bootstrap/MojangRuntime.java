package eml.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import eml.util.LaunchLog;

/**
 * Resolves the Mojang runtime (client jar + its library classpath) inside the bot's own directory,
 * downloading it from the official endpoints when it is not already present.
 *
 * <p>Everything read or written lives inside the game directory. Nothing outside it is ever scanned or
 * reused: "where a launcher stores its files" is a platform assumption, and this code has to behave the same
 * on any JVM host. The only exception is a path the operator names explicitly ({@code -Deml.clientJar}).
 * <pre>
 *   &lt;gameDir&gt;/versions/&lt;version&gt;/&lt;version&gt;.jar     client jar (mojmap, never modified on disk)
 *   &lt;gameDir&gt;/libraries/&lt;group&gt;/&lt;artifact&gt;/...    library jars
 * </pre>
 *
 * <p>Only {@code download.client} and the OS-applicable {@code libraries[]} entries are fetched. The OS test
 * reads the manifest's own {@code rules} - Mojang classifies its libraries that way - and LWJGL/natives
 * entries are skipped on purpose: the headless client never loads a GL context, so the native libraries
 * would just waste bandwidth and disk. An unknown platform (anything outside the windows/osx/linux names
 * Mojang defines) only ever fails CLOSED: rule-gated libraries are skipped, never silently treated as
 * Linux; unconditional libraries - the json/Netty/API jars the bot actually runs on - are still fetched.
 */
public final class MojangRuntime {
   private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
   /** Game-directory subfolder where launcher-style library jars live; artifact paths are relative to it. */
   private static final String LIBRARIES = "libraries";
   private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

   private final Path gameDirectory;
   private final String version;

   public MojangRuntime(final File gameDirectory, final String version) {
      this.gameDirectory = gameDirectory.toPath();
      this.version = version;
   }

   /** One downloadable file with its expected SHA-1 (Mojang publishes both in the version json). */
   public record Artifact(String relativePath, URI url, String sha1, long size) {
   }

   /** An entry of the resolved runtime classpath, ordered: client jar first, then libraries. */
   public record Resolved(List<File> classpath, List<File> missing) {
      public boolean complete() {
         return this.missing.isEmpty();
      }
   }

   // ==================== public API ====================

   /** Preferred location of the client jar for this bot directory. */
   public Path clientJarPath() {
      return this.gameDirectory.resolve("versions").resolve(this.version).resolve(this.version + ".jar");
   }

   /** Path of the client jar relative to the bot directory, in launcher style. */
   private String clientRelativePath() {
      return Path.of("versions", this.version, this.version + ".jar").toString();
   }

   /**
    * Locates the client jar inside the game directory (no download). A jar is accepted only if it really
    * contains {@code net/minecraft/client/Minecraft.class}, so a file the user dropped as
    * {@code Minecraft-26.1.2.jar}, {@code minecraft-client.jar} or similar is found regardless of name. A
    * copy inside {@code plugins/} is moved out, because that folder is scanned for plugin jars.
    */
   public File findClientJar() {
      List<Path> candidates = new ArrayList<>();

      // The one way to use a jar from outside the game directory: say so explicitly. Nothing is ever
      // discovered on its own, because "where a launcher keeps its files" is a platform assumption and this
      // code has to behave identically on any JVM host.
      String explicit = System.getProperty("eml.clientJar");

      if (explicit != null && !explicit.isBlank()) {
         File found = this.accept(Path.of(explicit));

         if (found != null) {
            return found;
         }

         LaunchLog.warn("[bootstrap] eml.clientJar is not a usable client jar: " + explicit);
      }

      candidates.add(this.clientJarPath());
      candidates.add(this.gameDirectory.resolve(this.version + ".jar"));
      candidates.add(this.gameDirectory.resolve("plugins").resolve(this.version + ".jar"));
      candidates.add(this.gameDirectory.resolve("minecraft-client.jar"));

      for (Path candidate : candidates) {
         File found = this.accept(candidate);
         if (found != null) {
            return found;
         }
      }

      // Name-agnostic fallback: scan the obvious places for anything that is provably a client jar.
      for (File dir : List.of(this.gameDirectory.toFile(), this.gameDirectory.resolve("plugins").toFile())) {
         File[] jars = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
         if (jars == null) {
            continue;
         }

         for (File jar : jars) {
            File found = this.accept(jar.toPath());
            if (found != null) {
               return found;
            }
         }
      }

      return null;
   }

   /** Validates one candidate, relocating it out of {@code plugins/} when needed. */
   private File accept(final Path candidate) {
      File file = candidate.toFile();
      if (!file.isFile() || file.length() < 5_000_000L || !isClientJar(file)) {
         return null;
      }

      boolean insidePlugins = candidate.startsWith(this.gameDirectory.resolve("plugins"));
      if (!insidePlugins) {
         return file;
      }

      try {
         Path target = this.clientJarPath();
         Files.createDirectories(target.getParent());
         Files.move(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
         LaunchLog.info("[bootstrap] moved client jar out of plugins/ -> " + target);
         return target.toFile();
      } catch (IOException e) {
         LaunchLog.warn("[bootstrap] client jar is in plugins/ but could not be moved, using it in place: " + e);
         return file;
      }
   }

   /** Content check: a real client jar carries the mojmap {@code Minecraft} class entry. */
   private static boolean isClientJar(final File file) {
      try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
         return zip.getEntry("net/minecraft/client/Minecraft.class") != null;
      } catch (IOException e) {
         return false;
      }
   }

   /**
    * Makes sure the client jar exists, downloading only that one file when it does not.
    *
    * <p>Used by the build as well as by the bot (the compile-only "open" jar is generated FROM this file,
    * so a fresh checkout has to be able to fetch it). The destination is always inside the game directory.
    */
   public File ensureClientJar() throws IOException, InterruptedException {
      File existing = this.findClientJar();

      if (existing != null) {
         return existing;
      }

      for (Artifact artifact : this.requiredArtifacts()) {
         if (artifact.relativePath().equals(this.clientRelativePath())) {
            Path target = this.gameDirectory.resolve(artifact.relativePath());
            LaunchLog.info("[bootstrap] downloading client jar " + this.version + " (" + (artifact.size() / 1024 / 1024) + " MiB)");
            download(artifact, target);
            return target.toFile();
         }
      }

      throw new IOException("version manifest offers no client download for '" + this.version + "'");
   }

   /** All artifacts that make up the runtime: client jar + applicable libraries. */
   public List<Artifact> requiredArtifacts() throws IOException, InterruptedException {
      JsonObject versionJson = fetchJson(this.versionJsonUrl());
      List<Artifact> artifacts = new ArrayList<>();

      JsonObject downloads = versionJson.getAsJsonObject("downloads");
      if (downloads == null || !downloads.has("client")) {
         throw new IOException("version manifest has no client download entry");
      }

      JsonObject client = downloads.getAsJsonObject("client");
      artifacts.add(new Artifact(
         clientRelativePath(),
         URI.create(client.get("url").getAsString()),
         client.get("sha1").getAsString(),
         client.get("size").getAsLong()
      ));

      JsonArray libraries = versionJson.getAsJsonArray("libraries");

      if (libraries != null) {
         for (JsonElement element : libraries) {
            Artifact artifact = this.libraryArtifact(element.getAsJsonObject());
            if (artifact != null) {
               artifacts.add(artifact);
            }
         }
      }

      return artifacts;
   }

   /** Builds the classpath from what is already inside the game directory, downloading whatever is absent. */
   public Resolved resolve(final boolean allowDownload) throws IOException, InterruptedException {
      List<Artifact> artifacts = this.requiredArtifacts();
      List<File> classpath = new ArrayList<>();
      List<File> missing = new ArrayList<>();
      File existingClient = this.findClientJar();
      int reused = 0;
      int fetched = 0;

      // Says which jar the run uses, because "it never downloaded anything" is otherwise unexplainable: the
      // file may have been placed in the game directory earlier, or named explicitly on the command line.
      LaunchLog.info("[bootstrap] client jar: " + (existingClient != null
         ? existingClient.getAbsolutePath()
         : "not found, will download into " + this.gameDirectory.resolve(this.clientRelativePath())));

      for (Artifact artifact : artifacts) {
         Path target = this.gameDirectory.resolve(artifact.relativePath());

         if (artifact.relativePath().equals(this.clientRelativePath()) && existingClient != null) {
            classpath.add(existingClient); // already inside the game directory, or named explicitly
            reused++;
            continue;
         }

         if (Files.isRegularFile(target)) {
            classpath.add(target.toFile());
            reused++;
            continue;
         }

         if (!allowDownload) {
            missing.add(target.toFile());
            continue;
         }

         LaunchLog.info("[bootstrap] downloading " + artifact.relativePath() + " (" + (artifact.size() / 1024 / 1024) + " MiB)");
         download(artifact, target);
         classpath.add(target.toFile());
         fetched++;
      }

      LaunchLog.info("[bootstrap] runtime classpath: " + classpath.size() + " entries (" + reused + " reused, " + fetched + " downloaded)");
      return new Resolved(classpath, missing);
   }

   // ==================== internals ====================

   private URI versionJsonUrl() throws IOException, InterruptedException {
      JsonObject manifest = fetchJson(URI.create(VERSION_MANIFEST));
      JsonArray versions = manifest.getAsJsonArray("versions");

      for (JsonElement element : versions) {
         JsonObject entry = element.getAsJsonObject();
         if (this.version.equals(entry.get("id").getAsString())) {
            return URI.create(entry.get("url").getAsString());
         }
      }

      throw new IOException("version '" + this.version + "' is not in the Mojang version manifest");
   }

   private Artifact libraryArtifact(final JsonObject library) {
      if (!appliesToThisOs(library)) {
         return null;
      }

      String name = library.has("name") ? library.get("name").getAsString() : "";
      if (name.startsWith("org.lwjgl")) {
         return null; // no GL context in headless mode
      }

      JsonObject downloads = library.has("downloads") ? library.getAsJsonObject("downloads") : null;
      if (downloads == null || !downloads.has("artifact")) {
         return null; // natives-only entries
      }

      JsonObject artifact = downloads.getAsJsonObject("artifact");
      if (artifact.get("path").getAsString().contains("natives-")) {
         return null;
      }

      return new Artifact(
         relativize(LIBRARIES + "/" + artifact.get("path").getAsString()),
         URI.create(artifact.get("url").getAsString()),
         artifact.get("sha1").getAsString(),
         artifact.get("size").getAsLong()
      );
   }

   /**
    * Evaluates launcher {@code rules} (os allow/disallow) the way the vanilla launcher does: the last
    * matching rule decides, and when a library HAS rules but none of them match this OS, it is excluded.
    * A library without any rules is always included.
    *
    * <p>Two fail-closed details keep "any OS, any CPU" honest, both of which a naive reading gets wrong:
    * an unknown platform name matches NO {@code os} rule (it must not be lumped in with Linux), and a
    * rule that carries {@code arch} must match the name AND the arch - Mojang's
    * {@code {os:{name:"windows", arch:"x86"}}} would otherwise "allow" on every 32/64-bit x86 host.
    */
   private static boolean appliesToThisOs(final JsonObject library) {
      if (!library.has("rules")) {
         return true;
      }

      String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      // null = a platform Mojang's manifests do not define; every os rule then simply fails to match.
      String current = osName.contains("win") ? "windows" : osName.contains("os x") || osName.contains("mac") ? "osx" : osName.contains("linux") ? "linux" : null;
      boolean matched = false;
      boolean allowed = false;

      for (JsonElement element : library.getAsJsonArray("rules")) {
         JsonObject rule = element.getAsJsonObject();
         boolean matches = true;

         if (rule.has("os")) {
            JsonObject os = rule.getAsJsonObject("os");
            matches = current != null && (os.has("name") ? os.get("name").getAsString().equals(current) : true);

            if (matches && os.has("arch")) {
               String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
               String wanted = os.get("arch").getAsString().toLowerCase(Locale.ROOT);
               boolean arm = arch.contains("aarch64") || arch.contains("arm");
               // x86 in Mojang's manifests means "32-or-64-bit x86" (the launcher's own semantics).
               matches = wanted.equals("x86") ? !arm : wanted.equals("arm64") ? arm : false;
            }
         }

         if (matches) {
            matched = true;
            allowed = "allow".equals(rule.get("action").getAsString());
         }
      }

      return matched && allowed;
   }

   private static void download(final Artifact artifact, final Path target) throws IOException, InterruptedException {
      Files.createDirectories(target.getParent());
      Path temp = target.resolveSibling(target.getFileName() + ".part");
      HttpRequest request = HttpRequest.newBuilder(artifact.url()).timeout(Duration.ofMinutes(5)).GET().build();
      HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());

      if (response.statusCode() / 100 != 2) {
         throw new IOException("HTTP " + response.statusCode() + " for " + artifact.url());
      }

      MessageDigest digest = sha1();
      try (InputStream body = response.body()) {
         Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
      }

      byte[] data = Files.readAllBytes(temp);
      digest.update(data);
      String actual = toHex(digest.digest());

      if (!artifact.sha1().equalsIgnoreCase(actual)) {
         Files.deleteIfExists(temp);
         throw new IOException("SHA-1 mismatch for " + artifact.relativePath() + ": expected " + artifact.sha1() + ", got " + actual);
      }

      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
   }

   private static JsonObject fetchJson(final URI uri) throws IOException, InterruptedException {
      HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() / 100 != 2) {
         throw new IOException("HTTP " + response.statusCode() + " for " + uri);
      }

      return JsonParser.parseString(response.body()).getAsJsonObject();
   }

   private static MessageDigest sha1() {
      try {
         return MessageDigest.getInstance("SHA-1");
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   private static String toHex(final byte[] bytes) {
      StringBuilder out = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
         out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }

      return out.toString();
   }

   /** Launcher artifact paths are POSIX-style; normalise them for the current OS. */
   private static String relativize(final String path) {
      return path.replace('/', File.separatorChar);
   }
}
