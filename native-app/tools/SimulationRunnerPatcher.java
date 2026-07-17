package com.gama.nativeapp.asm;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;

/**
 * Patches SimulationRunner$1.run() to always release experimentSemaphore.
 *
 * Strategy: Insert release() code before the condition-check block, and redirect
 * all GOTOs (normal path) to target BEFORE the release code. The catch handler
 * falls through to the release code naturally.
 */
public class SimulationRunnerPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: SimulationRunnerPatcher <input-class> <output-class>");
            System.exit(1);
        }

        byte[] classBytes = Files.readAllBytes(Paths.get(args[0]));
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("run".equals(mn.name) && "()V".equals(mn.desc)) {
                patched = patchRunMethod(cn, mn);
            }
        }

        if (!patched) {
            System.err.println("ERROR: Could not patch SimulationRunner$1.run()");
            System.exit(1);
        }

        // Strip all existing stack map frames
        for (MethodNode mn : cn.methods) {
            java.util.List<AbstractInsnNode> toRemove = new java.util.ArrayList<>();
            java.util.Iterator<AbstractInsnNode> it = mn.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode insn = it.next();
                if (insn instanceof FrameNode) {
                    toRemove.add(insn);
                }
            }
            for (AbstractInsnNode insn : toRemove) {
                mn.instructions.remove(insn);
            }
        }

        // Use ClassWriter with COMPUTE_MAXS only — no frame computation.
        // Frames are stripped; D8/R8 handles missing frames.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        Files.write(Paths.get(args[1]), cw.toByteArray());
        System.out.println("Written patched class to: " + args[1]);
    }

    static boolean patchRunMethod(ClassNode cn, MethodNode mn) {
        // Step 1: Find and remove the experimentSemaphore.release() from inside the try block
        AbstractInsnNode[] insns = mn.instructions.toArray();

        int releaseIdx = -1;
        int getfieldExpSemIdx = -1;

        for (int i = 0; i < insns.length; i++) {
            if (insns[i].getOpcode() == Opcodes.GETFIELD) {
                FieldInsnNode fin = (FieldInsnNode) insns[i];
                if ("experimentSemaphore".equals(fin.name)) {
                    if (i + 1 < insns.length && insns[i + 1].getOpcode() == Opcodes.INVOKEVIRTUAL) {
                        MethodInsnNode min = (MethodInsnNode) insns[i + 1];
                        if ("release".equals(min.name) && "()V".equals(min.desc)) {
                            getfieldExpSemIdx = i;
                            releaseIdx = i + 1;
                        }
                    }
                }
            }
        }

        if (releaseIdx < 0) {
            System.err.println("Could not find experimentSemaphore.release() pattern");
            return false;
        }

        // Find chain start (this$0 GETFIELD)
        // Walk backward from GETFIELD experimentSemaphore to find the full chain:
        // ALOAD 0 -> GETFIELD this$0 -> GETFIELD experimentSemaphore -> INVOKEVIRTUAL release
        // We must remove ALL of them to avoid orphaned stack values
        int chainStart = getfieldExpSemIdx;
        for (int i = getfieldExpSemIdx - 1; i >= Math.max(0, getfieldExpSemIdx - 5); i--) {
            int opcode = insns[i].getOpcode();
            if (opcode < 0) continue; // skip labels, frames, line numbers
            if (opcode == Opcodes.GETFIELD) {
                FieldInsnNode fin = (FieldInsnNode) insns[i];
                if ("this$0".equals(fin.name)) {
                    chainStart = i;
                }
            } else if (opcode == Opcodes.ALOAD) {
                chainStart = i;
                break;
            } else {
                break;
            }
        }

        // Remove the entire release chain
        for (int i = chainStart; i <= releaseIdx; i++) {
            mn.instructions.remove(insns[i]);
        }
        System.out.println("Removed experimentSemaphore.release() from try block (insns " + chainStart + "-" + releaseIdx + ")");

        // Step 2: Find the condition check label
        // The condition check is the target of GOTOs. It checks shutdown/dead and loops.
        // Find it by looking for the GOTO targets and the pattern: GETFIELD shutdown + IFNE + GETFIELD dead + IFEQ
        AbstractInsnNode[] freshInsns = mn.instructions.toArray();

        // Find all GOTO instructions
        LabelNode conditionCheckLabel = null;
        for (int i = 0; i < freshInsns.length; i++) {
            if (freshInsns[i].getOpcode() == Opcodes.GOTO) {
                JumpInsnNode jump = (JumpInsnNode) freshInsns[i];
                conditionCheckLabel = jump.label;
                break; // Use the first GOTO's target as the condition check label
            }
        }

        if (conditionCheckLabel == null) {
            System.err.println("Could not find condition check label (no GOTOs found)");
            return false;
        }

        // Verify: the condition check label should be followed by GETFIELD shutdown or GETFIELD dead
        // Actually, it might start with ALOAD_0 + GETFIELD
        System.out.println("Found condition check label, creating release block before it");

        // Step 3: Create the release block
        // Insert BEFORE the condition check label:
        //   release_label:
        //   ALOAD 0
        //   GETFIELD this$0
        //   GETFIELD experimentSemaphore
        //   INVOKEVIRTUAL release()
        LabelNode releaseLabel = new LabelNode();
        InsnList releaseCode = new InsnList();
        releaseCode.add(releaseLabel);
        releaseCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        releaseCode.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "this$0",
            "Lgama/core/runtime/concurrent/SimulationRunner;"));
        releaseCode.add(new FieldInsnNode(Opcodes.GETFIELD,
            "gama/core/runtime/concurrent/SimulationRunner", "experimentSemaphore",
            "Lgama/core/common/interfaces/GeneralSynchronizer;"));
        releaseCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            "gama/core/common/interfaces/GeneralSynchronizer", "release", "()V", false));

        mn.instructions.insertBefore(conditionCheckLabel, releaseCode);

        // Step 4: Redirect all GOTOs to target the release_label instead of conditionCheckLabel
        freshInsns = mn.instructions.toArray();
        int gotoCount = 0;
        for (int i = 0; i < freshInsns.length; i++) {
            if (freshInsns[i].getOpcode() == Opcodes.GOTO) {
                JumpInsnNode jump = (JumpInsnNode) freshInsns[i];
                if (jump.label == conditionCheckLabel) {
                    jump.label = releaseLabel;
                    gotoCount++;
                }
            }
        }

        System.out.println("Redirected " + gotoCount + " GOTOs to release block");
        System.out.println("Patched SimulationRunner$1.run() - experimentSemaphore will always be released");

        return true;
    }
}
