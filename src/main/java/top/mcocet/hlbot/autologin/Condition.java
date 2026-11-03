package top.mcocet.hlbot.autologin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rule condition (leaf + combinators). Leaf constraints are ANDed; {@code all} / {@code any} /
 * {@code not} nest further conditions. A {@code regex} match stores its capture groups as
 * {@code g1}..{@code gN} in the output map for {@code {{g1}}} interpolation by later actions.
 */
final class Condition {
   private final Set<Signal.Type> types = EnumSet.noneOf(Signal.Type.class);
   private final List<String> contains;
   private final List<String> notContains;
   private final String equalsText;
   private final String startsWithText;
   private final String regex;
   private final Pattern regexPattern;
   private final List<String> stage;
   private final List<String> notStage;
   private final String sender;
   private final Boolean overlay;
   private final boolean ignoreCase;
   private final List<Condition> all;
   private final List<Condition> any;
   private final Condition not;

   private Condition(final Set<Signal.Type> types, final List<String> contains, final List<String> notContains,
                     final String equalsText, final String startsWithText, final String regex, final Pattern regexPattern,
                     final List<String> stage, final List<String> notStage, final String sender, final Boolean overlay,
                     final boolean ignoreCase, final List<Condition> all, final List<Condition> any, final Condition not) {
      this.types.addAll(types);
      this.contains = contains;
      this.notContains = notContains;
      this.equalsText = equalsText;
      this.startsWithText = startsWithText;
      this.regex = regex;
      this.regexPattern = regexPattern;
      this.stage = stage;
      this.notStage = notStage;
      this.sender = sender;
      this.overlay = overlay;
      this.ignoreCase = ignoreCase;
      this.all = all;
      this.any = any;
      this.not = not;
   }

   static Condition from(final Object raw) {
      Map<String, Object> m = YamlUtil.map(raw);
      if (m.isEmpty()) {
         return new Condition(Set.of(), List.of(), List.of(), null, null, null, null,
            List.of(), List.of(), null, null, true, null, null, null);
      }

      Set<Signal.Type> types = EnumSet.noneOf(Signal.Type.class);
      for (String name : YamlUtil.strings(m.get("types"))) {
         try {
            types.add(Signal.Type.valueOf(name.trim().toUpperCase(Locale.ROOT)));
         } catch (IllegalArgumentException ignored) {
            // Unknown signal type: skip it rather than failing the whole config.
         }
      }

      boolean ignoreCase = m.containsKey("ignoreCase") ? YamlUtil.bool(m.get("ignoreCase"), true) : true;
      String regex = YamlUtil.text(m.get("regex"), null);
      Pattern compiled = regex == null ? null : Pattern.compile(regex, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);

      List<Condition> all = m.get("all") == null ? null : loadList(m.get("all"));
      List<Condition> any = m.get("any") == null ? null : loadList(m.get("any"));
      Condition not = m.get("not") == null ? null : from(m.get("not"));

      return new Condition(
         types,
         YamlUtil.strings(m.get("contains")),
         YamlUtil.strings(m.get("notContains")),
         YamlUtil.text(m.get("equals"), null),
         YamlUtil.text(m.get("startsWith"), null),
         regex,
         compiled,
         YamlUtil.strings(m.get("stage")),
         YamlUtil.strings(m.get("notStage")),
         YamlUtil.text(m.get("sender"), null),
         m.get("overlay") == null ? null : YamlUtil.bool(m.get("overlay"), false),
         ignoreCase,
         all,
         any,
         not
      );
   }

   private static List<Condition> loadList(final Object value) {
      List<Condition> out = new ArrayList<>();
      for (Map<String, Object> sub : YamlUtil.mapList(value)) {
         out.add(from(sub));
      }

      return out;
   }

   /**
    * Evaluates this condition against a signal and session context. Captures produced by a successful
    * branch (or by {@code regex}) are merged into {@code out}.
    */
   boolean matches(final Signal signal, final RuleContext ctx, final Map<String, String> out) {
      if (this.not != null) {
         Map<String, String> scratch = new HashMap<>();
         if (this.not.matches(signal, ctx, scratch)) {
            return false;
         }
      }

      if (this.all != null) {
         for (Condition sub : this.all) {
            if (!sub.matches(signal, ctx, out)) {
               return false;
            }
         }
      }

      if (this.any != null) {
         boolean matched = false;
         for (Condition sub : this.any) {
            Map<String, String> branch = new HashMap<>();
            if (sub.matches(signal, ctx, branch)) {
               out.putAll(branch);
               matched = true;
               break;
            }
         }

         if (!matched) {
            return false;
         }
      }

      if (!this.types.isEmpty() && !this.types.contains(signal.type())) {
         return false;
      }

      if (this.overlay != null && this.overlay != signal.overlay()) {
         return false;
      }

      String currentStage = ctx.getStage() == null ? "" : ctx.getStage();
      if (!this.stage.isEmpty() && !this.stage.stream().anyMatch(s -> s.equalsIgnoreCase(currentStage))) {
         return false;
      }

      if (!this.notStage.isEmpty() && this.notStage.stream().anyMatch(s -> s.equalsIgnoreCase(currentStage))) {
         return false;
      }

      if (this.sender != null) {
         String s = signal.sender();
         if (s == null || !(this.ignoreCase ? s.equalsIgnoreCase(this.sender) : s.equals(this.sender))) {
            return false;
         }
      }

      String text = signal.text();
      String hay = this.ignoreCase ? text.toLowerCase(Locale.ROOT) : text;

      for (String needle : this.contains) {
         String n = this.ignoreCase ? needle.toLowerCase(Locale.ROOT) : needle;
         if (!hay.contains(n)) {
            return false;
         }
      }

      for (String needle : this.notContains) {
         String n = this.ignoreCase ? needle.toLowerCase(Locale.ROOT) : needle;
         if (hay.contains(n)) {
            return false;
         }
      }

      if (this.equalsText != null) {
         String expected = this.ignoreCase ? this.equalsText.toLowerCase(Locale.ROOT) : this.equalsText;
         if (!hay.equals(expected)) {
            return false;
         }
      }

      if (this.startsWithText != null) {
         String prefix = this.ignoreCase ? this.startsWithText.toLowerCase(Locale.ROOT) : this.startsWithText;
         if (!hay.startsWith(prefix)) {
            return false;
         }
      }

      if (this.regexPattern != null) {
         Matcher matcher = this.regexPattern.matcher(text);
         if (!matcher.find()) {
            return false;
         }

         for (int i = 1; i <= matcher.groupCount(); i++) {
            String captured = matcher.group(i);
            if (captured != null) {
               out.put("g" + i, captured);
            }
         }
      }

      return true;
   }
}
