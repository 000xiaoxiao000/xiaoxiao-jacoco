package peruser;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 方法级稳定 hash：跨构建识别「源码/字节码未变」的方法，用于覆盖率增量携带。
 *
 * 做法：用 ASM 解析 class 文件（SKIP_DEBUG，排除行号/局部变量表等调试属性），对每个方法按其
 * 指令序列（含 try/catch、frame，不含行号）算 SHA-256。只要方法体逻辑不变，hash 就不变——
 * 即使别的方法被编辑导致本方法绝对行号平移，也不影响 hash，因此携带时改用「相对行偏移」回填。
 *
 * key 形如 "com/foo/C#a()V"（className#name#desc）。
 */
public final class MethodHasher {

    private MethodHasher() {
    }

    /** 扫描 class 目录 / jar，返回 (className#name#desc) -> hash 的映射。 */
    public static Map<String, String> hashAll(List<File> classPaths) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (File f : classPaths) {
            if (f.isDirectory()) {
                try (java.util.stream.Stream<Path> s = Files.walk(f.toPath())) {
                    List<Path> files = s.filter(x -> x.toString().endsWith(".class"))
                            .collect(java.util.stream.Collectors.toList());
                    for (Path p : files) {
                        process(Files.readAllBytes(p), out);
                    }
                }
            } else if (f.getName().endsWith(".jar")) {
                try (JarFile jf = new JarFile(f)) {
                    for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                        JarEntry je = e.nextElement();
                        if (je.getName().endsWith(".class")) {
                            try (InputStream is = jf.getInputStream(je)) {
                                process(readAll(is), out);
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void process(byte[] b, Map<String, String> out) {
        ClassReader cr = new ClassReader(b);
        String className = cr.getClassName();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodNode(Opcodes.ASM9, access, name, desc, sig, ex) {
                    @Override
                    public void visitEnd() {
                        super.visitEnd();
                        String hash = hashMethod(this);
                        out.put(className + "#" + name + "#" + desc, hash);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
    }

    /** 对单个方法算稳定 hash（基于指令序列，忽略行号）。 */
    static String hashMethod(MethodNode mn) {
        StringBuilder sb = new StringBuilder();
        InsnList ins = mn.instructions;
        Map<LabelNode, Integer> labelIds = new IdentityHashMap<>();
        int li = 0;
        for (int i = 0; i < ins.size(); i++) {
            AbstractInsnNode n = ins.get(i);
            if (n instanceof LabelNode) {
                labelIds.put((LabelNode) n, li++);
            }
        }
        for (int i = 0; i < ins.size(); i++) {
            AbstractInsnNode n = ins.get(i);
            sb.append(n.getOpcode()).append(':');
            if (n instanceof FieldInsnNode) {
                FieldInsnNode f = (FieldInsnNode) n;
                sb.append(f.owner).append('#').append(f.name).append('#').append(f.desc);
            } else if (n instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) n;
                sb.append(m.owner).append('#').append(m.name).append('#').append(m.desc);
            } else if (n instanceof TypeInsnNode) {
                sb.append(((TypeInsnNode) n).desc);
            } else if (n instanceof VarInsnNode) {
                sb.append(((VarInsnNode) n).var);
            } else if (n instanceof IntInsnNode) {
                sb.append(((IntInsnNode) n).operand);
            } else if (n instanceof JumpInsnNode) {
                sb.append(labelIds.getOrDefault(((JumpInsnNode) n).label, -1));
            } else if (n instanceof LdcInsnNode) {
                sb.append(String.valueOf(((LdcInsnNode) n).cst));
            } else if (n instanceof IincInsnNode) {
                IincInsnNode v = (IincInsnNode) n;
                sb.append(v.var).append(',').append(v.incr);
            } else if (n instanceof MultiANewArrayInsnNode) {
                MultiANewArrayInsnNode v = (MultiANewArrayInsnNode) n;
                sb.append(v.desc).append(v.dims);
            } else if (n instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode v = (InvokeDynamicInsnNode) n;
                sb.append(v.name).append(v.desc).append(v.bsm).append(java.util.Arrays.toString(v.bsmArgs));
            } else if (n instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode v = (TableSwitchInsnNode) n;
                sb.append(v.min).append(v.max).append(labelIds.get(v.dflt)).append(v.labels);
            } else if (n instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode v = (LookupSwitchInsnNode) n;
                sb.append(labelIds.get(v.dflt)).append(v.keys).append(v.labels);
            } else if (n instanceof FrameNode) {
                FrameNode v = (FrameNode) n;
                sb.append('F').append(v.type).append(String.valueOf(v.local)).append(String.valueOf(v.stack));
            } else if (n instanceof LabelNode) {
                sb.append('L').append(labelIds.get(n));
            } else if (n instanceof LineNumberNode) {
                // 故意忽略行号：保证逻辑未变、仅行号平移时 hash 不变
            }
            sb.append('|');
        }
        if (mn.tryCatchBlocks != null) {
            for (Object o : mn.tryCatchBlocks) {
                TryCatchBlockNode tc = (TryCatchBlockNode) o;
                sb.append('T').append(labelIds.get(tc.start)).append(labelIds.get(tc.end))
                        .append(labelIds.get(tc.handler)).append(tc.type).append('|');
            }
        }
        return sha256(sb.toString());
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte x : d) {
                hex.append(String.format("%02x", x));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
