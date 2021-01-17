package incjc;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static incjc.ProcHelpers.getJdkExecutable;
import static incjc.ProcHelpers.getProcessOutput;

public class JdkBasedClassFileExaminer implements Function<Path, ClassFileDesc> {

    @Override
    public ClassFileDesc apply(Path classFile) {
        String javapOut = getProcessOutput(new String[] { getJdkExecutable("javap"), classFile.toString() });

        Pattern srcPattern = Pattern.compile("Compiled from \"(.*\\.java)\"");
        Matcher srcMatcher = srcPattern.matcher(javapOut);
        if (!srcMatcher.find()) {
            throw new RuntimeException("Failed to find source file name with javap for " + classFile);
        }

        Pattern classNamePattern = Pattern.compile("(class|interface) ([^\\s<]+).*\\{");
        Matcher classNameMatcher = classNamePattern.matcher(javapOut);

        if (!classNameMatcher.find()) {
            throw new RuntimeException("Failed to find full class name with javap for " + classFile);
        }

        String className = classNameMatcher.group(2);

        String packagePathPrefix = "";
        int lastDotIndex = className.lastIndexOf('.');
        if (lastDotIndex != -1) {
            packagePathPrefix = className.substring(0, lastDotIndex).replace('.', File.separatorChar) + File.separatorChar;
        }

        String srcFile = packagePathPrefix + srcMatcher.group(1);

        return new ClassFileDesc(className, getClassDependencies(className, classFile), srcFile);
    }

    private static Set<String> getClassDependencies(String className, Path classFile) {
        Pattern depPattern = Pattern.compile("->\\s+(\\S+)");
        String jdepsOut = getProcessOutput(new String[] { getJdkExecutable("jdeps"), "-v", classFile.toString() });
        return Arrays.stream(jdepsOut.split(System.lineSeparator()))
            .filter(line -> line.matches("\\s+" + className.replace(".", "\\.").replace("$", "\\$") + ".*"))
            .map(depPattern::matcher)
            .filter(Matcher::find)
            .map(m -> m.group(1))
            .filter(depClass -> !isStandardLibraryClass(depClass))
            .collect(Collectors.toSet());
    }

    private static boolean isStandardLibraryClass(String className) {
        return className.startsWith("java.")
            || className.startsWith("javax.")
            || className.startsWith("javafx.");
    }
}
