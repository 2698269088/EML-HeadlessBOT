package top.mcocet.hlbot.autologin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small coercion helpers shared by the AutoLogin YAML parsers. */
final class YamlUtil {
   private YamlUtil() {
   }

   static String text(final Object value, final String fallback) {
      return value == null ? fallback : String.valueOf(value);
   }

   static boolean bool(final Object value, final boolean fallback) {
      if (value instanceof Boolean flag) {
         return flag;
      }

      return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value).trim());
   }

   static int integer(final Object value, final int fallback) {
      if (value instanceof Number number) {
         return number.intValue();
      }

      if (value == null) {
         return fallback;
      }

      try {
         return Integer.parseInt(String.valueOf(value).trim());
      } catch (NumberFormatException malformed) {
         return fallback;
      }
   }

   static long longValue(final Object value, final long fallback) {
      if (value instanceof Number number) {
         return number.longValue();
      }

      if (value == null) {
         return fallback;
      }

      try {
         return Long.parseLong(String.valueOf(value).trim());
      } catch (NumberFormatException malformed) {
         return fallback;
      }
   }

   /** Converts a YAML map to string keys, tolerating SnakeYAML's {@code Map<Object,Object>}. */
   static Map<String, Object> map(final Object value) {
      if (!(value instanceof Map<?, ?> raw)) {
         return new LinkedHashMap<>();
      }

      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : raw.entrySet()) {
         out.put(String.valueOf(entry.getKey()), entry.getValue());
      }

      return out;
   }

   static List<Map<String, Object>> mapList(final Object value) {
      if (!(value instanceof List<?> list)) {
         return List.of();
      }

      List<Map<String, Object>> out = new ArrayList<>();
      for (Object item : list) {
         out.add(map(item));
      }

      return out;
   }

   /** A list of strings; a scalar is accepted as a one-element list for forgiving config files. */
   static List<String> strings(final Object value) {
      if (value instanceof List<?> list) {
         List<String> out = new ArrayList<>();
         for (Object item : list) {
            if (item != null) {
               out.add(String.valueOf(item));
            }
         }

         return out;
      }

      return value == null ? List.of() : List.of(String.valueOf(value));
   }
}
