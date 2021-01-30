package incjc

import org.apache.commons.io.FileUtils
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

class MetaInfo(private val dir: String) {
    val classes // class name -> source file path
            : MutableMap<String, String>
    val sources // source file path -> hash
            : MutableMap<String, String>
    val deps // class name -> set of dependent class names
            : MutableMap<String, MutableSet<String>>

    companion object {
        fun existsIn(dir: String): Boolean {
            return (Paths.get(dir).toFile().exists()
                    && Paths.get(dir, CLASSES_FILE).toFile().exists()
                    && Paths.get(dir, SOURCES_FILE).toFile().exists()
                    && Paths.get(dir, DEPS_FILE).toFile().exists())
        }

        fun createOrReset(destDir: String) {
            val destPath = Paths.get(destDir)
            val destFile = destPath.toFile()
            try {
                if (destFile.exists()) {
                    if (destFile.isDirectory) {
                        FileUtils.deleteDirectory(destFile)
                    } else {
                        Files.delete(destPath)
                    }
                }
                Files.createDirectory(destPath)
                Files.createFile(destPath.resolve(CLASSES_FILE))
                Files.createFile(destPath.resolve(SOURCES_FILE))
                Files.createFile(destPath.resolve(DEPS_FILE))
            } catch (e: IOException) {
                throw RuntimeException("Failed to intialize metinfo directory $destDir", e)
            }
        }

        private const val CLASSES_FILE = "classes.txt"
        private const val SOURCES_FILE = "sources.txt"
        private const val DEPS_FILE = "deps.txt"
        private const val FIELD_SEP = "->"
    }

    init {
        try {
            classes = readMapFromFile(Paths.get(dir, CLASSES_FILE))
            sources = readMapFromFile(Paths.get(dir, SOURCES_FILE))
            deps = readDepsFromFile(Paths.get(dir, DEPS_FILE))
        } catch (e: IOException) {
            throw RuntimeException("Failed to parse metainfo", e)
        }
    }

    private fun readMapFromFile(filePath: Path): MutableMap<String, String> {
        val result: MutableMap<String, String> = HashMap()
        Files.lines(filePath).forEach { line ->
            if (line.isNotEmpty()) {
                val parts = line.split(FIELD_SEP).toTypedArray()
                result[parts[0]] = parts[1]
            }
        }
        return result
    }

    private fun writeMapToFile(map: Map<String, String>, filePath: Path) {
        PrintWriter(filePath.toFile()).use { w ->
            map.forEach { (k, v) -> w.println(k + FIELD_SEP + v) }
        }
    }

    private fun readDepsFromFile(filePath: Path): MutableMap<String, MutableSet<String>> {
        val result: MutableMap<String, MutableSet<String>> = HashMap()
        Files.lines(filePath).forEach { line ->
            if (line.isNotEmpty()) {
                val parts = line.split(FIELD_SEP).toTypedArray()
                result.computeIfAbsent(parts[0]) { HashSet() }
                    .add(parts[1])
            }
        }
        return result
    }

    private fun writeDepsToFile(filePath: Path) {
        PrintWriter(filePath.toFile()).use { w ->
            deps.forEach { (cls1, set) ->
                set.forEach { cls2 ->
                    w.println(cls1 + FIELD_SEP + cls2)
                }
            }
        }
    }

    fun save() {
        createOrReset(dir)
        try {
            writeMapToFile(classes, Paths.get(dir, CLASSES_FILE))
            writeMapToFile(sources, Paths.get(dir, SOURCES_FILE))
            writeDepsToFile(Paths.get(dir, DEPS_FILE))
        } catch (e: IOException) {
            throw RuntimeException("Failed to save metainfo to $dir", e)
        }
    }

    // XXX: needs optimization
    fun affectedSources(changedSources: Set<String>): Set<String> {
        // 1. find classes from sources given
        val classSet: MutableSet<String> = HashSet()
        classes.forEach { (cls, src) ->
            if (changedSources.contains(src)) {
                classSet.add(cls)
            }
        }
        debug(
            "Dependency search -- initial class set: " + System.lineSeparator() +
                    classSet.joinToString(System.lineSeparator())
        )

        // 2. collect all reachable classes in a graph, starting with classSet
        var reachable: MutableSet<String> = HashSet(classSet)
        do {
            val nextReachable: MutableSet<String> = HashSet()
            for (c in reachable) {
                val dependent: Set<String>? = deps[c]
                if (dependent != null) {
                    for (dep in dependent) {
                        if (!classSet.contains(dep) && !reachable.contains(dep)) {
                            nextReachable.add(dep)
                        }
                    }
                }
            }
            classSet.addAll(reachable)
            reachable = nextReachable
            if (reachable.isNotEmpty()) {
                debug(
                    "Dependency search -- next class set: " + System.lineSeparator() +
                            reachable.joinToString(System.lineSeparator())
                )
            }
        } while (reachable.isNotEmpty())

        // 3. switch from classes back to sources
        return classSet.stream()
            .map { c -> classes[c] }
            .map { s -> requireNotNull(s) }
            .collect(Collectors.toSet())
    }

    // XXX: not optimal -- full classes scan required
    fun classesBySources(changedSources: Set<String>): Set<String> {
        return classes.entries.stream()
            .filter { e -> changedSources.contains(e.value) }
            .map { e -> e.key }
            .collect(Collectors.toSet())
    }

    fun deleteClassesAndDeps(classesToDelete: Set<String>) {
        for (cls in classesToDelete) {
            classes.remove(cls)
            deps.remove(cls)
        }
        for (depSet in deps.values) {
            depSet.removeAll(classesToDelete)
        }
    }

    fun deleteSources(sourcesToDelete: Set<String>) {
        for (src in sourcesToDelete) {
            sources.remove(src)
        }
    }

    fun addSources(moreSources: Map<String, String>) {
        sources.putAll(moreSources)
    }
}