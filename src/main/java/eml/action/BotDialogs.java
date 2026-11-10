package eml.action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.gui.screens.dialog.DialogConnectionAccess;
import net.minecraft.core.Holder;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.ButtonListDialog;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.DialogListDialog;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.SimpleDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;

/** Headless dialog state and the same action pipeline used by vanilla dialog screens. */
public final class BotDialogs {
   public static final BotDialogs INSTANCE = new BotDialogs();

   public record InputInfo(String key, String type, String label, String value, int maxLength, List<String> options) {
      public InputInfo {
         options = List.copyOf(options);
      }
   }

   public record ButtonInfo(int index, String label, String action) {
   }

   public record Snapshot(String title, List<String> body, List<InputInfo> inputs, List<ButtonInfo> buttons,
                          boolean canCancel, boolean waiting) {
      public Snapshot {
         body = List.copyOf(body);
         inputs = List.copyOf(inputs);
         buttons = List.copyOf(buttons);
      }
   }

   private record InputState(InputInfo info, net.minecraft.server.dialog.Input definition) {
   }

   private record ButtonEntry(ButtonInfo info, Optional<Action> action) {
   }

   private static final class State {
      private final Dialog dialog;
      private final DialogConnectionAccess access;
      private final Map<String, InputState> inputs = new LinkedHashMap<>();
      private final List<ButtonEntry> buttons;
      private Snapshot snapshot;
      private boolean waiting;

      private State(final Dialog dialog, final DialogConnectionAccess access) {
         this.dialog = dialog;
         this.access = access;
         this.buttons = buttonsOf(dialog);
      }
   }

   private volatile State current;

   private BotDialogs() {
   }

   /** Called from the patched common packet listener on the client thread. */
   public void open(final Holder<Dialog> holder, final DialogConnectionAccess access) {
      Dialog dialog = holder.value();
      close("replaced");
      State state = new State(dialog, access);
      for (net.minecraft.server.dialog.Input input : dialog.common().inputs()) {
         state.inputs.put(input.key(), initialInput(input));
      }

      refreshSnapshot(state);
      this.current = state;
      top.mcocet.hlbot.plugins.BotPlugins.fire(
         new top.mcocet.hlbot.plugins.events.dialog.DialogOpenedEvent(state.snapshot));
      eml.hooks.BotRuntime.log().info("[dialog] {} ({} input(s), {} button(s)); use 'dialog info'",
         state.snapshot.title(), state.inputs.size(), state.buttons.size());
   }

   /** Drops the current dialog without sending a cancel action. */
   public void close(final String reason) {
      State state = this.current;
      if (state == null) {
         return;
      }

      this.current = null;
      top.mcocet.hlbot.plugins.BotPlugins.fire(
         new top.mcocet.hlbot.plugins.events.dialog.DialogClosedEvent(state.snapshot, reason));
   }

   public boolean isOpen() {
      return this.current != null;
   }

   public Snapshot snapshot() {
      State state = this.current;
      return state == null ? null : state.snapshot;
   }

   public List<InputInfo> inputs() {
      Snapshot snapshot = snapshot();
      return snapshot == null ? List.of() : snapshot.inputs();
   }

   public List<ButtonInfo> buttons() {
      Snapshot snapshot = snapshot();
      return snapshot == null ? List.of() : snapshot.buttons();
   }

   public String inputValue(final String key) {
      State state = this.current;
      InputState input = state == null ? null : state.inputs.get(key);
      return input == null ? null : input.info().value();
   }

   /** Console-safe structure dump: values are only reported as empty/set, never echoed. */
   public List<String> describe() {
      State state = this.current;
      if (state == null) {
         return List.of();
      }

      List<String> lines = new ArrayList<>();
      lines.add("title: " + state.snapshot.title() + (state.waiting ? " (waiting for server)" : ""));
      for (String body : state.snapshot.body()) {
         lines.add("  " + body);
      }

      for (InputState input : state.inputs.values()) {
         InputInfo info = input.info();
         String value = info.value().isEmpty() ? "empty" : "set";
         lines.add("  input " + info.key() + " [" + info.type() + "] label='" + info.label()
            + "' value=" + value + (info.options().isEmpty() ? "" : " options=" + info.options()));
      }

      for (ButtonEntry button : state.buttons) {
         lines.add("  button [" + button.info().index() + "] '" + button.info().label()
            + "' -> " + button.info().action());
      }

      return lines;
   }

   /** Client thread only. */
   public void setInput(final String key, final String value) {
      State state = requireOpen();
      if (state.waiting) {
         throw new IllegalArgumentException("dialog is waiting for the server");
      }

      InputState currentInput = state.inputs.get(key);
      if (currentInput == null) {
         throw new IllegalArgumentException("unknown dialog input: " + key);
      }

      state.inputs.put(key, updateInput(currentInput, value));
      refreshSnapshot(state);
   }

   /** Client thread only. Accepts a zero-based button index, exact label or custom action id. */
   public void click(final String target) {
      State state = requireOpen();
      if (state.waiting) {
         throw new IllegalArgumentException("dialog is waiting for the server");
      }

      ButtonEntry selected = null;
      if (target.chars().allMatch(Character::isDigit)) {
         int index = Integer.parseInt(target);
         if (index >= 0 && index < state.buttons.size()) {
            selected = state.buttons.get(index);
         }
      } else {
         for (ButtonEntry button : state.buttons) {
            if (button.info().label().equalsIgnoreCase(target) || button.info().action().equalsIgnoreCase(target)) {
               selected = button;
               break;
            }
         }
      }

      if (selected == null) {
         throw new IllegalArgumentException("unknown dialog button: " + target);
      }

      perform(state, selected.action(), state.dialog.common().afterAction());
   }

   /** Client thread only. Runs the dialog's own cancel action when Escape is allowed. */
   public boolean cancel() {
      State state = this.current;
      if (state == null || state.waiting || !state.dialog.common().canCloseWithEscape()) {
         return false;
      }

      perform(state, state.dialog.onCancel(), DialogAction.CLOSE);
      return true;
   }

   private void perform(final State state, final Optional<Action> action, final DialogAction afterAction) {
      Optional<ClickEvent> event = action.flatMap(definition -> definition.createAction(valueGetters(state)));
      if (event.isPresent() && !supported(event.get())) {
         throw new IllegalArgumentException("unsupported dialog action: " + event.get().action());
      }

      if (afterAction == DialogAction.CLOSE) {
         close("action");
      } else if (afterAction == DialogAction.WAIT_FOR_RESPONSE) {
         state.waiting = true;
         refreshSnapshot(state);
      }

      event.ifPresent(click -> dispatch(state.access, click));
   }

   private static void dispatch(final DialogConnectionAccess access, final ClickEvent event) {
      if (event instanceof ClickEvent.RunCommand command) {
         access.runCommand(net.minecraft.commands.Commands.trimOptionalPrefix(command.command()), null);
      } else if (event instanceof ClickEvent.ShowDialog dialog) {
         access.openDialog(dialog.dialog(), null);
      } else if (event instanceof ClickEvent.Custom custom) {
         access.sendCustomAction(custom.id(), custom.payload());
      }
   }

   private static boolean supported(final ClickEvent event) {
      return event instanceof ClickEvent.RunCommand || event instanceof ClickEvent.ShowDialog
         || event instanceof ClickEvent.Custom;
   }

   private State requireOpen() {
      State state = this.current;
      if (state == null) {
         throw new IllegalArgumentException("no dialog is open");
      }

      return state;
   }

   private static InputState initialInput(final net.minecraft.server.dialog.Input input) {
      String value = switch (input.control()) {
         case TextInput text -> text.initial();
         case BooleanInput bool -> Boolean.toString(bool.initial());
         case SingleOptionInput option -> option.initial().orElse(option.entries().getFirst()).id();
         case NumberRangeInput number -> {
            NumberRangeInput.RangeInfo range = number.rangeInfo();
            float initial = range.initial().orElse((range.start() + range.end()) / 2.0F);
            yield numberString(initial);
         }
         default -> "";
      };
      return new InputState(inputInfo(input, value), input);
   }

   private static InputState updateInput(final InputState state, final String value) {
      net.minecraft.server.dialog.Input definition = state.definition();
      String updated = switch (definition.control()) {
         case TextInput text -> {
            if (value.length() > text.maxLength()) {
               throw new IllegalArgumentException("input '" + definition.key() + "' exceeds maxLength " + text.maxLength());
            }
            yield value;
         }
         case BooleanInput ignored -> switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "on", "1", "yes" -> "true";
            case "false", "off", "0", "no" -> "false";
            default -> throw new IllegalArgumentException("boolean input '" + definition.key() + "' expects true/false");
         };
         case SingleOptionInput option -> {
            String matched = null;
            for (SingleOptionInput.Entry entry : option.entries()) {
               if (entry.id().equalsIgnoreCase(value) || entry.displayOrDefault().getString().equalsIgnoreCase(value)) {
                  matched = entry.id();
                  break;
               }
            }
            if (matched == null) {
               throw new IllegalArgumentException("unknown option for '" + definition.key() + "': " + value);
            }
            yield matched;
         }
         case NumberRangeInput number -> {
            float parsed;
            try {
               parsed = Float.parseFloat(value);
            } catch (NumberFormatException error) {
               throw new IllegalArgumentException("number input '" + definition.key() + "' expects a number");
            }
            NumberRangeInput.RangeInfo range = number.rangeInfo();
            if (!Float.isFinite(parsed) || parsed < Math.min(range.start(), range.end())
               || parsed > Math.max(range.start(), range.end())) {
               throw new IllegalArgumentException("number input '" + definition.key() + "' must be in ["
                  + range.start() + ", " + range.end() + "]");
            }
            float slider = range.start() == range.end() ? 0.5F : (parsed - range.start()) / (range.end() - range.start());
            yield numberString(range.computeScaledValue(slider));
         }
         default -> throw new IllegalArgumentException("unsupported input control: "
            + definition.control().getClass().getSimpleName());
      };
      return new InputState(inputInfo(definition, updated), definition);
   }

   private static InputInfo inputInfo(final net.minecraft.server.dialog.Input input, final String value) {
      return switch (input.control()) {
         case TextInput text -> new InputInfo(input.key(), "TEXT", text.label().getString(), value,
            text.maxLength(), List.of());
         case BooleanInput bool -> new InputInfo(input.key(), "BOOLEAN", bool.label().getString(), value,
            0, List.of(bool.onFalse(), bool.onTrue()));
         case SingleOptionInput option -> new InputInfo(input.key(), "OPTION", option.label().getString(), value,
            0, option.entries().stream().map(SingleOptionInput.Entry::id).toList());
         case NumberRangeInput number -> new InputInfo(input.key(), "NUMBER", number.label().getString(), value,
            0, List.of(numberString(number.rangeInfo().start()), numberString(number.rangeInfo().end())));
         default -> new InputInfo(input.key(), input.control().getClass().getSimpleName(), "", value, 0, List.of());
      };
   }

   private static Map<String, Action.ValueGetter> valueGetters(final State state) {
      Map<String, Action.ValueGetter> getters = new LinkedHashMap<>();
      for (InputState input : state.inputs.values()) {
         getters.put(input.info().key(), getter(input));
      }

      return getters;
   }

   private static Action.ValueGetter getter(final InputState input) {
      return new Action.ValueGetter() {
         @Override
         public String asTemplateSubstitution() {
            if (input.definition().control() instanceof TextInput) {
               return StringTag.escapeWithoutQuotes(input.info().value());
            }
            if (input.definition().control() instanceof BooleanInput bool) {
               return Boolean.parseBoolean(input.info().value()) ? bool.onTrue() : bool.onFalse();
            }

            return input.info().value();
         }

         @Override
         public Tag asTag() {
            if (input.definition().control() instanceof BooleanInput) {
               return ByteTag.valueOf(Boolean.parseBoolean(input.info().value()));
            }
            if (input.definition().control() instanceof NumberRangeInput) {
               return FloatTag.valueOf(Float.parseFloat(input.info().value()));
            }

            return StringTag.valueOf(input.info().value());
         }
      };
   }

   private static List<ButtonEntry> buttonsOf(final Dialog dialog) {
      List<ActionButton> definitions = new ArrayList<>();
      if (dialog instanceof SimpleDialog simple) {
         definitions.addAll(simple.mainActions());
      } else if (dialog instanceof MultiActionDialog multi) {
         definitions.addAll(multi.actions());
      } else if (dialog instanceof DialogListDialog list) {
         for (Holder<Dialog> child : list.dialogs()) {
            Component title = child.value().common().computeExternalTitle();
            definitions.add(new ActionButton(
               new CommonButtonData(title, Optional.empty(), list.buttonWidth()),
               Optional.of(new StaticAction(new ClickEvent.ShowDialog(child)))));
         }
      }

      if (dialog instanceof ButtonListDialog list) {
         list.exitAction().ifPresent(definitions::add);
      }

      List<ButtonEntry> buttons = new ArrayList<>();
      for (ActionButton definition : definitions) {
         int index = buttons.size();
         buttons.add(new ButtonEntry(
            new ButtonInfo(index, definition.button().label().getString(), actionName(definition.action())),
            definition.action()));
      }

      return buttons;
   }

   private static String actionName(final Optional<Action> action) {
      if (action.isEmpty()) {
         return "close";
      }
      if (action.get() instanceof CustomAll custom) {
         return custom.id().toString();
      }
      if (action.get() instanceof StaticAction staticAction) {
         return staticAction.value().action().name();
      }

      return action.get().getClass().getSimpleName();
   }

   private static void refreshSnapshot(final State state) {
      List<String> body = new ArrayList<>();
      for (var element : state.dialog.common().body()) {
         if (element instanceof PlainMessage message) {
            body.add(message.contents().getString());
         } else if (element instanceof ItemBody item) {
            item.description().ifPresent(description -> body.add(description.contents().getString()));
         }
      }

      List<InputInfo> inputs = state.inputs.values().stream().map(InputState::info).toList();
      List<ButtonInfo> buttons = state.buttons.stream().map(ButtonEntry::info).toList();
      state.snapshot = new Snapshot(state.dialog.common().title().getString(), body, inputs, buttons,
         state.dialog.common().canCloseWithEscape(), state.waiting);
   }

   private static String numberString(final float value) {
      int integer = (int)value;
      return integer == value ? Integer.toString(integer) : Float.toString(value);
   }
}
