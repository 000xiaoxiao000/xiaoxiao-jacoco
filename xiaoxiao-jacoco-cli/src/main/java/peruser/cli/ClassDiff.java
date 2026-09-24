package peruser.cli;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 两版 classfiles 的差异比对：类级（新增/删除/修改）→ 方法级（哪个方法变了）→ 行级（改了哪几行）。
 *
 * 行级有两条路，优先级从高到低：
 *
 * 1. <b>源码 LCS</b>（需要 --sourcefiles + --old-sourcefiles）：对两版源码文本做 LCS，
 *    精度最高，注释行、纯格式行也能识别。
 * 2. <b>指令序列 LCS</b>（只需要两版 class）：把一个方法的字节码指令序列化成
 *    "(opcode + 常量操作数)" 的字符串数组——刻意<b>忽略行号和局部变量槽号</b>——两版做 LCS，
 *    新版里没能匹配上的指令，其所在行号就是变更行。这样即使没有旧版源码也能拿到行级精度。
 *
 * 为什么不能直接对 LineNumberTable 的行号做 LCS：插入一行会让后面所有行号整体 +1，
 * 而且新旧不同内容的行可能恰好行号相同而被判成"没变"（实测 build1/build2 的 web3：
 * 行号 LCS 得出 {15,17}，真相是 {8,12}）。行号会平移、会被"顶替"，必须用指令内容而不是行号。
 */
public final class ClassDiff {

    /** LCS 精确算法的规模上限，超过则退化为按内容计数的近似算法 */
    private static final long MAX_CELLS = 4000000L;

    private ClassDiff() {
    }

    /**
     * @param oldClasses 旧版 classfiles（目录 / jar）
     * @param newClasses 新版 classfiles（目录 / jar）
     * @param oldSources 旧版源码根目录（可以为 null，此时走指令序列 LCS）
     * @param newSources 新版源码根目录（可以为 null，此时 HTML 无法着色）
     * @param charset    源码编码
     */
    public static Result diff(List<File> oldClasses, List<File> newClasses,
                              List<File> oldSources, List<File> newSources,
                              Charset charset) throws IOException {
        Map<String, ClassMeta> oldMeta = scan(oldClasses);
        Map<String, ClassMeta> newMeta = scan(newClasses);

        Map<String, Set<Integer>> fileChanged = new HashMap<>();
        boolean sourceBased = oldSources != null && newSources != null
                && !oldSources.isEmpty() && !newSources.isEmpty();
        if (sourceBased) {
            Set<String> keys = new LinkedHashSet<>();
            for (ClassMeta cm : newMeta.values()) {
                if (cm.sourceKey != null) {
                    keys.add(cm.sourceKey);
                }
            }
            for (String key : keys) {
                List<String> oldLines = readSource(oldSources, key, charset);
                List<String> newLines = readSource(newSources, key, charset);
                if (oldLines == null || newLines == null) {
                    continue;
                }
                fileChanged.put(key, changedLines(oldLines, newLines));
            }
        }

        Result result = new Result();
        int usedSourceDiff = 0;
        int usedInsnDiff = 0;

        for (Map.Entry<String, ClassMeta> e : newMeta.entrySet()) {
            String name = e.getKey();
            ClassMeta nm = e.getValue();
            ClassMeta om = oldMeta.get(name);
            ClassChange change = new ClassChange();
            change.className = name;
            change.sourceKey = nm.sourceKey;

            if (om == null) {
                change.status = Status.ADDED;
                nm.computeOwnership();
                for (MethodMeta mm : nm.methods.values()) {
                    addRange(change.changedLines, startOf(mm), mm.lastLine);
                }
                change.lineLevel = true;
                result.addedClasses++;
            } else {
                compareMethods(om, nm, change);
                Set<Integer> fileLines = nm.sourceKey == null ? null : fileChanged.get(nm.sourceKey);
                nm.computeOwnership();
                if (fileLines != null) {
                    // 路径 1：源码 LCS（最准）
                    for (Integer ln : fileLines) {
                        if (nm.coversLine(ln)) {
                            change.changedLines.add(ln);
                        }
                    }
                    change.lineLevel = true;
                    usedSourceDiff++;
                } else {
                    // 路径 2：没有两版源码 → 用两版字节码的指令序列 LCS 精确到行
                    boolean any = false;
                    for (MethodChange mc : change.methods) {
                        MethodMeta target = nm.methods.get(mc.name + mc.desc);
                        if (target == null) {
                            continue;
                        }
                        MethodMeta previous = findPrevious(om, mc, target);
                        Set<Integer> lines = new TreeSet<>();
                        if (previous == null || previous.lineSig.isEmpty() || target.lineSig.isEmpty()) {
                            addRange(lines, startOf(target), target.lastLine);
                        } else {
                            insnChangedLines(previous, target, lines);
                            if ("signature-changed".equals(mc.kind)) {
                                lines.add(startOf(target));
                            }
                        }
                        change.changedLines.addAll(lines);
                        any = true;
                    }
                    if (any) {
                        change.lineLevel = true;
                        usedInsnDiff++;
                    }
                }
                if (change.status == Status.UNCHANGED && !change.methods.isEmpty()) {
                    change.status = Status.MODIFIED;
                }
                if (change.status == Status.MODIFIED) {
                    result.modifiedClasses++;
                }
            }
            result.byClass.put(name, change);
            result.changedLineCount += change.changedLines.size();
        }

        result.lineLevelAvailable = usedSourceDiff > 0 || usedInsnDiff > 0;
        result.levelNote = noteFor(oldSources, newSources, usedSourceDiff, usedInsnDiff);

        for (String name : oldMeta.keySet()) {
            if (newMeta.containsKey(name)) {
                continue;
            }
            ClassChange change = new ClassChange();
            change.className = name;
            change.status = Status.REMOVED;
            change.sourceKey = oldMeta.get(name).sourceKey;
            result.byClass.put(name, change);
            result.removedClasses++;
        }
        return result;
    }

    private static String noteFor(List<File> oldSources, List<File> newSources,
                                  int usedSourceDiff, int usedInsnDiff) {
        if (usedSourceDiff > 0 && usedInsnDiff == 0) {
            return "行级：两版源码 LCS 对齐";
        }
        String insn = "行级：两版字节码指令序列 LCS 对齐（只需两版 class 就能精确到改动的那几行）";
        if (usedSourceDiff > 0) {
            return "混合：部分类用源码 LCS 对齐，其余类没定位到源码、回退到字节码指令序列 LCS";
        }
        boolean hasNew = newSources != null && !newSources.isEmpty();
        boolean hasOld = oldSources != null && !oldSources.isEmpty();
        if (hasNew && !hasOld) {
            return insn + "；新版源码仍用于 HTML 代码着色";
        }
        if (!hasNew) {
            return insn + "；没有新版源码，报告里没有源码页（无法着色）";
        }
        return insn;
    }

    /** 在旧版里找该方法改动前的版本：先看同名同 desc，再看同名不同 desc（签名变了） */
    private static MethodMeta findPrevious(ClassMeta oldMeta, MethodChange mc, MethodMeta target) {
        MethodMeta same = oldMeta.methods.get(mc.name + mc.desc);
        if (same != null) {
            return same;
        }
        for (MethodMeta pm : oldMeta.methods.values()) {
            if (pm.name.equals(mc.name)) {
                return pm;
            }
        }
        return null;
    }

    private static int startOf(MethodMeta mm) {
        return mm.ownStart > 0 ? mm.ownStart : Math.max(1, mm.firstLine);
    }

    /**
     * 指令序列 LCS：新版里没能和旧版匹配上的指令，其所在行号即为变更行。
     * 只认有字节码的行（改注释这种没有字节码的改动检测不到，但那种改动本来也不影响覆盖率）。
     */
    static void insnChangedLines(MethodMeta oldMeta, MethodMeta newMeta, Set<Integer> out) {
        String[] a = oldMeta.lineSig.toArray(new String[0]);
        String[] b = newMeta.lineSig.toArray(new String[0]);
        boolean[] matched = lcsMatch(a, b, true);
        if (System.getProperty("xxInsnDebug") != null) {
            System.out.println("--- insn diff " + oldMeta.name + " -> " + newMeta.name
                    + " (old " + a.length + " 行 / new " + b.length + " 行) ---");
            for (int i = 0; i < b.length; i++) {
                System.out.println((matched[i] ? "  " : "* ") + newMeta.lineNo.get(i) + "  " + b[i]);
            }
        }
        for (int i = 0; i < b.length; i++) {
            if (matched[i]) {
                continue;
            }
            int ln = newMeta.lineNo.get(i).intValue();
            if (ln > 0) {
                out.add(ln);
            }
        }
    }

    /**
     * 把一条指令规范化成可比较的字符串：带上 opcode 与"语义相关"的操作数；
     * 刻意<b>忽略局部变量槽号</b>（新增参数会让后面所有槽号平移，带上它会整段判为变更），
     * 也忽略 label / 行号 / frame（同样会因为代码位移而抖动）。
     */
    static String insnSignature(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (insn instanceof InsnNode) {
            return "o" + op;
        }
        if (insn instanceof IntInsnNode) {
            return "i" + op + "#" + ((IntInsnNode) insn).operand;
        }
        if (insn instanceof VarInsnNode) {
            return "v" + canonicalVarOpcode(op);
        }
        if (insn instanceof TypeInsnNode) {
            return "t" + op + "#" + ((TypeInsnNode) insn).desc;
        }
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) insn;
            return "f" + op + "#" + f.owner + "." + f.name + ":" + f.desc;
        }
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) insn;
            return "m" + op + "#" + m.owner + "." + m.name + m.desc;
        }
        if (insn instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode d = (InvokeDynamicInsnNode) insn;
            return "d#" + d.name + d.desc;
        }
        if (insn instanceof JumpInsnNode) {
            return "j" + op;
        }
        if (insn instanceof LdcInsnNode) {
            return "l#" + String.valueOf(((LdcInsnNode) insn).cst);
        }
        if (insn instanceof IincInsnNode) {
            return "n#" + ((IincInsnNode) insn).incr;
        }
        if (insn instanceof TableSwitchInsnNode) {
            TableSwitchInsnNode s = (TableSwitchInsnNode) insn;
            return "s" + op + "#" + s.min + "-" + s.max;
        }
        if (insn instanceof LookupSwitchInsnNode) {
            LookupSwitchInsnNode s = (LookupSwitchInsnNode) insn;
            return "s" + op + "#" + s.keys;
        }
        if (insn instanceof MultiANewArrayInsnNode) {
            MultiANewArrayInsnNode n = (MultiANewArrayInsnNode) insn;
            return "a#" + n.desc + "#" + n.dims;
        }
        return "u" + op;
    }

    /**
     * 局部变量指令的「规范 opcode」：aload_0/1/2/3 与 aload 是同一个语义，
     * 只是 opcode 不同（45 vs 25）。新增一个参数就会让后面的变量从 aload_3 变成 aload 4，
     * 不归一化的话整段都会被误判成变更。
     */
    private static int canonicalVarOpcode(int op) {
        if (op >= 26 && op <= 45) {
            // xLOAD_0..3 → xLOAD：ILOAD(21) LLOAD(22) FLOAD(23) DLOAD(24) ALOAD(25)
            return 21 + (op - 26) / 4;
        }
        if (op >= 59 && op <= 78) {
            // xSTORE_0..3 → xSTORE：ISTORE(54) LSTORE(55) FSTORE(56) DSTORE(57) ASTORE(58)
            return 54 + (op - 59) / 4;
        }
        return op;
    }

    /** label / 行号 / frame 不参与比对 */
    private static boolean ignorable(AbstractInsnNode insn) {
        return insn instanceof LabelNode || insn instanceof LineNumberNode || insn.getOpcode() < 0;
    }

    private static void compareMethods(ClassMeta om, ClassMeta nm, ClassChange change) {
        Map<String, MethodMeta> oldByName = new HashMap<>();
        for (MethodMeta mm : om.methods.values()) {
            oldByName.put(mm.name, mm);
        }
        for (MethodMeta mm : nm.methods.values()) {
            MethodMeta previous = om.methods.get(mm.name + mm.desc);
            if (previous == null) {
                MethodMeta alt = oldByName.get(mm.name);
                if (alt != null && !alt.desc.equals(mm.desc)) {
                    change.methods.add(new MethodChange(mm.name, mm.desc, "signature-changed"));
                } else {
                    change.methods.add(new MethodChange(mm.name, mm.desc, "added"));
                }
                continue;
            }
            if (!previous.hash.equals(mm.hash)) {
                change.methods.add(new MethodChange(mm.name, mm.desc, "modified"));
            }
        }
        for (MethodMeta mm : om.methods.values()) {
            if (nm.methods.containsKey(mm.name + mm.desc)) {
                continue;
            }
            boolean still = false;
            for (MethodMeta candidate : nm.methods.values()) {
                if (candidate.name.equals(mm.name)) {
                    still = true;
                    break;
                }
            }
            if (!still) {
                change.methods.add(new MethodChange(mm.name, mm.desc, "removed"));
            }
        }
    }

    private static void addRange(Set<Integer> set, int from, int to) {
        if (from <= 0 || to <= 0) {
            return;
        }
        for (int i = from; i <= to; i++) {
            set.add(i);
        }
    }

    private static Map<String, ClassMeta> scan(List<File> paths) throws IOException {
        Map<String, ClassMeta> out = new LinkedHashMap<>();
        for (File file : paths) {
            if (!file.exists()) {
                continue;
            }
            if (file.isDirectory()) {
                List<Path> found = new ArrayList<>();
                Files.walk(file.toPath(), new FileVisitOption[0])
                        .filter(p -> p.toString().endsWith(".class"))
                        .forEach(found::add);
                for (Path p : found) {
                    read(Files.readAllBytes(p), out);
                }
                continue;
            }
            if (!file.getName().endsWith(".jar") && !file.getName().endsWith(".zip")) {
                continue;
            }
            try (JarFile jar = new JarFile(file)) {
                Enumeration<JarEntry> en = jar.entries();
                while (en.hasMoreElements()) {
                    JarEntry entry = en.nextElement();
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }
                    try (InputStream in = jar.getInputStream(entry)) {
                        read(readAll(in), out);
                    }
                }
            }
        }
        return out;
    }

    private static void read(byte[] bytes, Map<String, ClassMeta> map) {
        try {
            ClassReader reader = new ClassReader(bytes);
            final String className = reader.getClassName();
            final ClassMeta meta = new ClassMeta();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {

                @Override
                public void visitSource(String source, String debug) {
                    if (source != null) {
                        int slash = className.lastIndexOf('/');
                        String pkg = slash < 0 ? "" : className.substring(0, slash);
                        meta.sourceKey = pkg.isEmpty() ? source : pkg + "/" + source;
                    }
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    return new MethodNode(Opcodes.ASM9, access, name, desc, signature, exceptions) {

                        @Override
                        public void visitEnd() {
                            super.visitEnd();
                            MethodMeta mm = new MethodMeta(this.name, this.desc,
                                    MethodHasher.hashMethod(this));
                            int currentLine = -1;
                            StringBuilder bundle = new StringBuilder();
                            for (AbstractInsnNode insn : this.instructions) {
                                if (insn instanceof LineNumberNode) {
                                    // 先按「上一条行号」落盘，再切换：指令归属于它前面最近的那条 LineNumberNode
                                    mm.flushLine(bundle, currentLine);
                                    currentLine = ((LineNumberNode) insn).line;
                                    mm.firstLine = Math.min(mm.firstLine, currentLine);
                                    mm.lastLine = Math.max(mm.lastLine, currentLine);
                                    continue;
                                }
                                if (ignorable(insn) || currentLine <= 0) {
                                    continue;
                                }
                                bundle.append(insnSignature(insn)).append('|');
                            }
                            mm.flushLine(bundle, currentLine);
                            meta.methods.put(this.name + this.desc, mm);
                        }
                    };
                }
            }, 0);
            map.put(className, meta);
        } catch (RuntimeException ignored) {
            // 非标准 / 损坏的 class，跳过
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static List<String> readSource(List<File> roots, String key, Charset charset) {
        if (roots == null) {
            return null;
        }
        for (File root : roots) {
            File f = new File(root, key);
            if (!f.isFile()) {
                continue;
            }
            try {
                return Files.readAllLines(f.toPath(), charset);
            } catch (IOException e) {
                try {
                    return Files.readAllLines(f.toPath());
                } catch (IOException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    static Set<Integer> changedLines(List<String> oldLines, List<String> newLines) {
        String[] a = normalize(oldLines);
        String[] b = normalize(newLines);
        boolean[] matched = lcsMatch(a, b);
        Set<Integer> out = new TreeSet<>();
        for (int i = 0; i < b.length; i++) {
            if (!matched[i]) {
                out.add(i + 1);
            }
        }
        return out;
    }

    private static String[] normalize(List<String> lines) {
        String[] out = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            out[i] = lines.get(i).trim();
        }
        return out;
    }

    private static boolean[] lcsMatch(String[] a, String[] b) {
        return lcsMatch(a, b, false);
    }

    /**
     * @param preferSkipNew 在 dp 相等时优先跳过 b（新版）的 token 而不是 a（旧版）的。
     *   字节码里满是 v25/o87/m185 这类重复 token，包含关系是多重的最优对齐；
     *   默认取向会把「未匹配」推到尾部，导致后面没改的行被误判为变更，
     *   所以指令序列比对用 true，把未匹配的块压到真正新插入的那一段。
     */
    private static boolean[] lcsMatch(String[] a, String[] b, boolean preferSkipNew) {
        long cells = (long) (a.length + 1) * (long) (b.length + 1);
        return cells <= MAX_CELLS ? lcsExact(a, b, preferSkipNew) : lcsApprox(a, b);
    }

    private static boolean[] lcsExact(String[] a, String[] b, boolean preferSkipNew) {
        int n = a.length;
        int m = b.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a[i].equals(b[j])
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        boolean[] matched = new boolean[m];
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                matched[j] = true;
                i++;
                j++;
            } else if (preferSkipNew ? dp[i + 1][j] > dp[i][j + 1] : dp[i + 1][j] >= dp[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return matched;
    }

    private static boolean[] lcsApprox(String[] a, String[] b) {
        Map<String, Integer> pool = new HashMap<>();
        for (String s : a) {
            Integer c = pool.get(s);
            pool.put(s, c == null ? 1 : c + 1);
        }
        boolean[] matched = new boolean[b.length];
        for (int i = 0; i < b.length; i++) {
            Integer c = pool.get(b[i]);
            if (c == null || c <= 0) {
                continue;
            }
            matched[i] = true;
            pool.put(b[i], c - 1);
        }
        return matched;
    }

    static List<String> emptyIfNull(List<String> list) {
        return list == null ? Collections.<String>emptyList() : list;
    }

    private static final class ClassMeta {
        String sourceKey;
        final Map<String, MethodMeta> methods = new LinkedHashMap<>();
        private boolean ownershipDone;

        /** 方法归属范围：声明行不在任何方法的 LineNumberTable 里，需要按前后方法推算出来 */
        private void computeOwnership() {
            if (ownershipDone) {
                return;
            }
            ownershipDone = true;
            List<MethodMeta> ordered = new ArrayList<>();
            for (MethodMeta mm : methods.values()) {
                if (mm.lastLine > 0 && mm.firstLine != Integer.MAX_VALUE) {
                    ordered.add(mm);
                }
            }
            ordered.sort((x, y) -> Integer.compare(x.firstLine, y.firstLine));
            int last = -1;
            for (MethodMeta mm : ordered) {
                int start = last < 0 ? mm.firstLine - 1 : Math.max(mm.firstLine - 1, last + 1);
                mm.ownStart = Math.max(1, start);
                last = Math.max(last, mm.lastLine);
            }
        }

        boolean coversLine(int line) {
            computeOwnership();
            for (MethodMeta mm : methods.values()) {
                if (mm.ownStart > 0 && mm.ownStart <= line && line <= mm.lastLine) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final class Result {
        public final Map<String, ClassChange> byClass = new LinkedHashMap<>();
        public int addedClasses;
        public int removedClasses;
        public int modifiedClasses;
        public int changedLineCount;
        public boolean lineLevelAvailable;
        public String levelNote = "方法级：未提供两版源码";

        public Collection<ClassChange> changed() {
            List<ClassChange> out = new ArrayList<>();
            for (ClassChange cc : byClass.values()) {
                if (cc.status == Status.UNCHANGED || cc.status == Status.REMOVED) {
                    continue;
                }
                out.add(cc);
            }
            return out;
        }
    }

    public static final class ClassChange {
        public String className;
        public Status status = Status.UNCHANGED;
        public final List<MethodChange> methods = new ArrayList<>();
        public final Set<Integer> changedLines = new TreeSet<>();
        public String sourceKey;
        public boolean lineLevel;

        public boolean hasChangedLines() {
            return !changedLines.isEmpty();
        }
    }

    public enum Status {
        ADDED,
        REMOVED,
        MODIFIED,
        UNCHANGED
    }

    public static final class MethodChange {
        public final String name;
        public final String desc;
        public final String kind;

        MethodChange(String name, String desc, String kind) {
            this.name = name;
            this.desc = desc;
            this.kind = kind;
        }
    }

    private static final class MethodMeta {
        final String name;
        final String desc;
        final String hash;
        final List<String> lineSig = new ArrayList<>();
        final List<Integer> lineNo = new ArrayList<>();
        int firstLine = Integer.MAX_VALUE;
        int lastLine = -1;
        int ownStart = -1;

        MethodMeta(String name, String desc, String hash) {
            this.name = name;
            this.desc = desc;
            this.hash = hash;
        }

        /** 把累计到当前行为止的指令签名打包成一行；空的不参与比对 */
        void flushLine(StringBuilder bundle, int line) {
            if (line <= 0 || bundle.length() == 0) {
                bundle.setLength(0);
                return;
            }
            lineSig.add(bundle.toString());
            lineNo.add(line);
            bundle.setLength(0);
        }
    }
}
