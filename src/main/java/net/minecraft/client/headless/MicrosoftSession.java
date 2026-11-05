package net.minecraft.client.headless;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.User;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * A cached Microsoft/Minecraft session, persisted to {@code session.yml} so a bot does not need
 * the interactive device-code flow on every start.
 *
 * <p>The Minecraft access token is short-lived (about 24 hours); the Microsoft refresh token is
 * kept alongside it so {@link MicrosoftAuth} can obtain a fresh Minecraft token without asking the
 * user again. The refresh token is a secret and the file should not be shared.
 */
public final class MicrosoftSession {
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
   /** Serialises writes to {@code session.yml}: the runtime renewal task and the console 'login' command can race. */
   private static final Object SAVE_LOCK = new Object();

   private final String name;
   private final String uuid;          // undashed, exactly as returned by api.minecraftservices.com
   private final String accessToken;   // Minecraft (api.minecraftservices.com) access token
   private final String refreshToken;  // Microsoft refresh token
   private final String xuid;          // optional Xbox user id
   private final String clientId;

   public MicrosoftSession(final String name, final String uuid, final String accessToken,
                           final String refreshToken, final String xuid, final String clientId) {
      this.name = name;
      this.uuid = uuid;
      this.accessToken = accessToken;
      this.refreshToken = refreshToken;
      this.xuid = xuid;
      this.clientId = clientId;
   }

   public String name() {
      return this.name;
   }

   public String uuid() {
      return this.uuid;
   }

   public String accessToken() {
      return this.accessToken;
   }

   public String refreshToken() {
      return this.refreshToken;
   }

   public String xuid() {
      return this.xuid;
   }

   public String clientId() {
      return this.clientId;
   }

   public MicrosoftSession withProfile(final String name, final String uuid) {
      return new MicrosoftSession(name, uuid, this.accessToken, this.refreshToken, this.xuid, this.clientId);
   }

   /** The vanilla client identity: real profile id and Minecraft access token. */
   public User toUser() {
      UUID id = com.mojang.util.UndashedUuid.fromString(this.uuid);
      return new User(
         this.name,
         id,
         this.accessToken,
         this.xuid == null || this.xuid.isBlank() ? Optional.empty() : Optional.of(this.xuid),
         this.clientId == null || this.clientId.isBlank() ? Optional.empty() : Optional.of(this.clientId)
      );
   }

   /** Reads {@code session.yml}; a missing or unreadable file yields {@code null}. */
   public static MicrosoftSession load(final File file) {
      if (!file.isFile()) {
         return null;
      }

      synchronized (SAVE_LOCK) {
         try (InputStream in = Files.newInputStream(file.toPath())) {
            Object root = new Yaml().load(in);
            if (root instanceof Map<?, ?> map) {
               String name = string(map.get("name"));
               String uuid = string(map.get("uuid"));
               String accessToken = string(map.get("accessToken"));
               String refreshToken = string(map.get("refreshToken"));
               String xuid = string(map.get("xuid"));
               String clientId = string(map.get("clientId"));
               if (name != null && uuid != null && accessToken != null) {
                  return new MicrosoftSession(name, uuid, accessToken, refreshToken, xuid, clientId);
               }
            }
         } catch (Exception e) {
            LOGGER.warn("Could not read {}", file.getName(), e);
         }
      }

      return null;
   }

   public void save(final File file) {
      synchronized (SAVE_LOCK) {
         writeLocked(file);
      }
   }

   private void writeLocked(final File file) {
      Map<String, String> data = new LinkedHashMap<>();
      data.put("name", this.name);
      data.put("uuid", this.uuid);
      data.put("accessToken", this.accessToken);
      if (this.refreshToken != null && !this.refreshToken.isBlank()) {
         data.put("refreshToken", this.refreshToken);
      }
      if (this.xuid != null && !this.xuid.isBlank()) {
         data.put("xuid", this.xuid);
      }
      if (this.clientId != null && !this.clientId.isBlank()) {
         data.put("clientId", this.clientId);
      }

      String content = "# EML-HeadlessBOT Microsoft session - the refresh token is a secret; do not share this file.\n"
         + new Yaml().dump(data);
      try {
         Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
      } catch (Exception e) {
         LOGGER.warn("Could not save {}", file.getName(), e);
      }
   }

   private static String string(final Object value) {
      if (value == null) {
         return null;
      }

      String text = String.valueOf(value);
      return text.isBlank() ? null : text;
   }
}
