package eml.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Copies the body of a donor template method (compiled from {@code eml.template} against the
 * access-opened compile jar) into a target method of a vanilla class.
 *
 * <p>Convention: a donor is {@code static} and its parameters are {@code (self, ...targetParams...)},
 * i.e. the target instance followed by exactly the target method's parameters. Then the donor's local
 * slot layout is identical to the target's (slot 0 is {@code self} / {@code this}), so the copy is
 * slot-for-slot identical: stack map frames and try/catch ranges transfer verbatim, and the
 * transformer never needs {@code COMPUTE_FRAMES} (which would force class loading during transform).
 *
 * <p>Why private access works after the copy: access control is evaluated against the class that
 * CONTAINS the instruction. Donor instructions end up inside the vanilla class, so its own private
 * fields, private methods and {@code invokedynamic} sites (whose lookup caller also becomes the
 * patched class) are legal at runtime without widening anything. Donors must therefore only touch
 * their own target class's internals plus the public/package API of everything else.
 */
public final class Donor {
   private final String donorOwner;
   private final String donorName;
   private MethodNode cached;
   private ClassNode donorNode;

   /** Structure-only views of classes a donor references, kept for the accessibility pre-flight. */
   private static final Map<String, ClassNode> STRUCTURE_CACHE = new HashMap<>();

   public Donor(final Class<?> donorClass, final String donorName) {
      this(donorClass.getName().replace('.', '/'), donorName);
   }

   public Donor(final String donorOwner, final String donorName) {
      this.donorOwner = donorOwner;
      this.donorName = donorName;
   }

   private MethodNode load() {
      if (this.cached == null) {
         String resource = this.donorOwner + ".class";
         try (InputStream stream = Donor.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
               throw new PatchException("donor class resource missing: " + resource);
            }

            ClassNode node = new ClassNode();
            new ClassReader(stream.readAllBytes()).accept(node, 0);
            this.donorNode = node;
            for (MethodNode method : node.methods) {
               if (method.name.equals(this.donorName)) {
                  this.cached = method;
                  break;
               }
            }

            if (this.cached == null) {
               throw new PatchException("donor method " + this.donorName + " not found in " + this.donorOwner);
            }
         } catch (PatchException e) {
            throw e;
         } catch (Exception e) {
            throw new PatchException("cannot read donor " + this.donorOwner + "." + this.donorName, e);
         }
      }

      return this.cached;
   }

   /** @return the internal name of the self parameter, used to match it against the target owner. */
   public String selfType() {
      Type[] args = Type.getArgumentTypes(this.load().desc);
      if (args.length == 0) {
         throw new PatchException("donor " + this.donorName + " has no self parameter");
      }

      return args[0].getInternalName();
   }

   /**
    * Replaces {@code target}'s body with the donor body.
    *
    * @param node    the class being transformed (target's owner)
    * @param target  method whose body is replaced
    * @param isInit  when true, the original super/this call prologue is preserved in front of the
    *                donor body (a constructor cannot re-issue its own super call from Java source)
    */
   public void replaceBody(final ClassNode node, final MethodNode target, final boolean isInit) {
      MethodNode donor = this.load();
      this.validate(node, target, donor, isInit);
      this.validateReferences();

      List<AbstractInsnNode> prologue = isInit ? superCallPrologue(target) : List.of();
      Map<LabelNode, LabelNode> labels = new HashMap<>();

      for (AbstractInsnNode insn = donor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
         if (insn instanceof LabelNode label) {
            labels.put(label, new LabelNode(label.getLabel()));
         }
      }

      InsnList replaced = new InsnList();
      for (AbstractInsnNode insn : prologue) {
         replaced.add(insn);
      }

      // javac encodes most stack map frames in the compact "same_locals_*" forms, which inherit their
      // locals from the method's INITIAL frame. A donor is static (slot 0 = an initialised self), but a
      // constructor's initial frame still carries uninitializedThis in slot 0, so transplanting those
      // compact frames verbatim fails verification with "Type Minecraft is not assignable to
      // uninitializedThis". Materialising one explicit full frame at the head of the copied body gives
      // every following compact frame a correct ancestor, and keeps the transformer free of
      // COMPUTE_FRAMES (which would have to load classes while one is being loaded).
      Object[] initialLocals = frameLocals(node.name, target.desc).toArray();
      replaced.add(new LabelNode());
      replaced.add(new FrameNode(Opcodes.F_FULL, initialLocals.length, initialLocals, 0, new Object[0]));

      int bodyOps = 0;
      for (AbstractInsnNode insn = donor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
         if (insn instanceof LineNumberNode) {
            continue;
         }

         replaced.add(insn.clone(labels));
         bodyOps++;
      }

      if (bodyOps == 0) {
         throw new PatchException("donor " + this.donorOwner + "." + this.donorName + " has an empty body");
      }

      target.instructions = replaced;
      target.tryCatchBlocks = new ArrayList<>();
      for (TryCatchBlockNode block : donor.tryCatchBlocks) {
         target.tryCatchBlocks.add(new TryCatchBlockNode(labels.get(block.start), labels.get(block.end), labels.get(block.handler), block.type));
      }

      target.localVariables = new ArrayList<>();
      target.visibleLocalVariableAnnotations = new ArrayList<>();
      target.invisibleLocalVariableAnnotations = new ArrayList<>();

      // Slot layout is identical, so the donor's limits carry over; the constructor prologue and any
      // javac bookkeeping get a small headroom (oversized maxes are always legal).
      target.maxLocals = Math.max(donor.maxLocals, target.maxLocals);
      target.maxStack = Math.max(donor.maxStack, prologue.size() + 1) + 2;

      // Drop debug leftovers so a stale LocalTableVersion cannot confuse the verifier.
      target.attrs = new ArrayList<>();
   }

   /**
    * The frame-local entries for a method's initial state: {@code self} plus its parameters, with the
    * second slot of every long/double written as {@code TOP} as the verification spec requires.
    *
    * <p>ASM wants its own encoding here: {@code Opcodes} constants for primitives, internal-name
    * strings for object and array types (a {@link Type} instance would fail with a ClassCastException).
    */
   private static List<Object> frameLocals(final String owner, final String descriptor) {
      List<Object> locals = new ArrayList<>();
      locals.add(owner);

      for (Type argument : Type.getArgumentTypes(descriptor)) {
         locals.add(frameEntry(argument));

         if (argument.getSize() == 2) {
            locals.add(Opcodes.TOP);
         }
      }

      return locals;
   }

   private static Object frameEntry(final Type type) {
      return switch (type.getSort()) {
         case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.INTEGER;
         case Type.FLOAT -> Opcodes.FLOAT;
         case Type.LONG -> Opcodes.LONG;
         case Type.DOUBLE -> Opcodes.DOUBLE;
         // Array types keep their descriptor; object types must be the bare internal name, because ASM
         // writes the frame entry straight into the constant pool ("Lfoo/Bar;" is not a legal class name).
         case Type.ARRAY -> type.getDescriptor();
         case Type.OBJECT -> type.getInternalName();
         default -> Opcodes.TOP;
      };
   }

   private void validate(final ClassNode node, final MethodNode target, final MethodNode donor, final boolean isInit) {
      if ((donor.access & Opcodes.ACC_STATIC) == 0) {
         throw new PatchException("donor must be static: " + donor.name);
      }

      List<Type> donorArgs = List.of(Type.getType(donor.desc).getArgumentTypes());
      List<Type> targetArgs = List.of(Type.getType(target.desc).getArgumentTypes());
      String self = donorArgs.get(0).getInternalName();

      if (!self.equals(node.name)) {
         throw new PatchException(
            "donor " + donor.name + " self type " + self + " != target owner " + node.name + "; use (TargetClass self, ...)");
      }

      if (!donorArgs.subList(1, donorArgs.size()).equals(targetArgs)) {
         throw new PatchException(
            "donor parameters " + donorArgs.subList(1, donorArgs.size()) + " != target parameters " + targetArgs + " for " + node.name + "." + target.name + target.desc);
      }

      Type donorReturn = Type.getReturnType(donor.desc);
      Type targetReturn = Type.getReturnType(target.desc);
      if (isInit) {
         if (donorReturn.getSort() != Type.VOID) {
            throw new PatchException("constructor donor must return void: " + donor.name);
         }
      } else if (!donorReturn.equals(targetReturn)) {
         throw new PatchException("donor return " + donorReturn + " != target return " + targetReturn + " for " + target.name);
      }
   }

   /**
    * A donor body is copied <em>into</em> the target class, so every reference it makes is resolved against
    * the target class, not against the donor. A private or package-private member of the donor (or of any
    * other bot class) therefore fails at runtime with {@code IllegalAccessError} the first time the path
    * runs - the exact failure this pre-flight turns into a load-time error instead. Target-class internals
    * and public API of everything else stay legal.
    */
   private void validateReferences() {
      MethodNode donor = this.load();

      for (AbstractInsnNode insn = donor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
         if (insn instanceof MethodInsnNode call) {
            this.requirePublic(call.owner, call.name, call.desc, false);
         } else if (insn instanceof FieldInsnNode field) {
            this.requirePublic(field.owner, field.name, field.desc, true);
         } else if (insn instanceof TypeInsnNode type) {
            this.requirePublic(type.desc, null, null, false);
         }
      }
   }

   private void requirePublic(final String rawOwner, final String name, final String desc, final boolean field) {
      String owner = normalizeType(rawOwner);
      boolean ownClass = owner.equals(this.donorOwner);
      if (!ownClass && !owner.startsWith("eml/")) {
         return; // the target class itself and plain vanilla API are the donor's business
      }

      if (ownClass && name == null) {
         return;
      }

      ClassNode node = classNode(owner);
      if (node == null) {
         throw new PatchException("donor " + this.donorName + " references unknown class " + owner);
      }

      if ((node.access & Opcodes.ACC_PUBLIC) == 0) {
         throw new PatchException("donor " + this.donorName + " references non-public class " + owner
            + "; a copied body runs inside the target class, so only public API stays reachable");
      }

      if (name == null) {
         return;
      }

      if (field) {
         for (org.objectweb.asm.tree.FieldNode member : node.fields) {
            if (member.name.equals(name) && member.desc.equals(desc)) {
               requirePublicMember(member.access, owner, name, desc);
               return;
            }
         }
      } else {
         for (MethodNode member : node.methods) {
            if (member.name.equals(name) && member.desc.equals(desc)) {
               requirePublicMember(member.access, owner, name, desc);
               return;
            }
         }
      }

      throw new PatchException("donor " + this.donorName + " references missing member " + owner + "." + name + desc);
   }

   private void requirePublicMember(final int access, final String owner, final String name, final String desc) {
      if ((access & Opcodes.ACC_PUBLIC) == 0) {
         throw new PatchException("donor " + this.donorName + " references non-public member " + owner + "." + name + desc
            + "; inline it or make it public, or the copied body fails with IllegalAccessError at runtime");
      }
   }

   /** Strips array markers so {@code [Leml/Foo;} and {@code Leml/Foo;} both yield {@code eml/Foo}. */
   private static String normalizeType(final String desc) {
      String value = desc;
      while (value.startsWith("[")) {
         value = value.substring(1);
      }

      if (value.startsWith("L") && value.endsWith(";")) {
         return value.substring(1, value.length() - 1);
      }

      return value;
   }

   /** Reads a class's structure (no linking, no initialisation) for the accessibility pre-flight. */
   private static synchronized ClassNode classNode(final String owner) {
      ClassNode cached = STRUCTURE_CACHE.get(owner);
      if (cached != null) {
         return cached;
      }

      String resource = owner + ".class";
      try (InputStream stream = Donor.class.getClassLoader().getResourceAsStream(resource)) {
         if (stream == null) {
            return null;
         }

         ClassNode node = new ClassNode();
         new ClassReader(stream.readAllBytes()).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
         STRUCTURE_CACHE.put(owner, node);
         return node;
      } catch (IOException e) {
         throw new PatchException("cannot read " + resource, e);
      }
   }

   /** Clones every instruction up to and including the first {@code INVOKESPECIAL <init>} (super/this call). */
   private static List<AbstractInsnNode> superCallPrologue(final MethodNode init) {
      List<AbstractInsnNode> prefix = new ArrayList<>();

      for (AbstractInsnNode insn = init.instructions.getFirst(); insn != null; insn = insn.getNext()) {
         if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof org.objectweb.asm.tree.FrameNode) {
            if (prefix.isEmpty()) {
               continue;
            }
         }

         prefix.add(insn.clone(Map.of()));
         if (insn instanceof MethodInsnNode call && "<init>".equals(call.name)) {
            return prefix;
         }
      }

      throw new PatchException("no super/this <init> call found in the original constructor");
   }
}
