package peruser.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IPackageCoverage;
import peruser.cli.ClassDiff;
import peruser.cli.MiniJson;

public final class IncrementalSummary {
    private static final int CONTEXT = 3;

    private IncrementalSummary() {
    }

    static void write(ClassDiff.Result result, IBundleCoverage bundle, List<File> sourceRoots,
                      File jsonFile, File htmlFile, Charset charset, boolean quiet) throws IOException {
        Map<String, IClassCoverage> byName = new HashMap<String, IClassCoverage>();
        for (IPackageCoverage pkg : bundle.getPackages()) {
            for (IClassCoverage cls : pkg.getClasses()) {
                byName.put(cls.getName(), cls);
            }
        }
        ICounter line = bundle.getLineCounter();
        ICounter branch = bundle.getBranchCounter();
        ICounter method = bundle.getMethodCounter();
        ICounter instruction = bundle.getInstructionCounter();
        if (jsonFile != null) {
            IncrementalSummary.writeJson(result, jsonFile, line, branch, method, instruction);
        }
        if (htmlFile != null) {
            IncrementalSummary.writeHtml(result, byName, sourceRoots, htmlFile, charset, line, branch, method, instruction);
        }
        if (!quiet) {
            System.out.println("[xiaoxiao-jacoco-cli] 增量覆盖率（只统计变更部分）:");
            System.out.println("    行   " + line.getCoveredCount() + "/" + line.getTotalCount() + "  (" + IncrementalSummary.pct(line) + "%)");
            System.out.println("    分支 " + branch.getCoveredCount() + "/" + branch.getTotalCount() + "  (" + IncrementalSummary.pct(branch) + "%)");
            System.out.println("    方法 " + method.getCoveredCount() + "/" + method.getTotalCount() + "  (" + IncrementalSummary.pct(method) + "%)");
            System.out.println("    指令 " + instruction.getCoveredCount() + "/" + instruction.getTotalCount() + "  (" + IncrementalSummary.pct(instruction) + "%)");
            if (jsonFile != null) {
                System.out.println("[xiaoxiao-jacoco-cli] 差异明细: " + jsonFile.getAbsolutePath());
            }
            if (htmlFile != null) {
                System.out.println("[xiaoxiao-jacoco-cli] 折叠视图: " + htmlFile.getAbsolutePath());
            }
        }
    }

    private static String pct(ICounter iCounter) {
        int n = iCounter.getTotalCount();
        return n == 0 ? "-" : String.format("%.1f", iCounter.getCoveredRatio() * 100.0);
    }

    private static void writeJson(ClassDiff.Result result, File file, ICounter iCounter, ICounter iCounter2, ICounter iCounter3, ICounter iCounter4) throws IOException {
        File file2 = file.getParentFile();
        if (file2 != null) {
            file2.mkdirs();
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("{\n  \"summary\": {\n");
        stringBuilder.append("    \"changedLines\": ").append(result.changedLineCount).append(",\n");
        stringBuilder.append("    \"addedClasses\": ").append(result.addedClasses).append(",\n");
        stringBuilder.append("    \"modifiedClasses\": ").append(result.modifiedClasses).append(",\n");
        stringBuilder.append("    \"removedClasses\": ").append(result.removedClasses).append(",\n");
        stringBuilder.append("    \"lineLevel\": ").append(result.lineLevelAvailable).append(",\n");
        stringBuilder.append("    \"levelNote\": ").append(IncrementalSummary.jstr(result.levelNote)).append(",\n");
        stringBuilder.append("    \"line\": {\"covered\": ").append(iCounter.getCoveredCount()).append(", \"total\": ").append(iCounter.getTotalCount()).append("},\n");
        stringBuilder.append("    \"branch\": {\"covered\": ").append(iCounter2.getCoveredCount()).append(", \"total\": ").append(iCounter2.getTotalCount()).append("},\n");
        stringBuilder.append("    \"method\": {\"covered\": ").append(iCounter3.getCoveredCount()).append(", \"total\": ").append(iCounter3.getTotalCount()).append("},\n");
        stringBuilder.append("    \"instruction\": {\"covered\": ").append(iCounter4.getCoveredCount()).append(", \"total\": ").append(iCounter4.getTotalCount()).append("}\n");
        stringBuilder.append("  },\n  \"classes\": [\n");
        boolean bl = true;
        for (ClassDiff.ClassChange classChange : result.byClass.values()) {
            if (classChange.status == ClassDiff.Status.UNCHANGED) continue;
            if (!bl) {
                stringBuilder.append(",\n");
            }
            bl = false;
            stringBuilder.append("    {\"class\": ").append(MiniJson.str(classChange.className)).append(", \"status\": ").append(MiniJson.str(classChange.status.name())).append(", \"source\": ").append(MiniJson.str(classChange.sourceKey)).append(", \"changedLines\": [");
            boolean bl2 = true;
            for (Integer n : classChange.changedLines) {
                if (!bl2) {
                    stringBuilder.append(", ");
                }
                bl2 = false;
                stringBuilder.append(n);
            }
            stringBuilder.append("], \"methods\": [");
            boolean bl3 = true;
            for (ClassDiff.MethodChange methodChange : classChange.methods) {
                if (!bl3) {
                    stringBuilder.append(", ");
                }
                bl3 = false;
                stringBuilder.append("{\"name\": ").append(MiniJson.str(methodChange.name)).append(", \"desc\": ").append(MiniJson.str(methodChange.desc)).append(", \"kind\": ").append(MiniJson.str(methodChange.kind)).append("}");
            }
            stringBuilder.append("]}");
        }
        stringBuilder.append("\n  ]\n}\n");
        Files.write(file.toPath(), stringBuilder.toString().getBytes("UTF-8"), new OpenOption[0]);
    }

    private static void writeHtml(ClassDiff.Result result, Map<String, IClassCoverage> map, List<File> list, File file, Charset charset, ICounter iCounter, ICounter iCounter2, ICounter iCounter3, ICounter iCounter4) throws IOException {
        File file2 = file.getParentFile();
        if (file2 != null) {
            file2.mkdirs();
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\">");
        stringBuilder.append("<title>增量覆盖率（只显示变更位置）</title><style>");
        stringBuilder.append("body{font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;margin:24px;color:#222;}");
        stringBuilder.append("h1{font-size:20px;font-weight:600;}h2{font-size:15px;margin-top:24px;}");
        stringBuilder.append(".cards{display:flex;gap:12px;flex-wrap:wrap;margin:12px 0 20px;}");
        stringBuilder.append(".card{border:1px solid #e3e3e3;border-radius:8px;padding:10px 14px;min-width:120px;}");
        stringBuilder.append(".card .k{font-size:12px;color:#888;}.card .v{font-size:20px;font-weight:600;}");
        stringBuilder.append("table{border-collapse:collapse;font-size:13px;}td,th{border:1px solid #e6e6e6;padding:3px 8px;}");
        stringBuilder.append("pre{margin:0;font-family:Menlo,Consolas,monospace;font-size:12.5px;}");
        stringBuilder.append(".box{border:1px solid #e3e3e3;border-radius:8px;margin:14px 0;overflow:hidden;}");
        stringBuilder.append(".box .hd{background:#fafafa;padding:8px 12px;font-size:13px;border-bottom:1px solid #e3e3e3;}");
        stringBuilder.append(".ln{display:flex;}.no{width:52px;text-align:right;padding-right:8px;color:#999;user-select:none;}");
        stringBuilder.append(".src{white-space:pre-wrap;padding-left:6px;}");
        stringBuilder.append(".cov{background:#e8f7ec;}.part{background:#fdf6d8;}.mis{background:#fdeaea;}.ctx{background:#fbfbfb;color:#999;}");
        stringBuilder.append("</style></head><body>");
        stringBuilder.append("<h1>增量覆盖率 —— 只显示变更位置</h1>");
        stringBuilder.append("<div class=\"cards\">");
        IncrementalSummary.card(stringBuilder, "变更行", String.valueOf(result.changedLineCount), "");
        IncrementalSummary.card(stringBuilder, "行覆盖", iCounter.getCoveredCount() + "/" + iCounter.getTotalCount(), IncrementalSummary.pct(iCounter) + "%");
        IncrementalSummary.card(stringBuilder, "分支覆盖", iCounter2.getCoveredCount() + "/" + iCounter2.getTotalCount(), IncrementalSummary.pct(iCounter2) + "%");
        IncrementalSummary.card(stringBuilder, "方法覆盖", iCounter3.getCoveredCount() + "/" + iCounter3.getTotalCount(), IncrementalSummary.pct(iCounter3) + "%");
        IncrementalSummary.card(stringBuilder, "指令覆盖", iCounter4.getCoveredCount() + "/" + iCounter4.getTotalCount(), IncrementalSummary.pct(iCounter4) + "%");
        IncrementalSummary.card(stringBuilder, "新增类", String.valueOf(result.addedClasses), "");
        IncrementalSummary.card(stringBuilder, "修改类", String.valueOf(result.modifiedClasses), "");
        IncrementalSummary.card(stringBuilder, "删除类", String.valueOf(result.removedClasses), "");
        stringBuilder.append("</div>");
        if (!result.lineLevelAvailable) {
            stringBuilder.append("<p style=\"color:#8a6d00\">提示：").append(IncrementalSummary.esc(result.levelNote)).append("（当前降级为方法级比对：改动方法的整段行区间都算变更行，分母会偏大、覆盖率被稀释）。补齐两版源码可精确到改动的那几行。</p>");
        }
        stringBuilder.append("<h2>变更清单</h2><table><tr><th>类</th><th>状态</th><th>变更行</th><th>方法变化</th></tr>");
        for (ClassDiff.ClassChange classChange : result.byClass.values()) {
            if (classChange.status == ClassDiff.Status.UNCHANGED) continue;
            stringBuilder.append("<tr><td>").append(IncrementalSummary.esc(classChange.className)).append("</td><td>").append(classChange.status.name()).append("</td><td>");
            if (classChange.changedLines.isEmpty()) {
                stringBuilder.append("（方法级，未定位到行）");
            } else {
                stringBuilder.append(classChange.changedLines.size()).append(" 行");
            }
            stringBuilder.append("</td><td>");
            for (ClassDiff.MethodChange mc : classChange.methods) {
                stringBuilder.append(IncrementalSummary.esc(mc.name)).append(" <span style=\"color:#888\">").append(IncrementalSummary.esc(mc.kind)).append("</span>; ");
            }
            stringBuilder.append("</td></tr>");
        }
        stringBuilder.append("</table>");
        stringBuilder.append("<h2>变更片段（上下文 ").append(3).append(" 行，灰底=未变更上下文）</h2>");
        for (ClassDiff.ClassChange classChange : result.byClass.values()) {
            List<String> srcLines = classChange.sourceKey == null
                    ? null : IncrementalSummary.readSource(list, classChange.sourceKey, charset);
            if (classChange.status == ClassDiff.Status.UNCHANGED || srcLines == null) continue;
            IClassCoverage cov = map.get(classChange.className);
            TreeSet<Integer> treeSet = new TreeSet<Integer>();
            for (Integer n : classChange.changedLines) {
                for (int i = n - 3; i <= n + 3; ++i) {
                    if (i < 1 || i > srcLines.size()) continue;
                    treeSet.add(i);
                }
            }
            if (treeSet.isEmpty()) continue;
            stringBuilder.append("<div class=\"box\"><div class=\"hd\">").append(IncrementalSummary.esc(classChange.className)).append(" &nbsp;·&nbsp; ").append(classChange.status.name()).append(" &nbsp;·&nbsp; 变更 ").append(classChange.changedLines.size()).append(" 行</div>");
            int n = -1;
            for (Integer n2 : treeSet) {
                if (n >= 0 && n2 != n + 1) {
                    stringBuilder.append("<div class=\"ln\"><span class=\"no\">…</span><span class=\"src\"></span></div>");
                }
                n = n2;
                boolean bl = classChange.changedLines.contains(n2);
                String string = "ctx";
                if (bl && cov != null) {
                    ILine iLine = cov.getLine(n2.intValue());
                    int status = iLine == null ? 0 : iLine.getStatus();
                    if (status == 2) {
                        string = "cov";
                    } else if (status == 3) {
                        string = "part";
                    } else if (status == 1) {
                        string = "mis";
                    }
                }
                stringBuilder.append("<div class=\"ln ").append(string).append("\"><span class=\"no\">").append(n2).append("</span><span class=\"src\">").append(IncrementalSummary.esc(srcLines.get(n2 - 1))).append("</span></div>");
            }
            stringBuilder.append("</div>");
        }
        stringBuilder.append("</body></html>");
        Files.write(file.toPath(), stringBuilder.toString().getBytes("UTF-8"), new OpenOption[0]);
    }

    private static void card(StringBuilder stringBuilder, String string, String string2, String string3) {
        stringBuilder.append("<div class=\"card\"><div class=\"k\">").append(string).append("</div><div class=\"v\">").append(string2).append("</div>");
        if (string3 != null && !string3.isEmpty()) {
            stringBuilder.append("<div class=\"k\">").append(string3).append("</div>");
        }
        stringBuilder.append("</div>");
    }

    private static List<String> readSource(List<File> list, String string, Charset charset) {
        for (File file : list) {
            File file2 = new File(file, string);
            if (!file2.isFile()) continue;
            try {
                return Files.readAllLines(file2.toPath(), charset);
            }
            catch (IOException iOException) {
                return null;
            }
        }
        return null;
    }

    private static String esc(String string) {
        if (string == null) {
            return "";
        }
        return string.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String jstr(String string) {
        if (string == null) {
            return "\"\"";
        }
        StringBuilder stringBuilder = new StringBuilder("\"");
        for (int i = 0; i < string.length(); ++i) {
            char c = string.charAt(i);
            if (c == '\"' || c == '\\') {
                stringBuilder.append('\\').append(c);
                continue;
            }
            if (c == '\n') {
                stringBuilder.append("\\n");
                continue;
            }
            if (c == '\r') {
                stringBuilder.append("\\r");
                continue;
            }
            if (c == '\t') {
                stringBuilder.append("\\t");
                continue;
            }
            if (c < ' ') {
                stringBuilder.append(String.format("\\u%04x", c));
                continue;
            }
            stringBuilder.append(c);
        }
        return stringBuilder.append('\"').toString();
    }

    static Map<String, List<String>> groupBySource(ClassDiff.Result result) {
        LinkedHashMap<String, List<String>> linkedHashMap = new LinkedHashMap<String, List<String>>();
        for (ClassDiff.ClassChange classChange : result.byClass.values()) {
            if (classChange.sourceKey == null) continue;
            ArrayList<String> arrayList = (ArrayList<String>)linkedHashMap.get(classChange.sourceKey);
            if (arrayList == null) {
                arrayList = new ArrayList<String>();
                linkedHashMap.put(classChange.sourceKey, arrayList);
            }
            arrayList.add(classChange.className);
        }
        return linkedHashMap;
    }
}

