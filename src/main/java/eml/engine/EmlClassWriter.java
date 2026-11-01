package eml.engine;

import org.objectweb.asm.ClassWriter;

/**
 * Class writer that can recompute stack map frames from inside a class-file transformer.
 *
 * <p>{@code COMPUTE_FRAMES} has to know common superclasses, which means loading the types it is asked
 * about. Doing that while the JVM is itself loading one of those classes is the classic failure, so the
 * loader is passed explicitly and any type that cannot be resolved - above all the class currently being
 * transformed - degrades to {@code java/lang/Object}. That is always verifier-correct (merging two
 * unrelated types to Object is legal), it only costs a little precision.
 */
public final class EmlClassWriter extends ClassWriter {
   private final ClassLoader loader;

   public EmlClassWriter(final int flags, final ClassLoader loader) {
      super(flags);
      this.loader = loader;
   }

   @Override
   protected String getCommonSuperClass(final String type1, final String type2) {
      if (type1.equals(type2)) {
         return type1;
      }

      try {
         Class<?> first = Class.forName(type1.replace('/', '.'), false, this.loader);
         Class<?> second = Class.forName(type2.replace('/', '.'), false, this.loader);

         if (first.isAssignableFrom(second)) {
            return type1;
         }

         if (second.isAssignableFrom(first)) {
            return type2;
         }

         if (first.isInterface() || second.isInterface()) {
            return "java/lang/Object"; // the spec's answer for interfaces: no common supertype to walk
         }

         do {
            first = first.getSuperclass();
         } while (first != second);

         return first.getName().replace('.', '/');
      } catch (Throwable notYetLoadable) {
         return "java/lang/Object";
      }
   }
}
