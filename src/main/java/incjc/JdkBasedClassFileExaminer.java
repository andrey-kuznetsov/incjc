package incjc;

import com.google.common.collect.Lists;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static incjc.ProcHelpers.getJdkExecutable;
import static incjc.ProcHelpers.getProcessOutput;

public class JdkBasedClassFileExaminer implements Function<Collection<Path>, Collection<ClassFileDesc>> {

    @Override
    public Collection<ClassFileDesc> apply(Collection<Path> classFiles) {
        List<String> classFileList = classFiles.stream().map(Path::toString).collect(Collectors.toList());

        ArrayList<String> javapCmd = Lists.newArrayList(getJdkExecutable("javap"));
        javapCmd.addAll(classFileList);
        String javapOut = getProcessOutput(javapCmd.toArray(new String[]{}));

        Pattern srcPattern = Pattern.compile("Compiled from \"(.*\\.java)\"\n.*(class|interface) ([^\\s<]+).*\\{");
        Matcher srcMatcher = srcPattern.matcher(javapOut);
        Map<String, ClassFileDesc> descMap = new HashMap<>();
        while (srcMatcher.find()) {
            String className = srcMatcher.group(3);

            String packagePathPrefix = "";
            int lastDotIndex = className.lastIndexOf('.');
            if (lastDotIndex != -1) {
                packagePathPrefix = className.substring(0, lastDotIndex).replace('.', File.separatorChar) + File.separatorChar;
            }

            String srcFile = packagePathPrefix + srcMatcher.group(1);

            descMap.put(className, new ClassFileDesc(className, new HashSet<>(), srcFile));
        }

        fillDependencies(classFileList, descMap);

        return descMap.values();
    }

    private static void fillDependencies(Collection<String> classFileList, Map<String, ClassFileDesc> target) {
        Pattern depPattern = Pattern.compile("(\\S+)\\s+->\\s+(\\S+)");
        ArrayList<String> jdepsCmd = Lists.newArrayList(getJdkExecutable("jdeps"), "-v");
        jdepsCmd.addAll(classFileList);
        String jdepsOut = getProcessOutput(jdepsCmd.toArray(new String[]{}));
        Arrays.stream(jdepsOut.split(System.lineSeparator()))
            .filter(line -> line.matches("\\s+.*"))
            .map(depPattern::matcher)
            .filter(Matcher::find)
            .filter(m -> !isStandardLibraryClass(m.group(2)))
            .forEach(m -> target.get(m.group(1)).dependsOn.add(m.group(2)));
    }

    private static boolean isStandardLibraryClass(String className) {
        return className.startsWith("java.")
            || className.startsWith("javax.")
            || className.startsWith("javafx.");
    }
}
