package eml.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** The patch rule kinds: donor body copy, call removal, and observe-only method wrapper. */
public abstract class Rules {
   private Rules() {
   }

   /** Base: one rule targets one class and reports its intent for the launch log. */
   public abstract static class Patch {
      public final String owner;

      protected Patch(final String owner) {
         this.owner = owner;
      }

      public abstract void apply(ClassNode node);

      public abstract String describe();

      /**
       * Whether applying this rule can change a method's operand-stack shape, so the class must be written
       * with recomputed stack map frames. Body-copy and wrapper rules keep the frame layout intact; a rule
       * that removes instruction ranges does not.
       */
      public boolean needsFrameRecomputation() {
         return false;
      }

      static MethodNode requireMethod(final ClassNode node, final String name, final String descriptor) {
         List<MethodNode> matches = new ArrayList<>();
         for (MethodNode method : node.methods) {
            if (method.name.equals(name) && (descriptor == null || descriptor.isEmpty() || method.desc.equals(descriptor))) {
               matches.add(method);
            }
         }

         if (matches.isEmpty()) {
            throw new PatchException("no method " + name + (descriptor == null || descriptor.isEmpty() ? "" : descriptor) + " in " + node.name);
         }

         if (matches.size() > 1) {
            throw new PatchException(
               "ambiguous " + name + " in " + node.name + " (" + matches.size() + " overloads): give the exact descriptor");
         }

         return matches.get(0);
      }
   }

   /**
    * Replaces a whole method body with a donor template. Use for lifecycle methods and anywhere the
    * new logic needs control flow, private access or the original super call.
    */
   public static final class BodyReplace extends Patch {
      private final String name;
      private final String descriptor;
      private final Donor donor;

      public BodyReplace(final String owner, final String name, final String descriptor, final Class<?> donorClass, final String donorMethod) {
         this(owner, name, descriptor, new Donor(donorClass, donorMethod));
      }

      public BodyReplace(final String owner, final String name, final String descriptor, final Donor donor) {
         super(owner);
         this.name = name;
         this.descriptor = descriptor;
         this.donor = donor;
      }

      @Override
      public void apply(final ClassNode node) {
         MethodNode target = requireMethod(node, this.name, this.descriptor);
         this.donor.replaceBody(node, target, "<init>".equals(this.name));
      }

      @Override
      public String describe() {
         return "BodyReplace " + this.owner + "." + this.name + this.descriptor + " <- donor " + this.donor;
      }

      @Override
      public String toString() {
         return "BodyReplace[" + this.owner + "." + this.name + this.descriptor + "]";
      }
   }

   /**
    * Removes every call to {@code callOwner.callName(callDesc)} inside {@code targetMethod}.
    * Non-void calls get a POP/POP2 so the operand stack stays balanced. Applied to render, sound and
    * GUI coupling sites inside classes we do not want to replace wholesale.
    */
   public static final class CallDrop extends Patch {
      private final String targetMethod;
      private final String targetDescriptor;
      private final String callOwner;
      private final String callName;
      private final String callDescriptor;
      private final int expectedMin;

      public CallDrop(final String owner, final String targetMethod, final String targetDescriptor, final String callOwner, final String callName, final String callDescriptor) {
         this(owner, targetMethod, targetDescriptor, callOwner, callName, callDescriptor, 1);
      }

      public CallDrop(final String owner, final String targetMethod, final String targetDescriptor, final String callOwner, final String callName, final String callDescriptor, final int expectedMin) {
         super(owner);
         this.targetMethod = targetMethod;
         this.targetDescriptor = targetDescriptor;
         this.callOwner = callOwner;
         this.callName = callName;
         this.callDescriptor = callDescriptor;
         this.expectedMin = expectedMin;
      }

      @Override
      public void apply(final ClassNode node) {
         MethodNode target = requireMethod(node, this.targetMethod, this.targetDescriptor);
         int removed = 0;
         Iterator<AbstractInsnNode> iterator = target.instructions.iterator();

         while (iterator.hasNext()) {
            AbstractInsnNode insn = iterator.next();
            if (insn instanceof MethodInsnNode call
               && call.owner.equals(this.callOwner)
               && call.name.equals(this.callName)
               && call.desc.equals(this.callDescriptor)) {
               Type returned = Type.getReturnType(call.desc);

               if (returned.getSort() != Type.VOID) {
                  int pop = returned.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP;
                  target.instructions.insert(call, new InsnNode(pop));
               }

               target.instructions.remove(call);
               removed++;
            }
         }

         if (removed < this.expectedMin) {
            throw new PatchException(
               "CallDrop expected >= " + this.expectedMin + " call(s) to " + this.callOwner + "." + this.callName + " in " + this.owner + "." + this.targetMethod + ", found " + removed);
         }
      }

      @Override
      public String describe() {
         return "CallDrop " + this.owner + "." + this.targetMethod + " : drop " + this.callOwner + "." + this.callName + this.callDescriptor;
      }

      @Override
      public String toString() {
         return "CallDrop[" + this.owner + "." + this.targetMethod + " -> " + this.callOwner + "." + this.callName + "]";
      }
   }

   /**
    * Removes whole statements that read one of the render/GUI fields the headless client leaves null -
    * e.g. {@code this.minecraft.gui.getChat().restoreState(state);} or
    * {@code this.minecraft.levelRenderer.startClientLoad(player, level, renderer);}.
    *
    * <p>{@link CallDrop} cannot express those: it deletes a single call, but here the NULL receiver is the
    * FIRST call of the chain, so deleting only the tail leaves the argument loads and an unbalanced stack
    * (and deleting the head breaks the tail). This rule therefore removes the entire sub-expression plus
    * its consuming store, found by simulating operand-stack depth: the statement starts at the loads that
    * produce the field value and ends once the stack is back where it started. Anything that does not
    * balance (the value feeding a larger expression) raises instead of silently corrupting the frame, so
    * {@code PatchCheck} turns such a case into a clear error rather than a VerifyError at launch.
    */
   public static final class StatementDrop extends Patch {
      private static final String MINECRAFT = "net/minecraft/client/Minecraft";
      private final String targetMethod;
      private final String targetDescriptor;
      private final String fieldName;
      private final int expectedMin;

      /** Why the last site was skipped; null when every site was removable. Read by the offline probe. */
      public String lastSkip;

      public StatementDrop(final String owner, final String targetMethod, final String targetDescriptor, final String fieldName) {
         this(owner, targetMethod, targetDescriptor, fieldName, 1);
      }

      /**
       * @param fieldName the nullable source to remove statements around: either a {@code Minecraft} field
       *                  name ({@code gui}) or an accessor written as {@code #getSoundManager}. Accessors
       *                  matter because plenty of render/GUI code reaches the object through a getter, and
       *                  anchoring on the OUTERMOST nullable value is what makes a whole statement removable
       *                  ({@code soundManager.playDelayed(new DirectionalSoundInstance(..., camera, ...))}
       *                  cannot be bounded from {@code gameRenderer}, whose read sits inside the arguments).
       */
      public StatementDrop(final String owner, final String targetMethod, final String targetDescriptor, final String fieldName, final int expectedMin) {
         super(owner);
         this.targetMethod = targetMethod;
         this.targetDescriptor = targetDescriptor;
         this.fieldName = fieldName;
         this.expectedMin = expectedMin;
      }

      /** Whether the source is a {@code Minecraft} accessor rather than a field. */
      private boolean isAccessor() {
         return this.fieldName.startsWith("#");
      }

      /**
       * Render objects are not stored only in {@code Minecraft}: {@code ClientLevel} keeps the
       * {@code LevelRenderer} it was handed at construction, and six of its methods dereference that field of
       * THEIR OWN class (block updates, section dirty marks). A source written {@code ~levelRenderer} matches
       * any field read whose declared type is that class, whatever the owner - the only way those sites are
       * findable at all.
       */
      private static final java.util.Map<String, String> TYPE_SOURCES = java.util.Map.of(
         "levelRenderer", "Lnet/minecraft/client/renderer/LevelRenderer;",
         "gameRenderer", "Lnet/minecraft/client/renderer/GameRenderer;",
         "particleEngine", "Lnet/minecraft/client/particle/ParticleEngine;",
         "soundManager", "Lnet/minecraft/client/sounds/SoundManager;",
         "gui", "Lnet/minecraft/client/gui/Gui;");

      /** True when {@code insn} produces the nullable value this rule is anchored on. */
      private boolean isSource(final AbstractInsnNode insn) {
         if (this.fieldName.startsWith("~")) {
            String expected = TYPE_SOURCES.get(this.fieldName.substring(1));

            if (expected == null) {
               throw new PatchException("StatementDrop: unknown typed source " + this.fieldName);
            }

            return insn instanceof FieldInsnNode read
               && read.getOpcode() == Opcodes.GETFIELD
               && read.desc.equals(expected);
         }

         if (this.isAccessor()) {
            String name = this.fieldName.substring(1);
            return insn instanceof MethodInsnNode call
               && call.getOpcode() == Opcodes.INVOKEVIRTUAL
               && call.owner.equals(MINECRAFT)
               && call.name.equals(name);
         }

         return insn instanceof FieldInsnNode read
            && read.getOpcode() == Opcodes.GETFIELD
            && read.owner.equals(MINECRAFT)
            && read.name.equals(this.fieldName);
      }

      /** The instruction that produces this rule's nullable value, or null. */
      private AbstractInsnNode findSource(final MethodNode target) {
         for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (isSource(insn)) {
               return insn;
            }
         }

         return null;
      }

      @Override
      public void apply(final ClassNode node) {
         MethodNode target = requireMethod(node, this.targetMethod, this.targetDescriptor);
         int removed = 0;

         for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null;) {
            if (isSource(insn)) {
               int dropped;

               try {
                  dropped = dropStatement(target, insn);
               } catch (PatchException unsupportedShape) {
                  // The value is not a self-contained statement (typically: passed as an argument to a
                  // constructor, which is harmless because nothing is invoked on it). Leave it alone and
                  // keep scanning; the expectedMin check below still fails loudly if this rule did nothing.
                  this.lastSkip = unsupportedShape.getMessage();
                  insn = insn.getNext();
                  continue;
               }

               if (dropped == 0) {
                  insn = insn.getNext();
                  continue;
               }

               removed += dropped;
               insn = target.instructions.getFirst();
               continue;
            }

            insn = insn.getNext();
         }

         if (removed < this.expectedMin) {
            throw new PatchException(
               "StatementDrop expected >= " + this.expectedMin + " statement(s) touching Minecraft." + this.fieldName + " in " + this.owner + "." + this.targetMethod + ", removed " + removed);
         }
      }

      /** @return 1 when the statement was removed; throws when its shape is not a droppable statement */
      private int dropStatement(final MethodNode target, final AbstractInsnNode source) {
         AbstractInsnNode start = source;
         int producers = 0;

         // Walk back over the RECEIVER CHAIN only: field reads/casts that transform the chain value, plus at
         // most ONE producer that starts it. The bound matters - a run of producers immediately before the
         // chain is indistinguishable from the previous statement's argument pushes, and swallowing those
         // unbalances the operand stack (that mis-bound is what produced the verifier errors earlier).
         while (true) {
            AbstractInsnNode previous = previousReal(start);

            if (previous == null || !isChainLink(previous)) {
               break;
            }

            if (isChainProducer(previous) && ++producers > 1) {
               break;
            }

            start = previous;
         }

         // Follow the chain's OWN values, not the absolute stack depth: a call in the middle of the chain
         // pops the receiver and pushes its result, so absolute depth returns to zero long before the
         // statement ends (that mis-bound left a dangling store and broke frame simulation).
         boolean[] derived = new boolean[16];
         int live = 0;
         AbstractInsnNode cursor = start;

         while (cursor != null) {
            int[] effect = valueEffect(cursor);

            if (live < effect[0]) {
               throw new PatchException(
                  "StatementDrop: statement touching Minecraft." + this.fieldName + " in " + this.owner + "." + this.targetMethod + " consumes values outside itself");
            }

            boolean chainResult = false;

            for (int i = 0; i < effect[0]; i++) {
               live--;
               chainResult |= derived[live];
            }

            for (int i = 0; i < effect[1]; i++) {
               if (live >= derived.length) {
                  throw new PatchException("StatementDrop: expression too wide to bound in " + this.owner + "." + this.targetMethod);
               }

               derived[live++] = chainResult;
            }

            if (live == 0) {
               break;
            }

            cursor = nextReal(cursor);
         }

         if (cursor == null) {
            throw new PatchException(
               "StatementDrop: cannot bound the statement touching Minecraft." + this.fieldName + " in " + this.owner + "." + this.targetMethod);
         }

         // Only a value that is thrown away can be removed with its statement. A store would leave the local
         // uninitialised at its later reads, which the verifier rejects ("bad local variable type"), so those
         // sites must be handled by a donor for the whole method instead.
         int endOpcode = cursor.getOpcode();

         if (isStore(cursor) || endOpcode == Opcodes.RETURN || (endOpcode >= Opcodes.IRETURN && endOpcode <= Opcodes.DRETURN)) {
            throw new PatchException(
               "StatementDrop: Minecraft." + this.fieldName + " value is stored or returned in " + this.owner + "." + this.targetMethod + "; needs a donor");
         }

         // Deleting inside a protected range can empty a try block, which is not representable; skip those.
         for (org.objectweb.asm.tree.TryCatchBlockNode block : nodeOrEmpty(target)) {
            int from = target.instructions.indexOf(block.start);
            int to = target.instructions.indexOf(block.end);
            int first = target.instructions.indexOf(start);
            int last = target.instructions.indexOf(cursor);

            if (first >= 0 && last >= 0 && from >= 0 && to >= 0 && first < to && last >= from) {
               throw new PatchException(
                  "StatementDrop: statement in " + this.owner + "." + this.targetMethod + " lies inside a try/catch range; needs a donor");
            }
         }

         // Labels, frames and line numbers stay: deleting a label could orphan a branch target.
         for (AbstractInsnNode node = start; node != null;) {
            AbstractInsnNode next = node.getNext();

            if (node.getOpcode() != -1) {
               target.instructions.remove(node);
            }

            if (node == cursor) {
               break;
            }

            node = next;
         }

         return 1;
      }

      /**
       * Whether the instruction belongs to the receiver chain that produced the field value: either a link
       * that transforms the chain value (field read, cast) or a self-contained producer (local load,
       * constant, {@code Minecraft.getInstance()}). Calls with arguments are excluded on purpose - walking
       * back over an argument list would swallow the PREVIOUS statement's pushes.
       */
      private static boolean isChainLink(final AbstractInsnNode insn) {
         int opcode = insn.getOpcode();

         if (opcode == Opcodes.GETFIELD || opcode == Opcodes.CHECKCAST) {
            return true; // consumes the chain value, produces the next link
         }

         if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.LDC || opcode == Opcodes.NEW
            || opcode == Opcodes.ACONST_NULL || opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
            return true; // pure producers
         }

         if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.DCONST_1) {
            return true;
         }

         if (insn instanceof VarInsnNode) {
            // ILOAD(26) .. ALOAD(30): the whole load family, ALOAD included.
            return opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD;
         }

         // A no-argument static call, e.g. Minecraft.getInstance() feeding the field read.
         return insn instanceof MethodInsnNode call
            && opcode == Opcodes.INVOKESTATIC
            && Type.getType(call.desc).getArgumentTypes().length == 0;
      }

      /** Chain heads: instructions that push a value without consuming the chain. */
      private static boolean isChainProducer(final AbstractInsnNode insn) {
         int opcode = insn.getOpcode();

         if (opcode == Opcodes.GETFIELD || opcode == Opcodes.CHECKCAST) {
            return false; // these consume the chain
         }

         return isChainLink(insn);
      }

      /** The instruction list's try/catch blocks live on the method node; guarded for older shapes. */
      private static List<org.objectweb.asm.tree.TryCatchBlockNode> nodeOrEmpty(final MethodNode target) {
         return target.tryCatchBlocks == null ? List.of() : target.tryCatchBlocks;
      }

      /** The previous instruction that actually affects the operand stack (labels/frames/lines carry none). */
      private static AbstractInsnNode previousReal(final AbstractInsnNode from) {
         AbstractInsnNode cursor = from.getPrevious();

         while (cursor != null && cursor.getOpcode() == -1) {
            cursor = cursor.getPrevious();
         }

         return cursor;
      }

      private static AbstractInsnNode nextReal(final AbstractInsnNode from) {
         AbstractInsnNode cursor = from.getNext();

         while (cursor != null && cursor.getOpcode() == -1) {
            cursor = cursor.getNext();
         }

         return cursor;
      }

      private static boolean isStore(final AbstractInsnNode insn) {
         if (insn == null) {
            return false;
         }

         int opcode = insn.getOpcode();
         return opcode == Opcodes.ISTORE || opcode == Opcodes.FSTORE || opcode == Opcodes.ASTORE || opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE;
      }

      private static boolean isPop(final AbstractInsnNode insn) {
         if (insn == null) {
            return false;
         }

         int opcode = insn.getOpcode();
         return opcode == Opcodes.POP || opcode == Opcodes.POP2;
      }

      /** {valuesPopped, valuesPushed} - slot counts folded to one value per long/double. */
      private int[] valueEffect(final AbstractInsnNode insn) {
         int opcode = insn.getOpcode();

         if (opcode == -1) {
            return new int[]{0, 0};
         }

         if (insn instanceof MethodInsnNode call) {
            Type methodType = Type.getType(call.desc);
            int popped = methodType.getArgumentTypes().length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
            int pushed = methodType.getReturnType().getSort() == Type.VOID ? 0 : 1;
            return new int[]{popped, pushed};
         }

         if (insn instanceof InvokeDynamicInsnNode dynamic) {
            Type methodType = Type.getType(dynamic.desc);
            return new int[]{methodType.getArgumentTypes().length, methodType.getReturnType().getSort() == Type.VOID ? 0 : 1};
         }

         if (insn instanceof FieldInsnNode) {
            return switch (opcode) {
               case Opcodes.GETFIELD -> new int[]{1, 1};
               case Opcodes.GETSTATIC -> new int[]{0, 1};
               case Opcodes.PUTFIELD -> new int[]{2, 0};
               default -> new int[]{1, 0}; // PUTSTATIC
            };
         }

         if (insn instanceof VarInsnNode) {
            return switch (opcode) {
               case Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD, Opcodes.LLOAD, Opcodes.DLOAD -> new int[]{0, 1};
               case Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE, Opcodes.LSTORE, Opcodes.DSTORE -> new int[]{1, 0};
               default -> throw new PatchException("StatementDrop: unexpected variable opcode " + opcode + " in " + this.owner);
            };
         }

         if (insn instanceof LdcInsnNode || insn instanceof JumpInsnNode || insn instanceof LookupSwitchInsnNode
            || insn instanceof TableSwitchInsnNode || insn instanceof IincInsnNode) {
            if (insn instanceof JumpInsnNode || insn instanceof LookupSwitchInsnNode || insn instanceof TableSwitchInsnNode
               || insn instanceof IincInsnNode) {
               throw new PatchException(
                  "StatementDrop: refusing to bound a statement containing a branch in " + this.owner + "." + this.targetMethod);
            }

            return new int[]{0, 1};
         }

         if (insn instanceof TypeInsnNode) {
            return opcode == Opcodes.NEW || opcode == Opcodes.ANEWARRAY ? new int[]{0, 1} : new int[]{1, 1}; // cast/instanceof
         }

         if (insn instanceof IntInsnNode array) {
            return opcode == Opcodes.NEWARRAY ? new int[]{1, 1} : new int[]{0, 1};
         }

         if (insn instanceof MultiANewArrayInsnNode many) {
            return new int[]{many.dims, 1};
         }

         return switch (opcode) {
            case Opcodes.NOP -> new int[]{0, 0};
            case Opcodes.ACONST_NULL, Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3,
                 Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.FCONST_0, Opcodes.FCONST_1,
                 Opcodes.FCONST_2, Opcodes.DCONST_0, Opcodes.DCONST_1, Opcodes.BIPUSH, Opcodes.SIPUSH -> new int[]{0, 1};
            // Arithmetic, conversions and comparisons: particle and sound code is full of these.
            case Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG, Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.L2I,
                 Opcodes.L2F, Opcodes.L2D, Opcodes.F2I, Opcodes.F2L, Opcodes.F2D, Opcodes.D2I, Opcodes.D2L, Opcodes.D2F,
                 Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD, Opcodes.BALOAD,
                 Opcodes.CALOAD, Opcodes.SALOAD, Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG,
                 Opcodes.ARRAYLENGTH -> new int[]{1, 1};
            case Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD, Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB,
                 Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL, Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV,
                 Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR,
                 Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.LAND, Opcodes.LOR,
                 Opcodes.LXOR -> new int[]{2, 1};
            case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE,
                 Opcodes.CASTORE, Opcodes.SASTORE -> new int[]{3, 0};
            case Opcodes.POP -> new int[]{1, 0};
            case Opcodes.POP2 -> new int[]{2, 0};
            case Opcodes.DUP -> new int[]{1, 2};
            case Opcodes.DUP_X1 -> new int[]{2, 3};
            case Opcodes.DUP_X2, Opcodes.DUP2_X2 -> new int[]{0, 0}; // not something we can bound safely
            case Opcodes.DUP2 -> new int[]{1, 2};
            case Opcodes.DUP2_X1 -> new int[]{3, 4};
            case Opcodes.SWAP -> new int[]{2, 2};
            case Opcodes.CHECKCAST -> new int[]{1, 1};
            case Opcodes.INSTANCEOF -> new int[]{1, 1};
            case Opcodes.ATHROW -> new int[]{1, 0};
            case Opcodes.RETURN -> new int[]{0, 0};
            case Opcodes.IRETURN, Opcodes.FRETURN, Opcodes.ARETURN, Opcodes.LRETURN, Opcodes.DRETURN -> new int[]{1, 0};
            default -> throw new PatchException(
               "StatementDrop: unhandled opcode " + opcode + " while bounding a statement in " + this.owner + "." + this.targetMethod);
         };
      }

      /** push - pop for the instruction, counted in operand-stack slots (long/double take two). */
      @SuppressWarnings("unused")
      private int netStackEffect(final AbstractInsnNode insn) {
         int opcode = insn.getOpcode();

         if (opcode == -1) {
            return 0;
         }

         if (insn instanceof MethodInsnNode call) {
            return Type.getType(call.desc).getReturnType().getSize() - Type.getType(call.desc).getArgumentTypes().length;
         }

         if (insn instanceof InvokeDynamicInsnNode dynamic) {
            return Type.getType(dynamic.desc).getReturnType().getSize() - Type.getType(dynamic.desc).getArgumentTypes().length;
         }

         if (insn instanceof FieldInsnNode field) {
            int size = Type.getType(field.desc).getSize();

            return switch (opcode) {
               case Opcodes.GETFIELD -> size - 1;
               case Opcodes.GETSTATIC -> size;
               default -> -(size + 1); // PUTFIELD, PUTSTATIC
            };
         }

         if (insn instanceof LdcInsnNode constant) {
            return constant.cst instanceof Long || constant.cst instanceof Double ? 2 : 1;
         }

         if (insn instanceof IntInsnNode array) {
            return opcode == Opcodes.NEWARRAY ? 0 : 1;
         }

         if (insn instanceof MultiANewArrayInsnNode many) {
            return 1 - many.dims;
         }

         if (insn instanceof VarInsnNode) {
            return switch (opcode) {
               case Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD -> 1;
               case Opcodes.LLOAD, Opcodes.DLOAD -> 2;
               case Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE -> -1;
               default -> -2; // LSTORE, DSTORE
            };
         }

         if (insn instanceof TypeInsnNode) {
            return opcode == Opcodes.NEW || opcode == Opcodes.ANEWARRAY ? 1 : 0;
         }

         if (insn instanceof JumpInsnNode || insn instanceof LookupSwitchInsnNode || insn instanceof TableSwitchInsnNode
            || insn instanceof IincInsnNode) {
            // A branch inside the range means the statement is not linear, so its stack depth cannot be
            // followed: refuse rather than remove a range that some other branch target might live in.
            throw new PatchException(
               "StatementDrop: refusing to bound a statement containing a branch in " + this.owner + "." + this.targetMethod);
         }

         return switch (opcode) {
            case Opcodes.NOP -> 0;
            case Opcodes.ACONST_NULL, Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3,
                 Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> 1;
            case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 -> 2;
            case Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD -> 1;
            case Opcodes.LLOAD, Opcodes.DLOAD -> 2;
            case Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE -> -1;
            case Opcodes.LSTORE, Opcodes.DSTORE -> -2;
            case Opcodes.POP -> -1;
            case Opcodes.POP2 -> -2;
            case Opcodes.DUP -> 1;
            case Opcodes.DUP_X1 -> 2;
            case Opcodes.DUP_X2 -> 3;
            case Opcodes.DUP2 -> 2;
            case Opcodes.DUP2_X1 -> 3;
            case Opcodes.DUP2_X2 -> 4;
            case Opcodes.SWAP, Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> 0;
            case Opcodes.ATHROW -> -1;
            case Opcodes.RETURN -> 0;
            case Opcodes.IRETURN, Opcodes.FRETURN, Opcodes.ARETURN -> -1;
            case Opcodes.LRETURN, Opcodes.DRETURN -> -2;
            default -> throw new PatchException("StatementDrop: unhandled opcode " + opcode + " while bounding a statement in " + this.owner);
         };
      }

      @Override
      public boolean needsFrameRecomputation() {
         // Removing a statement changes local/stack occupancy at every branch target after it.
         return true;
      }

      /** How the source reads in logs: a Minecraft field, a Minecraft accessor, or a typed field anywhere. */
      private String sourceLabel() {
         if (this.fieldName.startsWith("~")) {
            return "any field of type " + this.fieldName.substring(1);
         }

         return this.isAccessor() ? "Minecraft" + this.fieldName + "()" : "Minecraft." + this.fieldName;
      }

      @Override
      public String describe() {
         return "StatementDrop " + this.owner + "." + this.targetMethod + " : statements touching " + sourceLabel();
      }

      @Override
      public String toString() {
         return "StatementDrop[" + this.owner + "." + this.targetMethod + " : Minecraft." + this.fieldName + "]";
      }
   }

   /**
    * Wraps an instance method with an observation hook: the original body is renamed
    * {@code eml$<name>} and the generated dispatcher calls {@code BotHooks.<hook>(this, args...)}
    * before delegating. The hook returns void, so the generated method is straight-line code and
    * needs no stack map frames. Where cancellation is required, use {@link BodyReplace} instead and
    * branch in the donor.
    */
   public static final class Wrap extends Patch {
      private static final String HOOK_OWNER = "eml/hooks/BotHooks";
      private final String name;
      private final String descriptor;
      private final String hookName;
      private final String hookOwner;
      private final boolean eraseParameters;
      private final boolean after;

      public Wrap(final String owner, final String name, final String descriptor, final String hookName) {
         this(owner, name, descriptor, HOOK_OWNER, hookName, false);
      }

      /**
       * Wraps so the hook runs either before or after the original body.
       *
       * <p>{@code after} exists for events whose subject only exists once vanilla has done its work - the
       * entity that {@code handleAddEntity} spawns, the menu that {@code handleOpenScreen} builds. Ordering
       * with {@link StatementDrop} on the same method matters: a drop has to be registered first, because
       * once this rule renames the body to {@code eml$<name>}, the drop would look at the new dispatcher and
       * find nothing to remove.
       *
       * <p>{@code eraseParameters} passes every reference argument as {@code java/lang/Object} so the hook can
       * be written without naming the original parameter types. Needed for methods whose arguments belong to a
       * library that is on the runtime classpath but is not a compile dependency (netty); passing a subtype
       * where the descriptor declares Object is valid, so no cast instruction is required.
       */
      public Wrap(final String owner, final String name, final String descriptor, final Class<?> hookClass, final String hookName, final boolean eraseParameters, final boolean after) {
         this(owner, name, descriptor, hookClass.getName().replace('.', '/'), hookName, eraseParameters, after);
      }

      public Wrap(final String owner, final String name, final String descriptor, final Class<?> hookClass, final String hookName, final boolean eraseParameters) {
         this(owner, name, descriptor, hookClass, hookName, eraseParameters, false);
      }

      public Wrap(final String owner, final String name, final String descriptor, final String hookOwner, final String hookName, final boolean eraseParameters) {
         this(owner, name, descriptor, hookOwner, hookName, eraseParameters, false);
      }

      public Wrap(final String owner, final String name, final String descriptor, final String hookOwner, final String hookName, final boolean eraseParameters, final boolean after) {
         super(owner);
         this.name = name;
         this.descriptor = descriptor;
         this.hookName = hookName;
         this.hookOwner = hookOwner;
         this.eraseParameters = eraseParameters;
         this.after = after;
      }

      @Override
      public void apply(final ClassNode node) {
         MethodNode original = requireMethod(node, this.name, this.descriptor);

         if ((original.access & Opcodes.ACC_STATIC) != 0) {
            throw new PatchException("Wrap only supports instance methods, got static " + node.name + "." + this.name);
         }

         if ((original.access & Opcodes.ACC_ABSTRACT) != 0) {
            throw new PatchException("Wrap cannot wrap abstract " + node.name + "." + this.name + " (no body to delegate to)");
         }

         String delegated = "eml$" + this.name;

         for (MethodNode method : node.methods) {
            if (method.name.equals(delegated) && method.desc.equals(original.desc)) {
               // Wrapping twice would make the dispatcher delegate to itself: infinite recursion.
               throw new PatchException("already wrapped: " + node.name + "." + this.name + this.descriptor);
            }
         }

         // Overloads share a base name, so the second wrapped overload needs its own delegated name.
         for (int suffix = 2; containsMethod(node, delegated); suffix++) {
            delegated = "eml$" + this.name + "$" + suffix;
         }

         // The registered descriptor is optional (name matching suffices when unique); the generated
         // dispatcher must nevertheless carry the real one.
         String descriptor = this.descriptor == null || this.descriptor.isEmpty() ? original.desc : this.descriptor;
         Type[] arguments = Type.getArgumentTypes(descriptor);
         Type returned = Type.getReturnType(descriptor);
         original.name = delegated;

         MethodNode dispatcher = new MethodNode(original.access, this.name, descriptor, original.signature, original.exceptions.toArray(new String[0]));
         int paramSlots = 1;

         for (Type argument : arguments) {
            paramSlots += argument.getSize();
         }

         // Hook signature: (selfType, ...same arguments...)void - assembled from the argument descriptors
         // so no parenthesis bookkeeping can go wrong.
         StringBuilder hookDescriptor = new StringBuilder("(L").append(node.name).append(';');

         for (Type argument : arguments) {
            if (this.eraseParameters) {
               if (argument.getSort() != Type.OBJECT && argument.getSort() != Type.ARRAY) {
                  throw new PatchException(
                     "Wrap with erased parameters cannot pass primitive " + argument + " of " + node.name + "." + this.name);
               }

               hookDescriptor.append("Ljava/lang/Object;");
            } else {
               hookDescriptor.append(argument.getDescriptor());
            }
         }

         hookDescriptor.append(")V");

         if (this.after) {
            // Delegating first means the hook runs on state the vanilla body just produced; a value returning
            // method would need its result duplicated around the hook, which nothing here requires.
            if (returned.getSort() != Type.VOID) {
               throw new PatchException(
                  "Wrap with an after-hook only supports void methods, got " + node.name + "." + this.name + descriptor);
            }

            this.delegate(dispatcher, node.name, delegated, descriptor, arguments);
            this.invokeHook(dispatcher, this.hookOwner, this.hookName, hookDescriptor.toString(), arguments);
         } else {
            this.invokeHook(dispatcher, this.hookOwner, this.hookName, hookDescriptor.toString(), arguments);
            this.delegate(dispatcher, node.name, delegated, descriptor, arguments);
         }

         // 3) return whatever the original produced
         dispatcher.instructions.add(new InsnNode(returnOpcode(returned)));

         dispatcher.maxLocals = paramSlots;
         dispatcher.maxStack = paramSlots + 1;
         node.methods.add(dispatcher);
      }

      private static boolean containsMethod(final ClassNode node, final String name) {
         for (MethodNode method : node.methods) {
            if (method.name.equals(name)) {
               return true;
            }
         }

         return false;
      }

      private void invokeHook(
         final MethodNode dispatcher, final String owner, final String method, final String hookDescriptor, final Type[] arguments
      ) {
         dispatcher.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
         this.loadArguments(dispatcher, arguments);
         dispatcher.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, method, hookDescriptor, false));
      }

      private void delegate(
         final MethodNode dispatcher, final String owner, final String delegated, final String descriptor, final Type[] arguments
      ) {
         dispatcher.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
         this.loadArguments(dispatcher, arguments);
         dispatcher.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, delegated, descriptor, false));
      }

      private void loadArguments(final MethodNode dispatcher, final Type[] arguments) {
         int slot = 1;

         for (Type argument : arguments) {
            dispatcher.instructions.add(new org.objectweb.asm.tree.VarInsnNode(loadOpcode(argument), slot));
            slot += argument.getSize();
         }
      }

      private static int returnOpcode(final Type returned) {
         return switch (returned.getSort()) {
            case Type.VOID -> Opcodes.RETURN;
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.IRETURN;
            case Type.FLOAT -> Opcodes.FRETURN;
            case Type.LONG -> Opcodes.LRETURN;
            case Type.DOUBLE -> Opcodes.DRETURN;
            default -> Opcodes.ARETURN;
         };
      }

      private static int loadOpcode(final Type argument) {
         return switch (argument.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ILOAD;
            case Type.FLOAT -> Opcodes.FLOAD;
            case Type.LONG -> Opcodes.LLOAD;
            case Type.DOUBLE -> Opcodes.DLOAD;
            default -> Opcodes.ALOAD;
         };
      }

      @Override
      public String describe() {
         return "Wrap " + this.owner + "." + this.name + this.descriptor + " -> " + this.hookOwner + "." + this.hookName + (this.eraseParameters ? " (erased)" : "");
      }

      @Override
      public String toString() {
         return "Wrap[" + this.owner + "." + this.name + "]";
      }
   }
}
