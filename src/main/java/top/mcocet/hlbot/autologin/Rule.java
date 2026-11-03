package top.mcocet.hlbot.autologin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One rule: a condition ({@code when}) plus an ordered action list, with evaluation modifiers.
 *
 * <p>{@code stage} / {@code notStage} may be written either inside {@code when} or directly on the
 * rule (the latter is a shorthand, matching XinConnect's templates). {@code priority} is evaluated
 * ascending; {@code once} limits a rule to one trigger per session; {@code cooldownMs} spaces repeated
 * triggers; {@code stopOnMatch} stops the evaluation of lower-priority rules after this one fires.
 */
final class Rule {
   private final String id;
   private final int priority;
   private final boolean once;
   private final long cooldownMs;
   private final boolean stopOnMatch;
   private final Condition condition;
   private final List<Action> actions;

   private Rule(final String id, final int priority, final boolean once, final long cooldownMs,
                final boolean stopOnMatch, final Condition condition, final List<Action> actions) {
      this.id = id;
      this.priority = priority;
      this.once = once;
      this.cooldownMs = cooldownMs;
      this.stopOnMatch = stopOnMatch;
      this.condition = condition;
      this.actions = actions;
   }

   static Rule from(final Object raw, final int index) {
      Map<String, Object> m = YamlUtil.map(raw);
      String id = m.containsKey("id") ? YamlUtil.text(m.get("id"), "rule-" + index) : "rule-" + index;
      int priority = YamlUtil.integer(m.get("priority"), 100);
      boolean once = YamlUtil.bool(m.get("once"), false);
      long cooldownMs = YamlUtil.longValue(m.get("cooldownMs"), 0L);
      boolean stopOnMatch = YamlUtil.bool(m.get("stopOnMatch"), false);

      Map<String, Object> when = YamlUtil.map(m.get("when"));
      if (!when.containsKey("stage") && m.get("stage") != null) {
         when.put("stage", m.get("stage"));
      }

      if (!when.containsKey("notStage") && m.get("notStage") != null) {
         when.put("notStage", m.get("notStage"));
      }

      List<Action> actions = new ArrayList<>();
      Object actionList = m.get("actions");
      if (actionList instanceof List<?> list) {
         for (Object item : list) {
            actions.add(Action.from(item));
         }
      } else if (m.get("action") != null) {
         actions.add(Action.from(m.get("action")));
      }

      return new Rule(id, priority, once, cooldownMs, stopOnMatch, Condition.from(when), actions);
   }

   String id() {
      return this.id;
   }

   int priority() {
      return this.priority;
   }

   boolean isOnce() {
      return this.once;
   }

   long cooldownMs() {
      return this.cooldownMs;
   }

   boolean isStopOnMatch() {
      return this.stopOnMatch;
   }

   Condition condition() {
      return this.condition;
   }

   List<Action> actions() {
      return this.actions;
   }
}
