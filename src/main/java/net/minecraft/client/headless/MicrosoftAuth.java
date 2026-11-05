package net.minecraft.client.headless;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

/**
 * Microsoft account login for the official (online-mode) server flow.
 *
 * <p>The official Minecraft launcher performs this before the game starts and only hands the game a
 * {@code User} carrying a Minecraft access token. authlib does not implement the Microsoft OAuth part, so
 * this class owns the whole chain:
 *
 * <ol>
 *   <li>Microsoft device-code flow (no browser on the host needed; the user opens a URL on any device);</li>
 *   <li>Xbox Live authentication and XSTS token exchange;</li>
 *   <li>Minecraft services login ({@code api.minecraftservices.com});</li>
 *   <li>profile fetch (real UUID/name) and a game-entitlement check.</li>
 * </ol>
 *
 * <p>The result is a {@link MicrosoftSession}, which can be persisted and refreshed without re-running the
 * device-code flow. The session is then turned into a {@code net.minecraft.client.User} for the client.
 */
public final class MicrosoftAuth {
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

   /** The public Azure application id used by the Minecraft launcher (and by third-party clients). */
   public static final String CLIENT_ID = "00000000402b5328";

   private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
   private static final String FORM = "application/x-www-form-urlencoded";
   private static final String JSON = "application/json";

   private static final URI DEVICE_CODE_URL = URI.create("https://login.live.com/oauth20_connect.srf");
   private static final URI TOKEN_URL = URI.create("https://login.live.com/oauth20_token.srf");
   private static final URI XBL_URL = URI.create("https://user.auth.xboxlive.com/user/authenticate");
   private static final URI XSTS_URL = URI.create("https://xsts.auth.xboxlive.com/xsts/authorize");
   private static final URI MC_LOGIN_URL = URI.create("https://api.minecraftservices.com/launcher/login");
   private static final URI MC_PROFILE_URL = URI.create("https://api.minecraftservices.com/minecraft/profile");
   private static final URI MC_ENTITLEMENTS_URL = URI.create("https://api.minecraftservices.com/entitlements/mcstore");

   private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(30))
      .build();

   private MicrosoftAuth() {
   }

   /**
    * Resolves a session for online-mode startup: reuses a still-valid cached Minecraft token, refreshes
    * it via the cached Microsoft refresh token when possible, and only then falls back to the interactive
    * device-code flow.
    */
   public static MicrosoftSession login(final ConsoleIO io, final File gameDirectory) throws MicrosoftAuthException {
      File file = new File(gameDirectory, "session.yml");
      MicrosoftSession cached = MicrosoftSession.load(file);

      if (cached != null && cached.accessToken() != null && !cached.accessToken().isBlank()) {
         try {
            Profile profile = fetchProfile(cached.accessToken());
            MicrosoftSession updated = cached.withProfile(profile.name, profile.id);
            updated.save(file);
            io.print(BotLang.get("bot.mslogin.valid", updated.name()));
            return updated;
         } catch (MicrosoftAuthException invalid) {
            io.print(BotLang.get("bot.mslogin.cached_invalid", invalid.getMessage()));
         }
      }

      if (cached != null && cached.refreshToken() != null && !cached.refreshToken().isBlank()) {
         try {
            MicrosoftSession refreshed = refresh(cached);
            refreshed.save(file);
            io.print(BotLang.get("bot.mslogin.refreshed", refreshed.name()));
            return refreshed;
         } catch (MicrosoftAuthException failed) {
            io.print(BotLang.get("bot.mslogin.refresh_failed", failed.getMessage()));
         }
      }

      return deviceCodeLogin(io, file);
   }

   /**
    * Runs the device-code flow unconditionally (used by the {@code login} command to switch accounts) and
    * saves the resulting session to {@code sessionFile}.
    */
   public static MicrosoftSession deviceCodeLogin(final ConsoleIO io, final File sessionFile) throws MicrosoftAuthException {
      DeviceCode code = requestDeviceCode();
      io.print(BotLang.get("bot.mslogin.open", code.verificationUri));
      io.print(BotLang.get("bot.mslogin.code", code.userCode));
      io.print(BotLang.get("bot.mslogin.waiting"));

      MsaToken token = awaitDeviceCode(code);
      MicrosoftSession session = authenticate(token);
      session.save(sessionFile);
      io.print(BotLang.get("bot.mslogin.success", session.name(), session.uuid()));
      return session;
   }

   // ==================== Microsoft device-code flow ====================

   private static DeviceCode requestDeviceCode() throws MicrosoftAuthException {
      HttpResponse<String> response = post(DEVICE_CODE_URL, FORM, form(Map.of(
         "client_id", CLIENT_ID,
         "scope", SCOPE,
         "response_type", "device_code"
      )));
      JsonObject json = expect2xx(response, "device-code request");

      String userCode = str(json, "user_code");
      String deviceCode = str(json, "device_code");
      String verificationUri = str(json, "verification_uri");
      if (userCode == null || deviceCode == null || verificationUri == null) {
         throw new MicrosoftAuthException("device-code response is missing required fields");
      }

      return new DeviceCode(userCode, deviceCode, verificationUri, intValue(json, "interval", 5), intValue(json, "expires_in", 900));
   }

   private static MsaToken awaitDeviceCode(final DeviceCode code) throws MicrosoftAuthException {
      long deadline = System.currentTimeMillis() + code.expiresIn * 1000L;
      int interval = Math.max(1, code.interval);

      while (System.currentTimeMillis() < deadline) {
         try {
            Thread.sleep(interval * 1000L);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MicrosoftAuthException("login was interrupted");
         }

         HttpResponse<String> response = post(TOKEN_URL, FORM, form(Map.of(
            "client_id", CLIENT_ID,
            "grant_type", "device_code",
            "device_code", code.deviceCode
         )));

         if (response.statusCode() == 200) {
            JsonObject json = parseJson(response.body(), "device-code token");
            String accessToken = str(json, "access_token");
            String refreshToken = str(json, "refresh_token");
            if (accessToken == null) {
               throw new MicrosoftAuthException("device-code token response is missing access_token");
            }
            return new MsaToken(accessToken, refreshToken);
         }

         JsonObject errorBody = safeParse(response.body());
         String error = errorBody == null ? null : str(errorBody, "error");
         if ("authorization_pending".equals(error)) {
            continue;
         }
         if ("slow_down".equals(error)) {
            interval += 5;
            continue;
         }

         String detail = error;
         if (detail == null) {
            detail = response.body() == null || response.body().isBlank() ? "HTTP " + response.statusCode() : response.body();
         }
         throw new MicrosoftAuthException("Microsoft login failed: " + detail);
      }

      throw new MicrosoftAuthException("Microsoft login timed out; run 'login' again for a new code");
   }

   // ==================== Xbox Live / Minecraft token chain ====================

   private static MicrosoftSession authenticate(final MsaToken msa) throws MicrosoftAuthException {
      XblXstsToken xbl = authenticateXbl(msa.accessToken);
      XblXstsToken xsts = authenticateXsts(xbl.token);
      String uhs = xsts.userHash != null ? xsts.userHash : xbl.userHash;
      String minecraftToken = loginWithXbox(uhs, xsts.token);
      Profile profile = fetchProfile(minecraftToken);
      checkEntitlements(minecraftToken);
      return new MicrosoftSession(profile.name, profile.id, minecraftToken, msa.refreshToken, xbl.xuid, CLIENT_ID);
   }

   private static XblXstsToken authenticateXbl(final String accessToken) throws MicrosoftAuthException {
      JsonObject properties = new JsonObject();
      properties.addProperty("AuthMethod", "RPS");
      properties.addProperty("SiteName", "user.auth.xboxlive.com");
      properties.addProperty("RpsTicket", "t=" + accessToken);

      JsonObject request = new JsonObject();
      request.add("Properties", properties);
      request.addProperty("RelyingParty", "http://auth.xboxlive.com");
      request.addProperty("TokenType", "JWT");

      HttpResponse<String> response = post(XBL_URL, JSON, request.toString(), Map.of("x-xbl-contract-version", "1"));
      JsonObject json = expect2xx(response, "Xbox Live authentication");
      String token = str(json, "Token");
      if (token == null) {
         throw new MicrosoftAuthException("Xbox Live response is missing Token");
      }

      String xuid = xui(json, "xid");
      if (xuid == null) {
         xuid = xui(json, "xuid");
      }
      return new XblXstsToken(token, xui(json, "uhs"), xuid);
   }

   private static XblXstsToken authenticateXsts(final String xblToken) throws MicrosoftAuthException {
      JsonObject properties = new JsonObject();
      properties.addProperty("SandboxId", "RETAIL");
      JsonArray userTokens = new JsonArray();
      userTokens.add(xblToken);
      properties.add("UserTokens", userTokens);

      JsonObject request = new JsonObject();
      request.add("Properties", properties);
      request.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
      request.addProperty("TokenType", "JWT");

      HttpResponse<String> response = post(XSTS_URL, JSON, request.toString(), Map.of("x-xbl-contract-version", "1"));
      JsonObject json = expect2xx(response, "Xbox Live XSTS authentication");
      String token = str(json, "Token");
      if (token == null) {
         throw new MicrosoftAuthException("XSTS response is missing Token");
      }

      return new XblXstsToken(token, xui(json, "uhs"), null);
   }

   private static String loginWithXbox(final String uhs, final String xstsToken) throws MicrosoftAuthException {
      if (uhs == null || uhs.isBlank()) {
         throw new MicrosoftAuthException("Xbox Live response is missing the user hash");
      }

      JsonObject request = new JsonObject();
      request.addProperty("platform", "PC_LAUNCHER");
      request.addProperty("xtoken", "XBL3.0 x=" + uhs + ";" + xstsToken);

      HttpResponse<String> response = post(MC_LOGIN_URL, JSON, request.toString());
      JsonObject json = expect2xx(response, "Minecraft login");
      String accessToken = str(json, "access_token");
      if (accessToken == null) {
         throw new MicrosoftAuthException("Minecraft login response is missing access_token");
      }

      return accessToken;
   }

   private static Profile fetchProfile(final String accessToken) throws MicrosoftAuthException {
      HttpResponse<String> response = get(MC_PROFILE_URL, accessToken);
      if (response.statusCode() == 404) {
         throw new MicrosoftAuthException("this Microsoft account does not own Minecraft (Java Edition)");
      }

      JsonObject json = expect2xx(response, "Minecraft profile");
      String id = str(json, "id");
      String name = str(json, "name");
      if (id == null || name == null) {
         throw new MicrosoftAuthException("Minecraft profile response is missing id/name");
      }

      return new Profile(name, id);
   }

   /** Ownership is normally implied by a successful profile fetch; this is an extra, non-fatal check. */
   private static void checkEntitlements(final String accessToken) throws MicrosoftAuthException {
      HttpResponse<String> response = get(MC_ENTITLEMENTS_URL, accessToken);
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
         LOGGER.warn("Could not check Minecraft entitlements (HTTP {}): {}", response.statusCode(), response.body());
         return;
      }

      JsonObject json = parseJson(response.body(), "Minecraft entitlements");
      if (!json.has("items") || !json.get("items").isJsonArray()) {
         return;
      }

      for (var element : json.getAsJsonArray("items")) {
         if (element.isJsonObject()) {
            String itemName = str(element.getAsJsonObject(), "name");
            if ("game_minecraft".equals(itemName) || "product_minecraft".equals(itemName)) {
               return;
            }
         }
      }

      LOGGER.warn("Microsoft account may not own Minecraft: no game entitlement found");
   }

   /** Refreshes a cached session via its Microsoft refresh token; public for the runtime renewal task. */
   public static MicrosoftSession refresh(final MicrosoftSession session) throws MicrosoftAuthException {
      String refreshToken = session.refreshToken();
      if (refreshToken == null || refreshToken.isBlank()) {
         throw new MicrosoftAuthException("no refresh token in the cached session");
      }

      HttpResponse<String> response = post(TOKEN_URL, FORM, form(Map.of(
         "client_id", CLIENT_ID,
         "grant_type", "refresh_token",
         "refresh_token", refreshToken,
         "scope", SCOPE
      )));
      JsonObject json = expect2xx(response, "Microsoft token refresh");

      String accessToken = str(json, "access_token");
      String newRefreshToken = str(json, "refresh_token");
      if (accessToken == null) {
         throw new MicrosoftAuthException("refresh response is missing access_token");
      }

      return authenticate(new MsaToken(accessToken, newRefreshToken == null ? refreshToken : newRefreshToken));
   }

   // ==================== HTTP / JSON helpers ====================

   private static HttpResponse<String> post(final URI uri, final String contentType, final String body) throws MicrosoftAuthException {
      return post(uri, contentType, body, Map.of());
   }

   private static HttpResponse<String> post(final URI uri, final String contentType, final String body, final Map<String, String> headers) throws MicrosoftAuthException {
      try {
         HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", contentType)
            .header("Accept", "application/json");
         headers.forEach(builder::header);

         HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
         return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new MicrosoftAuthException("interrupted while contacting " + uri, e);
      } catch (Exception e) {
         throw new MicrosoftAuthException("could not reach " + uri + ": " + e, e);
      }
   }

   private static HttpResponse<String> get(final URI uri, final String bearer) throws MicrosoftAuthException {
      try {
         HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json");
         if (bearer != null && !bearer.isBlank()) {
            builder.header("Authorization", "Bearer " + bearer);
         }

         return HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new MicrosoftAuthException("interrupted while contacting " + uri, e);
      } catch (Exception e) {
         throw new MicrosoftAuthException("could not reach " + uri + ": " + e, e);
      }
   }

   private static String form(final Map<String, String> fields) {
      StringBuilder out = new StringBuilder();
      for (Map.Entry<String, String> entry : fields.entrySet()) {
         if (out.length() > 0) {
            out.append('&');
         }

         out.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
         out.append('=');
         out.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
      }

      return out.toString();
   }

   private static JsonObject expect2xx(final HttpResponse<String> response, final String context) throws MicrosoftAuthException {
      int status = response.statusCode();
      if (status < 200 || status >= 300) {
         String detail = response.body() == null || response.body().isBlank() ? "HTTP " + status : response.body();
         throw new MicrosoftAuthException(context + " failed (HTTP " + status + "): " + detail);
      }

      return parseJson(response.body(), context);
   }

   private static JsonObject parseJson(final String body, final String context) throws MicrosoftAuthException {
      try {
         return JsonParser.parseString(body).getAsJsonObject();
      } catch (RuntimeException e) {
         throw new MicrosoftAuthException(context + ": invalid JSON response: " + body);
      }
   }

   private static JsonObject safeParse(final String body) {
      try {
         return JsonParser.parseString(body).getAsJsonObject();
      } catch (RuntimeException e) {
         return null;
      }
   }

   private static String str(final JsonObject json, final String key) {
      return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
   }

   private static int intValue(final JsonObject json, final String key, final int fallback) {
      if (json.has(key) && json.get(key).isJsonPrimitive()) {
         try {
            return json.get(key).getAsInt();
         } catch (RuntimeException ignored) {
            return fallback;
         }
      }

      return fallback;
   }

   private static String xui(final JsonObject json, final String key) {
      if (!json.has("DisplayClaims") || !json.get("DisplayClaims").isJsonObject()) {
         return null;
      }

      JsonObject claims = json.getAsJsonObject("DisplayClaims");
      if (!claims.has("xui") || !claims.get("xui").isJsonArray()) {
         return null;
      }

      JsonArray xui = claims.getAsJsonArray("xui");
      if (xui.isEmpty() || !xui.get(0).isJsonObject()) {
         return null;
      }

      JsonObject first = xui.get(0).getAsJsonObject();
      return first.has(key) && !first.get(key).isJsonNull() ? first.get(key).getAsString() : null;
   }

   // ==================== small value carriers ====================

   private static final class DeviceCode {
      final String userCode;
      final String deviceCode;
      final String verificationUri;
      final int interval;
      final int expiresIn;

      DeviceCode(final String userCode, final String deviceCode, final String verificationUri, final int interval, final int expiresIn) {
         this.userCode = userCode;
         this.deviceCode = deviceCode;
         this.verificationUri = verificationUri;
         this.interval = interval;
         this.expiresIn = expiresIn;
      }
   }

   private static final class MsaToken {
      final String accessToken;
      final String refreshToken;

      MsaToken(final String accessToken, final String refreshToken) {
         this.accessToken = accessToken;
         this.refreshToken = refreshToken;
      }
   }

   private static final class XblXstsToken {
      final String token;
      final String userHash;
      final String xuid;

      XblXstsToken(final String token, final String userHash, final String xuid) {
         this.token = token;
         this.userHash = userHash;
         this.xuid = xuid;
      }
   }

   private static final class Profile {
      final String name;
      final String id;

      Profile(final String name, final String id) {
         this.name = name;
         this.id = id;
      }
   }
}
