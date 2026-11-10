package net.minecraft.client.headless;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A minimal Server List Ping client: handshake + status request, then the JSON status object.
 *
 * <p>Why bother before logging in: a headless bot that dials a dead or restarting server spends a whole
 * login attempt on it (and, with auto-reconnect on, repeats that every few seconds). One status round trip
 * answers "is it up, how busy is it, which version" in a few milliseconds and without touching the game
 * client at all - it is a plain socket, so it also works while the client thread is busy.
 *
 * <p>Protocol: VarInt length framing, handshake packet {@code 0x00} (protocol version, address, port, next
 * state {@code 1}), then an empty {@code 0x00} status request. The reply is one packet whose payload is a
 * packet id ({@code 0x00}) followed by a JSON string.
 */
public final class ServerPinger {
   /** {@code online == false} carries the reason in {@code detail}; the other fields are then -1/null. */
   public record Result(boolean online, long latencyMillis, int onlinePlayers, int maxPlayers, String version, int protocolVersion, String detail) {
      static Result offline(final String detail) {
         return new Result(false, -1L, -1, -1, null, -1, detail);
      }
   }

   private ServerPinger() {
   }

   public static Result ping(final String host, final int port, final int protocolVersion, final int timeoutMillis) {
      long started = System.nanoTime();

      try (Socket socket = new Socket()) {
         socket.setTcpNoDelay(true);
         socket.connect(new InetSocketAddress(host, port), timeoutMillis);
         socket.setSoTimeout(timeoutMillis);

         byte[] handshake = handshake(host, port, protocolVersion);
         OutputStream out = socket.getOutputStream();
         writeVarInt(out, handshake.length);
         out.write(handshake);
         // Status request: length 1, packet id 0.
         writeVarInt(out, 1);
         writeVarInt(out, 0x00);
         out.flush();

         DataInputStream in = new DataInputStream(socket.getInputStream());
         int length = readVarInt(in);
         if (length <= 0 || length > 1 << 21) {
            return Result.offline("invalid status length " + length);
         }

         byte[] payload = new byte[length];
         in.readFully(payload);
         String json = statusJson(payload);
         long latency = (System.nanoTime() - started) / 1_000_000L;
         return parse(json, latency);
      } catch (IOException e) {
         return Result.offline(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
      }
   }

   private static byte[] handshake(final String host, final int port, final int protocolVersion) throws IOException {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      writeVarInt(buffer, 0x00);
      writeVarInt(buffer, protocolVersion);
      byte[] address = host.getBytes(StandardCharsets.UTF_8);
      writeVarInt(buffer, address.length);
      buffer.write(address);
      buffer.write((port >>> 8) & 0xFF);
      buffer.write(port & 0xFF);
      writeVarInt(buffer, 1); // next state: status
      return buffer.toByteArray();
   }

   private static String statusJson(final byte[] payload) throws IOException {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
      int packetId = readVarInt(in);
      if (packetId != 0x00) {
         throw new IOException("unexpected status packet id " + packetId);
      }

      int length = readVarInt(in);
      if (length < 0 || length > payload.length) {
         throw new IOException("invalid status string length " + length);
      }

      byte[] text = new byte[length];
      in.readFully(text);
      return new String(text, StandardCharsets.UTF_8);
   }

   private static Result parse(final String json, final long latency) {
      try {
         JsonObject root = JsonParser.parseString(json).getAsJsonObject();
         int online = -1;
         int max = -1;
         if (root.has("players") && root.get("players").isJsonObject()) {
            JsonObject players = root.getAsJsonObject("players");
            online = players.has("online") ? players.get("online").getAsInt() : -1;
            max = players.has("max") ? players.get("max").getAsInt() : -1;
         }

         String version = "?";
         int protocol = -1;
         if (root.has("version") && root.get("version").isJsonObject()) {
            JsonObject info = root.getAsJsonObject("version");
            version = info.has("name") ? info.get("name").getAsString() : "?";
            protocol = info.has("protocol") ? info.get("protocol").getAsInt() : -1;
         }

         return new Result(true, latency, online, max, version, protocol, "ok");
      } catch (RuntimeException e) {
         return Result.offline("unreadable status json: " + e.getMessage());
      }
   }

   private static void writeVarInt(final OutputStream out, final int value) throws IOException {
      int remaining = value;
      while ((remaining & ~0x7F) != 0) {
         out.write((remaining & 0x7F) | 0x80);
         remaining >>>= 7;
      }

      out.write(remaining);
   }

   private static int readVarInt(final InputStream in) throws IOException {
      int value = 0;
      int position = 0;

      while (true) {
         int current = in.read();
         if (current < 0) {
            throw new IOException("unexpected end of stream while reading a VarInt");
         }

         value |= (current & 0x7F) << position;
         if ((current & 0x80) == 0) {
            return value;
         }

         position += 7;
         if (position >= 35) {
            throw new IOException("VarInt is too big");
         }
      }
   }
}
