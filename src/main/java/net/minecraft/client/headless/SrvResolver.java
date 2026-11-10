package net.minecraft.client.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/** Resolves the conventional {@code _minecraft._tcp} DNS SRV record used by Minecraft servers. */
public final class SrvResolver {
   public record Resolved(String host, int port, boolean srv) {
      static Resolved direct(final String host, final int port) {
         return new Resolved(host, port, false);
      }
   }

   private record Candidate(int priority, int weight, Resolved address) {
   }

   private SrvResolver() {
   }

   /** Explicit non-default ports and IP literals bypass SRV, matching the vanilla server-address rules. */
   public static Resolved resolve(final String host, final int port) {
      String normalized = normalize(host);
      if (port != 25565 || normalized.isEmpty() || isIpLiteral(normalized)) {
         return Resolved.direct(host, port);
      }

      DirContext context = null;
      try {
         String domain = java.net.IDN.toASCII(normalized);
         Hashtable<String, String> environment = new Hashtable<>();
         environment.put(javax.naming.Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
         environment.put(javax.naming.Context.PROVIDER_URL, "dns:");
         context = new InitialDirContext(environment);
         Attributes attributes = context.getAttributes("_minecraft._tcp." + domain, new String[]{"SRV"});
         Attribute records = attributes.get("SRV");
         if (records == null) {
            return Resolved.direct(host, port);
         }

         List<Candidate> candidates = new ArrayList<>();
         NamingEnumeration<?> values = records.getAll();
         while (values.hasMore()) {
            candidate(String.valueOf(values.next()), candidates);
         }

         return candidates.stream()
            .min(Comparator.comparingInt(Candidate::priority).thenComparing(Comparator.comparingInt(Candidate::weight).reversed()))
            .map(Candidate::address)
            .orElseGet(() -> Resolved.direct(host, port));
      } catch (NamingException | IllegalArgumentException ignored) {
         return Resolved.direct(host, port);
      } finally {
         if (context != null) {
            try {
               context.close();
            } catch (NamingException ignored) {
            }
         }
      }
   }

   private static void candidate(final String value, final List<Candidate> candidates) {
      try {
         String[] fields = value.trim().split("\\s+");
         if (fields.length < 4) {
            return;
         }

         int priority = Integer.parseInt(fields[0]);
         int weight = Integer.parseInt(fields[1]);
         int port = Integer.parseInt(fields[2]);
         String host = fields[3].endsWith(".") ? fields[3].substring(0, fields[3].length() - 1) : fields[3];
         if (port < 1 || port > 65535 || host.isEmpty() || ".".equals(host)) {
            return;
         }

         candidates.add(new Candidate(priority, weight, new Resolved(host, port, true)));
      } catch (NumberFormatException ignored) {
      }
   }

   private static String normalize(final String host) {
      String value = host == null ? "" : host.trim();
      return value.length() > 1 && value.startsWith("[") && value.endsWith("]")
         ? value.substring(1, value.length() - 1) : value;
   }

   private static boolean isIpLiteral(final String host) {
      if (host.indexOf(':') >= 0 || "localhost".equalsIgnoreCase(host)) {
         return true;
      }
      if (!host.matches("[0-9.]+")) {
         return false;
      }

      String[] parts = host.split("\\.", -1);
      if (parts.length != 4) {
         return false;
      }
      for (String part : parts) {
         try {
            if (part.isEmpty() || Integer.parseInt(part) > 255) {
               return false;
            }
         } catch (NumberFormatException error) {
            return false;
         }
      }

      return true;
   }
}
