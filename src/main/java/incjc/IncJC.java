package incjc;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.Nullable;

import static incjc.Debug.debug;
import static incjc.ProcHelpers.getJdkExecutable;
import static incjc.ProcHelpers.inputStreamPump;

public class IncJC {

    public static final String TMP_INCJC_PREFIX = "tmp-incjc-";

    public static final int RETVAL_COMPILATION_ERROR = 1;
    public static final int RETVAL_UNEXPECTED_FAILURE = 2;
    public static final int RETVAL_ILLEGAL_ARGS = 3;

    private static final Function<Collection<Path>, Collection<ClassFileDesc>> CLASS_FILE_EXAMINER =
        new JdkBasedClassFileExaminer();

    public static void main(String[] args) {
        try {
            if (args.length != 2) {
                System.err.println("Usage: incjc <classpath> <sourcepath>");
                System.exit(RETVAL_ILLEGAL_ARGS);
            }

            String classpath = args[0];
            String sourceDir = args[1];
            if (!compile(classpath, sourceDir)) {
                System.exit(RETVAL_COMPILATION_ERROR);
            }
        } catch (RuntimeException e) {
            System.err.println("FAILURE: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(RETVAL_UNEXPECTED_FAILURE);
        }
    }

    public static boolean compile(String classpath, String sourceDir) {
        String absClasspath = Paths.get(classpath).toAbsolutePath().toString();
        String absSourceDir = Paths.get(sourceDir).toAbsolutePath().toString();

        Set<String> allSources = findAllSources(absSourceDir);
        if (allSources.isEmpty()) {
            System.out.println("No sources found.");
            return true;
        } else {
            debug("All sources: " + System.lineSeparator() + String.join(System.lineSeparator(), allSources));
        }

        String metaPath = metaInfoPathForSourceDir(absSourceDir);

        if (!MetaInfo.existsIn(metaPath)) {
            System.out.println("No meta information found in " + metaPath + ". Recompiling all sources.");
            return compileFully(absSourceDir, allSources, absClasspath, metaPath);
        }
        return compileIncrementally(absSourceDir, allSources, absClasspath, metaPath);
    }

    private static boolean compileFully(String sourceDir, Set<String> sources, String classpath, String metaPath) {
        try {
            Path classPath = Paths.get(classpath);
            if (Files.exists(classPath)) {
                if (Files.isDirectory(classPath)) {
                    FileUtils.cleanDirectory(classPath.toFile());
                } else {
                    throw new RuntimeException("Classpath provided is not a directory: " + classPath);
                }
            } else {
                Files.createDirectories(classPath);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to clean directory " + classpath);
        }

        if (javac(sources, classpath, classpath)) {
            MetaInfo.createOrReset(metaPath);
            MetaInfo metaInfo = new MetaInfo(metaPath);
            enrichMetaInfo(metaInfo, sourceDir, sources, Paths.get(classpath));
            metaInfo.save();
            return true;
        }

        return false;
    }

    public static boolean compileIncrementally(String sourceDir, Set<String> sources, String classpath, String metaPath) {
        MetaInfo metaInfo = new MetaInfo(metaPath);
        Map<String, String> changedAndNewSources = findChangedAndNewSources(sources, metaInfo.sources);
        Set<String> deletedSources = Sets.difference(metaInfo.sources.keySet(), sources);
        Set<String> changedAndNewAndDeletedSources = Sets.union(changedAndNewSources.keySet(), deletedSources);
        Set<String> sourcesToRecompile = Sets.difference(
            Sets.union(
                metaInfo.affectedSources(changedAndNewAndDeletedSources),
                changedAndNewAndDeletedSources),
            deletedSources);

        if (sourcesToRecompile.isEmpty()) {
            System.out.println("Nothing to compile.");
            return true;
        } else {
            System.out.println("Sources to compile: " + System.lineSeparator() +
                String.join(System.lineSeparator(), sourcesToRecompile));
        }

        Path classpathCopy = getTmpDir();
        Path javacDest = getTmpDir();
        try {
            Set<String> classesToSkip = metaInfo.classesBySources(sourcesToRecompile);
            Path classPath = Paths.get(classpath);
            copyClassFiles(classPath, classpathCopy, Sets.difference(metaInfo.classes.keySet(), classesToSkip));
            if (javac(sourcesToRecompile, classpathCopy.toString(), javacDest.toString())) {
                metaInfo.deleteClassesAndDeps(classesToSkip);
                metaInfo.deleteSources(deletedSources);
                Set<String> newClassNames = new HashSet<>();
                enrichMetaInfo(metaInfo, sourceDir, sourcesToRecompile, javacDest, newClassNames);
                metaInfo.save();
                deleteClassFiles(classPath, classesToSkip);
                copyClassFiles(javacDest, classPath, newClassNames);
                return true;
            }
            return false;
        } finally {
            FileUtils.deleteQuietly(classpathCopy.toFile());
            FileUtils.deleteQuietly(javacDest.toFile());
        }
    }

    private static String metaInfoPathForSourceDir(String sourceDir) {
        return String.format("%s%s.incjc-meta-%s",
            System.getProperty("user.home"), File.separator, DigestUtils.md5Hex(sourceDir));
    }

    private static void enrichMetaInfo(MetaInfo metaInfo, String sourceDir, Set<String> sources, Path classesRoot) {
        enrichMetaInfo(metaInfo, sourceDir, sources, classesRoot, null);
    }

    private static void enrichMetaInfo(MetaInfo metaInfo, String sourceDir, Set<String> sources, Path classesRoot,
        @Nullable Set<String> outClassNames)
    {
        metaInfo.addSources(sources.stream().collect(Collectors.toMap(Function.identity(), src -> hash(new File(src)))));
        for (ClassFileDesc desc : CLASS_FILE_EXAMINER.apply(findAllClassFiles(classesRoot))) {
            metaInfo.classes.put(desc.fullClassName, sourceDir + File.separator + desc.sourceFile);
            for (String dep : desc.dependsOn) {
                metaInfo.deps.computeIfAbsent(dep, k -> new HashSet<>()).add(desc.fullClassName);
            }
            if (outClassNames != null) {
                outClassNames.add(desc.fullClassName);
            }
        }
    }

    private static Set<String> findAllSources(String dir) {
        try {
            return Files.find(Paths.get(dir), Integer.MAX_VALUE,
                (path, attrs) -> attrs.isRegularFile() && path.toFile().getName().endsWith(".java"))
                .map(Path::toString)
                .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException("Failed to find source files", e);
        }
    }

    private static Map<String, String> findChangedAndNewSources(Set<String> allSources, Map<String, String> prevSourceHashes) {
        Map<String, String> result = new HashMap<>();

        allSources.forEach(src -> {
            String oldHash = prevSourceHashes.get(src);
            String newHash = hash(Paths.get(src).toFile());
            debug("Comparing hashes for " + src + ":" +
                System.lineSeparator() + "old = " + oldHash +
                System.lineSeparator() + "new = " + newHash);
            if (!Objects.equals(oldHash, newHash)) {
                result.put(src, newHash);
            }
        });

        if (!result.isEmpty()) {
            debug("Changed / new sources: " + System.lineSeparator() + String.join(System.lineSeparator(), result.keySet()));
        } else {
            debug("No changed / new sources found");
        }

        return result;
    }

    private static void copyClassFiles(Path src, Path dst, Set<String> classNames) {
        if (!classNames.isEmpty()) {
            debug("Copying classes to " + dst + ":" + System.lineSeparator() + String.join(System.lineSeparator(), classNames));
        }
        for (String className : classNames) {
            String relPathToClass = classNameToFileName(className);
            Path srcFile = src.resolve(relPathToClass);
            Path dstFile = dst.resolve(relPathToClass);
            try {
                Files.createDirectories(dstFile.getParent());
                Files.copy(srcFile, dstFile);
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy class from " + srcFile +  " to " + dstFile, e);
            }
        }
    }

    private static void deleteClassFiles(Path classPath, Set<String> classNames) {
        for (String className : classNames) {
            Path filePath = classPath.resolve(classNameToFileName(className));
            debug("Deleting class file " + filePath);
            try {
                Files.delete(filePath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete class file " + filePath, e);
            }
        }
    }

    private static String classNameToFileName(String className) {
        return className.replace(".", File.separator) + ".class";
    }

    private static String hash(File file) {
        try {
            return DigestUtils.sha256Hex(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to calculate hash for " + file, e);
        }
    }

    private static Path getTmpDir() {
        try {
            return Files.createTempDirectory(TMP_INCJC_PREFIX);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temporary directory", e);
        }
    }

    private static boolean javac(Set<String> sources, String classpath, String dstDir) {
        try {
            String classpathEnv = System.getenv("CLASSPATH");
            if (classpathEnv != null) {
                classpath += File.pathSeparator + classpathEnv;
            }
            ArrayList<String> cmd = Lists.newArrayList(getJdkExecutable("javac"), "-cp", classpath, "-d", dstDir);
            cmd.addAll(sources);
            debug(String.join(" ", cmd));
            Process p = Runtime.getRuntime().exec(cmd.toArray(new String[]{}));
            Thread inputPump = inputStreamPump(p.getInputStream(), System.out);
            Thread errorPump = inputStreamPump(p.getErrorStream(), System.err);
            inputPump.start();
            errorPump.start();
            int retval = p.waitFor();
            inputPump.join();
            errorPump.join();
            return retval == 0;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static Set<Path> findAllClassFiles(Path dir) {
        try {
            return Files.find(dir, Integer.MAX_VALUE,
                (path, attrs) -> attrs.isRegularFile() && path.toFile().getName().endsWith(".class"))
                .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException("Failed while finding class files in " + dir, e);
        }
    }
}
