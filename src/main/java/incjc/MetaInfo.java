package incjc;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;

import static incjc.Debug.debug;

public class MetaInfo {
    public final String dir;

    public final Map<String, String> classes;   // class name -> source file path
    public final Map<String, String> sources;   // source file path -> hash
    public final Map<String, Set<String>> deps; // class name -> set of dependent class names

    private static final String CLASSES_FILE = "classes.txt";
    private static final String SOURCES_FILE = "sources.txt";
    private static final String DEPS_FILE = "deps.txt";
    private static final String FIELD_SEP = "->";

    public MetaInfo(String dir) {
        this.dir = dir;
        try {
            classes = readMapFromFile(Paths.get(dir, CLASSES_FILE));
            sources = readMapFromFile(Paths.get(dir, SOURCES_FILE));
            deps = readDepsFromFile(Paths.get(dir, DEPS_FILE));
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to parse metainfo", e);
        }
    }

    private Map<String, String> readMapFromFile(Path filePath) throws IOException {
        Map<String, String> result = new HashMap<>();

        Files.lines(filePath).forEach(line -> {
            if (!line.isEmpty()) {
                String[] parts = line.split(FIELD_SEP);
                result.put(parts[0], parts[1]);
            }
        });

        return result;
    }

    private void writeMapToFile(Map<String, String> map, Path filePath) throws IOException {
        try (PrintWriter w = new PrintWriter(filePath.toFile())) {
            map.forEach((k, v) -> w.println(k + FIELD_SEP + v));
        }
    }

    private Map<String, Set<String>> readDepsFromFile(Path filePath) throws IOException {
        Map<String, Set<String>> result = new HashMap<>();

        Files.lines(filePath).forEach(line -> {
            if (!line.isEmpty()) {
                String[] parts = line.split(FIELD_SEP);
                result.computeIfAbsent(parts[0], k -> new HashSet<>()).add(parts[1]);
            }
        });

        return result;
    }

    private void writeDepsToFile(Path filePath) throws IOException {
        try (PrintWriter w = new PrintWriter(filePath.toFile())) {
            deps.forEach((cls1, set) -> set.forEach(cls2 -> w.println(cls1 + FIELD_SEP + cls2)));
        }
    }

    public void save() {
        createOrReset(dir);
        try {
            writeMapToFile(classes, Paths.get(dir, CLASSES_FILE));
            writeMapToFile(sources, Paths.get(dir, SOURCES_FILE));
            writeDepsToFile(Paths.get(dir, DEPS_FILE));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save metainfo to " + dir, e);
        }
    }

    public static boolean existsIn(String dir) {
        return Paths.get(dir).toFile().exists()
            && Paths.get(dir, CLASSES_FILE).toFile().exists()
            && Paths.get(dir, SOURCES_FILE).toFile().exists()
            && Paths.get(dir, DEPS_FILE).toFile().exists();
    }

    public static void createOrReset(String destDir) {
        Path destPath = Paths.get(destDir);
        File destFile = destPath.toFile();
        try {
            if (destFile.exists()) {
                if (destFile.isDirectory()) {
                    FileUtils.deleteDirectory(destFile);
                } else {
                    Files.delete(destPath);
                }
            }
            Files.createDirectory(destPath);
            Files.createFile(destPath.resolve(CLASSES_FILE));
            Files.createFile(destPath.resolve(SOURCES_FILE));
            Files.createFile(destPath.resolve(DEPS_FILE));
        } catch (IOException e) {
            throw new RuntimeException("Failed to intialize metinfo directory " + destDir, e);
        }
    }

    // XXX: needs optimization
    public Set<String> affectedSources(Set<String> changedSources) {
        // 1. find classes from sources given
        Set<String> classSet = new HashSet<>();
        classes.forEach((cls, src) -> {
            if (changedSources.contains(src)) {
                classSet.add(cls);
            }
        });
        debug("Dependency search -- initial class set: " + System.lineSeparator() + String.join(System.lineSeparator(), classSet));

        // 2. collect all reachable classes in a graph, starting with classSet
        Set<String> reachable = new HashSet<>(classSet);
        do {
            Set<String> nextReachable = new HashSet<>();
            for (String c : reachable) {
                Set<String> dependent = deps.get(c);
                if (dependent != null) {
                    for (String dep : dependent) {
                        if (!classSet.contains(dep) && !reachable.contains(dep)) {
                            nextReachable.add(dep);
                        }
                    }
                }
            }
            classSet.addAll(reachable);
            reachable = nextReachable;
            if (!reachable.isEmpty()) {
                debug("Dependency search -- next class set: " + System.lineSeparator() + String.join(System.lineSeparator(), reachable));
            }
        } while (!reachable.isEmpty());

        // 3. switch from classes back to sources
        return classSet.stream().map(classes::get).collect(Collectors.toSet());
    }

    // XXX: not optimal -- full classes scan required
    public Set<String> classesBySources(Set<String> changedSources) {
        return classes.entrySet().stream()
            .filter(e -> changedSources.contains(e.getValue()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    public void deleteClassesAndDeps(Set<String> classesToDelete) {
        for (String cls : classesToDelete) {
            classes.remove(cls);
            deps.remove(cls);
        }

        for (Set<String> depSet : deps.values()) {
            depSet.removeAll(classesToDelete);
        }
    }

    public void deleteSources(Set<String> sourcesToDelete) {
        for (String src : sourcesToDelete) {
            sources.remove(src);
        }
    }

    public void addSources(Map<String, String> moreSources) {
        sources.putAll(moreSources);
    }
 }
