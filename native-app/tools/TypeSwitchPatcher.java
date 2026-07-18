import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches all invokedynamic typeSwitch calls (Java 21 pattern matching) to use
 * traditional instanceof + checkcast chains.
 *
 * Java 21's javac compiles chains of `if (obj instanceof Type t) ...` into:
 *   aload obj
 *   iload matchIdx
 *   invokedynamic typeSwitch:(Object, int) -> int
 *   tableswitch { -1: noMatch, 0: case0, 1: case1, ... default: fallthrough }
 *
 * D8/Android cannot handle this invokedynamic. This patcher replaces it with:
 *   aload obj
 *   instanceof Type0
 *   ifne case0_label
 *   aload obj
 *   instanceof Type1
 *   ifne case1_label
 *   ...
 *   goto default_label
 *
 * The case bodies (checkcast + work) and goto return_point are unchanged.
 */
public class TypeSwitchPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: TypeSwitchPatcher <jar> [jar2 ...]");
            System.exit(1);
        }

        for (String arg : args) {
            File jarFile = new File(arg);
            if (!jarFile.exists()) {
                System.err.println("JAR not found: " + arg);
                continue;
            }
            processJar(jarFile);
        }
    }

    private static void processJar(File jarFile) throws Exception {
        File tmpJar = new File(jarFile.getAbsolutePath() + ".typeswitch_tmp");
        int totalPatched = 0;

        ZipFile zipIn = new ZipFile(jarFile);
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));

        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) {
                data = is.readAllBytes();
            }

            if (entry.getName().endsWith(".class")) {
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);

                int patched = processClass(cn);
                if (patched > 0) {
                    totalPatched += patched;
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                        @Override
                        protected String getCommonSuperClass(String type1, String type2) {
                            try { return super.getCommonSuperClass(type1, type2); }
                            catch (Exception e) { return "java/lang/Object"; }
                        }
                    };
                    cn.accept(cw);
                    data = cw.toByteArray();
                }
            }

            zipOut.putNextEntry(new ZipEntry(entry.getName()));
            zipOut.write(data);
            zipOut.closeEntry();
        }

        zipIn.close();
        zipOut.close();

        // Replace original with patched
        File origBak = new File(jarFile.getAbsolutePath() + ".typeswitch_bak");
        jarFile.renameTo(origBak);
        tmpJar.renameTo(jarFile);
        origBak.delete();

        if (totalPatched > 0) {
            System.out.println("TypeSwitchPatcher: patched " + totalPatched + " typeSwitch calls in " + jarFile.getName());
        }
    }

    private static int processClass(ClassNode cn) {
        int totalPatched = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) continue;
            int patched = processMethod(cn, mn);
            totalPatched += patched;
        }
        return totalPatched;
    }

    private static int processMethod(ClassNode cn, MethodNode mn) {
        int patched = 0;
        List<AbstractInsnNode> toRemove = new ArrayList<>();

        List<InvokeDynamicInsnNode> dynNodes = new ArrayList<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof InvokeDynamicInsnNode idn) {
                if (idn.bsm != null && "typeSwitch".equals(idn.bsm.getName())) {
                    dynNodes.add(idn);
                }
            }
        }

        for (InvokeDynamicInsnNode idn : dynNodes) {
            try {
                patchTypeSwitch(cn, mn, idn);
                patched++;
            } catch (Exception e) {
                System.err.println("Warning: failed to patch typeSwitch in " + cn.name + "." + mn.name + ": " + e.getMessage());
            }
        }
        return patched;
    }

    private static void patchTypeSwitch(ClassNode cn, MethodNode mn, InvokeDynamicInsnNode idn) {
        // --- Step 1: Extract bootstrap args (the Class types being matched) ---
        // bsmArgs should be Type objects for class constants
        List<Type> caseTypes = new ArrayList<>();
        for (Object arg : idn.bsmArgs) {
            if (arg instanceof Type type) {
                caseTypes.add(type);
            } else if (arg instanceof ConstantDynamic cd) {
                // ConstantDynamic referencing a class - try to resolve the type from its descriptor
                caseTypes.add(Type.getType(cd.getDescriptor()));
            } else {
                System.err.println("Warning: unexpected bootstrap arg type: " + arg.getClass() + " in " + cn.name);
                return;
            }
        }

        if (caseTypes.isEmpty()) {
            System.err.println("Warning: no case types found in typeSwitch bootstrap args in " + cn.name);
            return;
        }

        // --- Step 2: Find the preceding aload of the scrutinee ---
        // Pattern: aload temp; iload idx; invokedynamic typeSwitch
        AbstractInsnNode prev = idn.getPrevious();
        if (prev == null || !(prev instanceof VarInsnNode iloadInsn) || iloadInsn.getOpcode() != Opcodes.ILOAD) {
            System.err.println("Warning: expected iload before invokedynamic in " + cn.name + "." + mn.name);
            return;
        }

        AbstractInsnNode prevPrev = prev.getPrevious();
        if (prevPrev == null || !(prevPrev instanceof VarInsnNode aloadInsn) || aloadInsn.getOpcode() != Opcodes.ALOAD) {
            System.err.println("Warning: expected aload before iload in " + cn.name + "." + mn.name);
            return;
        }

        int scrutineeLocal = aloadInsn.var;

        // --- Step 3: Find the following TableSwitchInsnNode ---
        AbstractInsnNode next = idn.getNext();
        if (next == null || !(next instanceof TableSwitchInsnNode tsi)) {
            System.err.println("Warning: expected tableswitch after invokedynamic in " + cn.name + "." + mn.name);
            return;
        }

        // --- Step 4: Extract case targets from TableSwitch ---
        int low = tsi.min;  // -1 or 0
        int high = tsi.max; // N-1
        LabelNode defaultLabel = tsi.dflt;

        // The case labels in the TableSwitch: index 0 corresponds to case `low`, index 1 to `low+1`, etc.
        // We need: for case i (where i >= 0), label = tsi.labels.get(i - low)
        // For case -1 (if low == -1), there's no type check - it's the null sentinel, same as default

        // --- Step 5: Build replacement instructions ---
        InsnList insertions = new InsnList();

        // If low == -1, the original code had a null check (or requireNonNull) as case -1
        // We must preserve it: add a null guard before the instanceof chain
        if (low == -1) {
            LabelNode nullCaseLabel = tsi.labels.get(0); // case -1 target
            insertions.add(new VarInsnNode(Opcodes.ALOAD, scrutineeLocal));
            insertions.add(new JumpInsnNode(Opcodes.IFNULL, nullCaseLabel));
        }

        // For each case type (cases 0 through high):
        for (int i = 0; i < caseTypes.size(); i++) {
            int caseIndex = i; // case 0, 1, 2, ...
            if (caseIndex < low || caseIndex > high) continue;

            LabelNode caseLabel = tsi.labels.get(caseIndex - low);
            Type caseType = caseTypes.get(i);

            // aload <scrutinee>
            insertions.add(new VarInsnNode(Opcodes.ALOAD, scrutineeLocal));
            // instanceof <type>
            insertions.add(new TypeInsnNode(Opcodes.INSTANCEOF, caseType.getInternalName()));
            // ifne <caseLabel>
            insertions.add(new JumpInsnNode(Opcodes.IFNE, caseLabel));
        }

        // goto default (no match)
        insertions.add(new JumpInsnNode(Opcodes.GOTO, defaultLabel));

        // --- Step 6: Remove old instructions and insert new ones ---
        // CRITICAL: Capture insertion point BEFORE removing nodes (remove clears next/prev pointers)
        AbstractInsnNode insertionPoint = next.getNext();

        // Remove: aload temp, iload idx, invokedynamic, tableswitch
        mn.instructions.remove(prevPrev);  // aload temp
        mn.instructions.remove(prev);      // iload idx
        mn.instructions.remove(idn);       // invokedynamic
        mn.instructions.remove(next);      // tableswitch

        // Insert the instanceof chain at the captured position
        if (insertionPoint != null) {
            mn.instructions.insertBefore(insertionPoint, insertions);
        } else {
            mn.instructions.add(insertions);
        }
    }
}
