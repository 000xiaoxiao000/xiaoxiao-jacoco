package peruser.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ICoverageNode;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.analysis.IPackageCoverage;
import org.jacoco.core.analysis.ISourceFileCoverage;
import org.jacoco.core.analysis.ISourceNode;
import peruser.cli.ClassDiff;

public final class IncrementalCoverage {
    static final ILine EMPTY_LINE = new ILine(){

        public ICounter getInstructionCounter() {
            return new SimpleCounter(0, 0);
        }

        public ICounter getBranchCounter() {
            return new SimpleCounter(0, 0);
        }

        public int getStatus() {
            return 0;
        }
    };

    private IncrementalCoverage() {
    }

    public static IBundleCoverage filter(IBundleCoverage iBundleCoverage, ClassDiff.Result result) {
        return new FilteredBundle(iBundleCoverage, result);
    }

    static ICounter sum(List<ICounter> list) {
        int n = 0;
        int n2 = 0;
        for (ICounter iCounter : list) {
            n += iCounter.getCoveredCount();
            n2 += iCounter.getMissedCount();
        }
        return new SimpleCounter(n, n2);
    }

    static ICounter countLines(ISourceNode iSourceNode, Set<Integer> set) {
        int n = 0;
        int n2 = 0;
        int n3 = iSourceNode.getFirstLine();
        int n4 = iSourceNode.getLastLine();
        if (n3 == -1 || n4 == -1) {
            return new SimpleCounter(0, 0);
        }
        for (int i = n3; i <= n4; ++i) {
            int n5;
            ILine iLine;
            if (set != null && !set.contains(i) || (iLine = iSourceNode.getLine(i)) == null || (n5 = iLine.getStatus()) == 0) continue;
            if (n5 == 2 || n5 == 3) {
                ++n;
                continue;
            }
            ++n2;
        }
        return new SimpleCounter(n, n2);
    }

    static ICounter countCounter(ISourceNode iSourceNode, Set<Integer> set, boolean bl) {
        int n = 0;
        int n2 = 0;
        int n3 = iSourceNode.getFirstLine();
        int n4 = iSourceNode.getLastLine();
        if (n3 == -1 || n4 == -1) {
            return new SimpleCounter(0, 0);
        }
        for (int i = n3; i <= n4; ++i) {
            ICounter iCounter;
            ILine iLine;
            if (set != null && !set.contains(i) || (iLine = iSourceNode.getLine(i)) == null) continue;
            ICounter iCounter2 = iCounter = bl ? iLine.getBranchCounter() : iLine.getInstructionCounter();
            if (iCounter == null) continue;
            n += iCounter.getCoveredCount();
            n2 += iCounter.getMissedCount();
        }
        return new SimpleCounter(n, n2);
    }

    private static ICounter countComplexity(Collection<IMethodCoverage> collection) {
        int n = 0;
        int n2 = 0;
        for (IMethodCoverage iMethodCoverage : collection) {
            ICounter iCounter = iMethodCoverage.getComplexityCounter();
            if (iCounter == null) continue;
            int n3 = iCounter.getTotalCount();
            if (iMethodCoverage.getInstructionCounter().getCoveredCount() == 0) {
                n2 += n3;
                continue;
            }
            n += n3;
        }
        return new SimpleCounter(n, n2);
    }

    static Set<Integer> linesOf(ClassDiff.ClassChange classChange) {
        if (classChange == null) {
            return null;
        }
        if (classChange.status == ClassDiff.Status.ADDED) {
            return null;
        }
        if (classChange.status == ClassDiff.Status.UNCHANGED) {
            return classChange.changedLines;
        }
        if (classChange.changedLines.isEmpty()) {
            return null;
        }
        return classChange.changedLines;
    }

    static String statusText(int n) {
        switch (n) {
            case 2: {
                return "covered";
            }
            case 3: {
                return "partly";
            }
            case 1: {
                return "missed";
            }
        }
        return "n/a";
    }

    static Map<String, Set<Integer>> emptyMap() {
        return Collections.emptyMap();
    }

    static List<String> keysOf(Map<String, Set<Integer>> map) {
        return new ArrayList<String>(map.keySet());
    }

    private static final class FilteredBundle
    extends Base
    implements IBundleCoverage {
        private final Collection<IPackageCoverage> packages;

        FilteredBundle(IBundleCoverage iBundleCoverage, ClassDiff.Result result) {
            super((ICoverageNode)iBundleCoverage);
            ArrayList<IPackageCoverage> arrayList = new ArrayList<IPackageCoverage>();
            for (IPackageCoverage iPackageCoverage : iBundleCoverage.getPackages()) {
                arrayList.add(new FilteredPackage(iPackageCoverage, result));
            }
            this.packages = arrayList;
        }

        public Collection<IPackageCoverage> getPackages() {
            return this.packages;
        }

        public ICounter getInstructionCounter() {
            ArrayList<ICounter> arrayList = new ArrayList<ICounter>();
            for (IPackageCoverage iPackageCoverage : this.packages) {
                arrayList.add(iPackageCoverage.getInstructionCounter());
            }
            return IncrementalCoverage.sum(arrayList);
        }

        public ICounter getBranchCounter() {
            ArrayList<ICounter> arrayList = new ArrayList<ICounter>();
            for (IPackageCoverage iPackageCoverage : this.packages) {
                arrayList.add(iPackageCoverage.getBranchCounter());
            }
            return IncrementalCoverage.sum(arrayList);
        }

        public ICounter getLineCounter() {
            ArrayList<ICounter> arrayList = new ArrayList<ICounter>();
            for (IPackageCoverage iPackageCoverage : this.packages) {
                arrayList.add(iPackageCoverage.getLineCounter());
            }
            return IncrementalCoverage.sum(arrayList);
        }

        public ICounter getComplexityCounter() {
            ArrayList<ICounter> arrayList = new ArrayList<ICounter>();
            for (IPackageCoverage iPackageCoverage : this.packages) {
                arrayList.add(iPackageCoverage.getComplexityCounter());
            }
            return IncrementalCoverage.sum(arrayList);
        }

        public ICounter getMethodCounter() {
            ArrayList<ICounter> arrayList = new ArrayList<ICounter>();
            for (IPackageCoverage iPackageCoverage : this.packages) {
                arrayList.add(iPackageCoverage.getMethodCounter());
            }
            return IncrementalCoverage.sum(arrayList);
        }

        public ICounter getClassCounter() {
            ArrayList<ICounter> arrayList = new ArrayList<ICounter>();
            for (IPackageCoverage iPackageCoverage : this.packages) {
                arrayList.add(iPackageCoverage.getClassCounter());
            }
            return IncrementalCoverage.sum(arrayList);
        }

        public ICounter getCounter(ICoverageNode.CounterEntity counterEntity) {
            switch (counterEntity) {
                case INSTRUCTION: {
                    return this.getInstructionCounter();
                }
                case BRANCH: {
                    return this.getBranchCounter();
                }
                case LINE: {
                    return this.getLineCounter();
                }
                case COMPLEXITY: {
                    return this.getComplexityCounter();
                }
                case METHOD: {
                    return this.getMethodCounter();
                }
            }
            return this.getClassCounter();
        }
    }

    static final class SimpleCounter
    implements ICounter {
        private final int covered;
        private final int missed;

        SimpleCounter(int n, int n2) {
            this.covered = n;
            this.missed = n2;
        }

        public double getValue(ICounter.CounterValue counterValue) {
            switch (counterValue) {
                case COVEREDCOUNT: {
                    return this.covered;
                }
                case MISSEDCOUNT: {
                    return this.missed;
                }
                case TOTALCOUNT: {
                    return this.covered + this.missed;
                }
                case COVEREDRATIO: {
                    return this.total() == 0 ? 0.0 : (double)this.covered / (double)this.total();
                }
                case MISSEDRATIO: {
                    return this.total() == 0 ? 0.0 : (double)this.missed / (double)this.total();
                }
            }
            return 0.0;
        }

        public int getTotalCount() {
            return this.covered + this.missed;
        }

        public int getCoveredCount() {
            return this.covered;
        }

        public int getMissedCount() {
            return this.missed;
        }

        public double getCoveredRatio() {
            return this.getValue(ICounter.CounterValue.COVEREDRATIO);
        }

        public double getMissedRatio() {
            return this.getValue(ICounter.CounterValue.MISSEDRATIO);
        }

        public int getStatus() {
            int n = this.total();
            if (n == 0) {
                return 0;
            }
            if (this.covered == 0) {
                return 1;
            }
            return this.missed == 0 ? 2 : 3;
        }

        private int total() {
            return this.covered + this.missed;
        }
    }

    private static final class FilteredSourceFile
    extends Base
    implements ISourceFileCoverage {
        private final ISourceFileCoverage self;
        private final Set<Integer> changed;

        FilteredSourceFile(ISourceFileCoverage iSourceFileCoverage, ClassDiff.Result result) {
            super((ICoverageNode)iSourceFileCoverage);
            this.self = iSourceFileCoverage;
            this.changed = FilteredSourceFile.mergedChanged(iSourceFileCoverage, result);
        }

        private static Set<Integer> mergedChanged(ISourceFileCoverage iSourceFileCoverage, ClassDiff.Result result) {
            String string = iSourceFileCoverage.getPackageName() == null || iSourceFileCoverage.getPackageName().isEmpty() ? iSourceFileCoverage.getName() : iSourceFileCoverage.getPackageName() + "/" + iSourceFileCoverage.getName();
            boolean bl = false;
            boolean bl2 = false;
            TreeSet<Integer> treeSet = new TreeSet<Integer>();
            for (ClassDiff.ClassChange classChange : result.byClass.values()) {
                if (!string.equals(classChange.sourceKey)) continue;
                bl = true;
                Set<Integer> set = IncrementalCoverage.linesOf(classChange);
                if (set == null) {
                    bl2 = true;
                    continue;
                }
                treeSet.addAll(set);
            }
            if (!bl) {
                return null;
            }
            return bl2 ? null : treeSet;
        }

        public String getPackageName() {
            return this.self.getPackageName();
        }

        public int getFirstLine() {
            return this.self.getFirstLine();
        }

        public int getLastLine() {
            return this.self.getLastLine();
        }

        public ILine getLine(int n) {
            if (this.changed != null && !this.changed.contains(n)) {
                return EMPTY_LINE;
            }
            return this.self.getLine(n);
        }

        public ICounter getInstructionCounter() {
            return IncrementalCoverage.countCounter((ISourceNode)this.self, this.changed, false);
        }

        public ICounter getBranchCounter() {
            return IncrementalCoverage.countCounter((ISourceNode)this.self, this.changed, true);
        }

        public ICounter getLineCounter() {
            return IncrementalCoverage.countLines((ISourceNode)this.self, this.changed);
        }

        public ICounter getComplexityCounter() {
            return this.self.getComplexityCounter();
        }

        public ICounter getMethodCounter() {
            return this.self.getMethodCounter();
        }

        public ICounter getClassCounter() {
            return new SimpleCounter(0, 0);
        }

        public ICounter getCounter(ICoverageNode.CounterEntity counterEntity) {
            switch (counterEntity) {
                case INSTRUCTION: {
                    return this.getInstructionCounter();
                }
                case BRANCH: {
                    return this.getBranchCounter();
                }
                case LINE: {
                    return this.getLineCounter();
                }
                case COMPLEXITY: {
                    return this.getComplexityCounter();
                }
                case METHOD: {
                    return this.getMethodCounter();
                }
            }
            return this.getClassCounter();
        }

        @Override
        public boolean containsCode() {
            return this.changed == null || !this.changed.isEmpty();
        }
    }

    private static final class FilteredMethod
    extends Base
    implements IMethodCoverage {
        private final IMethodCoverage self;
        private final Set<Integer> changed;
        private final boolean hasChanged;

        FilteredMethod(IMethodCoverage iMethodCoverage, Set<Integer> set) {
            super((ICoverageNode)iMethodCoverage);
            this.self = iMethodCoverage;
            this.changed = set;
            this.hasChanged = FilteredMethod.hasChangedLines(iMethodCoverage, set);
        }

        private static boolean hasChangedLines(IMethodCoverage iMethodCoverage, Set<Integer> set) {
            if (set == null) {
                return true;
            }
            int n = iMethodCoverage.getFirstLine();
            int n2 = iMethodCoverage.getLastLine();
            if (n == -1 || n2 == -1) {
                return false;
            }
            for (int i = n; i <= n2; ++i) {
                if (!set.contains(i)) continue;
                return true;
            }
            return false;
        }

        boolean hasChanged() {
            return this.hasChanged;
        }

        public String getDesc() {
            return this.self.getDesc();
        }

        public String getSignature() {
            return this.self.getSignature();
        }

        public int getFirstLine() {
            return this.self.getFirstLine();
        }

        public int getLastLine() {
            return this.self.getLastLine();
        }

        public ILine getLine(int n) {
            if (this.changed != null && !this.changed.contains(n)) {
                return EMPTY_LINE;
            }
            return this.self.getLine(n);
        }

        public ICounter getInstructionCounter() {
            return IncrementalCoverage.countCounter((ISourceNode)this.self, this.changed, false);
        }

        public ICounter getBranchCounter() {
            return IncrementalCoverage.countCounter((ISourceNode)this.self, this.changed, true);
        }

        public ICounter getLineCounter() {
            return IncrementalCoverage.countLines((ISourceNode)this.self, this.changed);
        }

        public ICounter getComplexityCounter() {
            return this.self.getComplexityCounter();
        }

        public ICounter getMethodCounter() {
            return new SimpleCounter(0, 0);
        }

        public ICounter getClassCounter() {
            return new SimpleCounter(0, 0);
        }

        public ICounter getCounter(ICoverageNode.CounterEntity counterEntity) {
            switch (counterEntity) {
                case INSTRUCTION: {
                    return this.getInstructionCounter();
                }
                case BRANCH: {
                    return this.getBranchCounter();
                }
                case LINE: {
                    return this.getLineCounter();
                }
                case COMPLEXITY: {
                    return this.getComplexityCounter();
                }
                case METHOD: {
                    return this.getMethodCounter();
                }
            }
            return this.getClassCounter();
        }

        @Override
        public boolean containsCode() {
            return this.hasChanged;
        }
    }

    private static final class FilteredClass
    extends Base
    implements IClassCoverage {
        private final IClassCoverage self;
        private final Set<Integer> changed;
        private final Collection<IMethodCoverage> methods;
        private final ICounter lineCounter;
        private final ICounter instructionCounter;
        private final ICounter branchCounter;
        private final ICounter complexityCounter;
        private final ICounter methodCounter;

        FilteredClass(IClassCoverage iClassCoverage, ClassDiff.Result result) {
            super((ICoverageNode)iClassCoverage);
            this.self = iClassCoverage;
            ClassDiff.ClassChange classChange = result.byClass.get(iClassCoverage.getName());
            this.changed = IncrementalCoverage.linesOf(classChange);
            ArrayList<IMethodCoverage> arrayList = new ArrayList<IMethodCoverage>();
            for (IMethodCoverage iMethodCoverage : iClassCoverage.getMethods()) {
                FilteredMethod filteredMethod = new FilteredMethod(iMethodCoverage, this.changed);
                if (!filteredMethod.hasChanged()) continue;
                arrayList.add(filteredMethod);
            }
            this.methods = arrayList;
            this.lineCounter = IncrementalCoverage.countLines((ISourceNode)iClassCoverage, this.changed);
            this.instructionCounter = IncrementalCoverage.countCounter((ISourceNode)iClassCoverage, this.changed, false);
            this.branchCounter = IncrementalCoverage.countCounter((ISourceNode)iClassCoverage, this.changed, true);
            this.complexityCounter = IncrementalCoverage.countComplexity(arrayList);
            int n = 0;
            int n2 = 0;
            for (IMethodCoverage iMethodCoverage : arrayList) {
                if (iMethodCoverage.getInstructionCounter().getCoveredCount() > 0) {
                    ++n;
                    continue;
                }
                ++n2;
            }
            this.methodCounter = new SimpleCounter(n, n2);
        }

        public long getId() {
            return this.self.getId();
        }

        public boolean isNoMatch() {
            return this.self.isNoMatch();
        }

        public String getSignature() {
            return this.self.getSignature();
        }

        public String getSuperName() {
            return this.self.getSuperName();
        }

        public String[] getInterfaceNames() {
            return this.self.getInterfaceNames();
        }

        public String getPackageName() {
            return this.self.getPackageName();
        }

        public String getSourceFileName() {
            return this.self.getSourceFileName();
        }

        public Collection<IMethodCoverage> getMethods() {
            return this.methods;
        }

        public int getFirstLine() {
            return this.self.getFirstLine();
        }

        public int getLastLine() {
            return this.self.getLastLine();
        }

        public ILine getLine(int n) {
            if (this.changed != null && !this.changed.contains(n)) {
                return EMPTY_LINE;
            }
            return this.self.getLine(n);
        }

        public ICounter getInstructionCounter() {
            return this.instructionCounter;
        }

        public ICounter getBranchCounter() {
            return this.branchCounter;
        }

        public ICounter getLineCounter() {
            return this.lineCounter;
        }

        public ICounter getComplexityCounter() {
            return this.complexityCounter;
        }

        public ICounter getMethodCounter() {
            return this.methodCounter;
        }

        public ICounter getClassCounter() {
            return new SimpleCounter(0, 0);
        }

        public ICounter getCounter(ICoverageNode.CounterEntity counterEntity) {
            switch (counterEntity) {
                case INSTRUCTION: {
                    return this.getInstructionCounter();
                }
                case BRANCH: {
                    return this.getBranchCounter();
                }
                case LINE: {
                    return this.getLineCounter();
                }
                case COMPLEXITY: {
                    return this.getComplexityCounter();
                }
                case METHOD: {
                    return this.getMethodCounter();
                }
            }
            return this.getClassCounter();
        }

        @Override
        public boolean containsCode() {
            return this.changed == null || !this.changed.isEmpty();
        }
    }

    private static final class FilteredPackage
    extends Base
    implements IPackageCoverage {
        private final Collection<IClassCoverage> classes;
        private final Collection<ISourceFileCoverage> sourceFiles;

        FilteredPackage(IPackageCoverage iPackageCoverage, ClassDiff.Result result) {
            super((ICoverageNode)iPackageCoverage);
            ArrayList<IClassCoverage> arrayList = new ArrayList<IClassCoverage>();
            for (Object object : iPackageCoverage.getClasses()) {
                arrayList.add(new FilteredClass((IClassCoverage)object, result));
            }
            this.classes = arrayList;
            ArrayList arrayList2 = new ArrayList();
            for (ISourceFileCoverage iSourceFileCoverage : iPackageCoverage.getSourceFiles()) {
                arrayList2.add(new FilteredSourceFile(iSourceFileCoverage, result));
            }
            this.sourceFiles = arrayList2;
        }

        public Collection<IClassCoverage> getClasses() {
            return this.classes;
        }

        public Collection<ISourceFileCoverage> getSourceFiles() {
            return this.sourceFiles;
        }

        public ICounter getInstructionCounter() {
            return this.sumOf(this.classes);
        }

        public ICounter getBranchCounter() {
            ArrayList<ICounter> arrayList = new ArrayList<ICounter>();
            for (IClassCoverage iClassCoverage : this.classes) {
                arrayList.add(iClassCoverage.getBranchCounter());
            }
            return IncrementalCoverage.sum(arrayList);
        }

        public ICounter getLineCounter() {
            ArrayList<ICounter> arrayList = new ArrayList<ICounter>();
            for (IClassCoverage iClassCoverage : this.classes) {
                arrayList.add(iClassCoverage.getLineCounter());
            }
            return IncrementalCoverage.sum(arrayList);
        }

        public ICounter getComplexityCounter() {
            ArrayList<ICounter> arrayList = new ArrayList<ICounter>();
            for (IClassCoverage iClassCoverage : this.classes) {
                arrayList.add(iClassCoverage.getComplexityCounter());
            }
            return IncrementalCoverage.sum(arrayList);
        }

        public ICounter getMethodCounter() {
            ArrayList<ICounter> arrayList = new ArrayList<ICounter>();
            for (IClassCoverage iClassCoverage : this.classes) {
                arrayList.add(iClassCoverage.getMethodCounter());
            }
            return IncrementalCoverage.sum(arrayList);
        }

        public ICounter getClassCounter() {
            int n = 0;
            int n2 = 0;
            for (IClassCoverage iClassCoverage : this.classes) {
                if (!iClassCoverage.containsCode()) continue;
                if (iClassCoverage.getMethodCounter().getMissedCount() == 0) {
                    ++n;
                    continue;
                }
                ++n2;
            }
            return new SimpleCounter(n, n2);
        }

        public ICounter getCounter(ICoverageNode.CounterEntity counterEntity) {
            switch (counterEntity) {
                case INSTRUCTION: {
                    return this.getInstructionCounter();
                }
                case BRANCH: {
                    return this.getBranchCounter();
                }
                case LINE: {
                    return this.getLineCounter();
                }
                case COMPLEXITY: {
                    return this.getComplexityCounter();
                }
                case METHOD: {
                    return this.getMethodCounter();
                }
            }
            return this.getClassCounter();
        }

        private ICounter sumOf(Collection<IClassCoverage> collection) {
            ArrayList<ICounter> arrayList = new ArrayList<ICounter>();
            for (IClassCoverage iClassCoverage : collection) {
                arrayList.add(iClassCoverage.getInstructionCounter());
            }
            return IncrementalCoverage.sum(arrayList);
        }
    }

    private static abstract class Base
    implements ICoverageNode {
        final ICoverageNode delegate;

        Base(ICoverageNode iCoverageNode) {
            this.delegate = iCoverageNode;
        }

        public ICoverageNode.ElementType getElementType() {
            return this.delegate.getElementType();
        }

        public String getName() {
            return this.delegate.getName();
        }

        public boolean containsCode() {
            return this.delegate.containsCode();
        }

        public ICoverageNode getPlainCopy() {
            return this.delegate.getPlainCopy();
        }
    }
}

