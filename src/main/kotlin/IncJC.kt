package incjc

import com.google.common.collect.Lists
import com.google.common.collect.Sets
import incjc.MetaInfo.Companion.createOrReset
import incjc.MetaInfo.Companion.existsIn
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.function.Function
import java.util.stream.Collectors
import kotlin.system.exitProcess

object IncJC {

    const val TMP_INCJC_PREFIX = "tmp-incjc-"

    const val RETVAL_COMPILATION_ERROR = 1
    const val RETVAL_UNEXPECTED_FAILURE = 2
    const val RETVAL_ILLEGAL_ARGS = 3

    private val CLASS_FILE_EXAMINER: ClassFileExaminer = JdkBasedClassFileExaminer()

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (args.size != 2) {
                System.err.println("Usage: incjc <classpath> <sourcepath>")
                exitProcess(RETVAL_ILLEGAL_ARGS)
            }
            val classpath = args[0]
            val sourceDir = args[1]
            if (!compile(classpath, sourceDir)) {
                exitProcess(RETVAL_COMPILATION_ERROR)
            }
        } catch (e: RuntimeException) {
            System.err.println("FAILURE: " + e.message)
            e.printStackTrace(System.err)
            exitProcess(RETVAL_UNEXPECTED_FAILURE)
        }
    }

    fun compile(classpath: String, sourceDir: String): Boolean {
        val absClasspath = Paths.get(classpath).toAbsolutePath().toString()
        val absSourceDir = Paths.get(sourceDir).toAbsolutePath().toString()
        val allSources = findAllSources(absSourceDir)
        if (allSources.isEmpty()) {
            println("No sources found.")
            return true
        } else {
            debug("All sources: " + System.lineSeparator() + allSources.joinToString(System.lineSeparator()))
        }
        val metaPath = metaInfoPathForSourceDir(absSourceDir)
        if (!existsIn(metaPath)) {
            println("No meta information found in $metaPath. Recompiling all sources.")
            return compileFully(absSourceDir, allSources, absClasspath, metaPath)
        }
        return compileIncrementally(absSourceDir, allSources, absClasspath, metaPath)
    }

    private fun compileFully(sourceDir: String, sources: Set<String>, classpath: String, metaPath: String): Boolean {
        try {
            val classPath = Paths.get(classpath)
            if (Files.exists(classPath)) {
                if (Files.isDirectory(classPath)) {
                    FileUtils.cleanDirectory(classPath.toFile())
                } else {
                    throw RuntimeException("Classpath provided is not a directory: $classPath")
                }
            } else {
                Files.createDirectories(classPath)
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to clean directory $classpath")
        }
        if (javac(sources, classpath, classpath)) {
            createOrReset(metaPath)
            val metaInfo = MetaInfo(metaPath)
            enrichMetaInfo(metaInfo, sourceDir, sources, Paths.get(classpath))
            metaInfo.save()
            return true
        }
        return false
    }

    fun compileIncrementally(sourceDir: String, sources: Set<String>, classpath: String, metaPath: String): Boolean {
        val metaInfo = MetaInfo(metaPath)
        val changedAndNewSources = findChangedAndNewSources(sources, metaInfo.sources)
        val deletedSources = Sets.difference(metaInfo.sources.keys, sources)
        val changedAndNewAndDeletedSources: Set<String> = Sets.union(changedAndNewSources.keys, deletedSources)
        val sourcesToRecompile = Sets.difference(
            Sets.union(
                metaInfo.affectedSources(changedAndNewAndDeletedSources),
                changedAndNewAndDeletedSources
            ),
            deletedSources
        )
        if (sourcesToRecompile.isEmpty()) {
            println("Nothing to compile.")
            return true
        } else {
            println(
                "Sources to compile: " + System.lineSeparator() +
                        sourcesToRecompile.joinToString(System.lineSeparator())
            )
        }
        val classpathCopy = getTmpDir()
        val javacDest = getTmpDir()
        try {
            val classesToSkip: Set<String> = metaInfo.classesBySources(sourcesToRecompile)
            val classPath = Paths.get(classpath)
            copyClassFiles(classPath, classpathCopy, Sets.difference(metaInfo.classes.keys, classesToSkip))
            if (javac(sourcesToRecompile, classpathCopy.toString(), javacDest.toString())) {
                metaInfo.deleteClassesAndDeps(classesToSkip)
                metaInfo.deleteSources(deletedSources)
                val newClassNames: MutableSet<String> = HashSet()
                enrichMetaInfo(metaInfo, sourceDir, sourcesToRecompile, javacDest, newClassNames)
                metaInfo.save()
                deleteClassFiles(classPath, classesToSkip)
                copyClassFiles(javacDest, classPath, newClassNames)
                return true
            }
            return false
        } finally {
            FileUtils.deleteQuietly(classpathCopy.toFile())
            FileUtils.deleteQuietly(javacDest.toFile())
        }
    }

    private fun metaInfoPathForSourceDir(sourceDir: String): String {
        return String.format(
            "%s%s.incjc-meta-%s",
            System.getProperty("user.home"), File.separator, DigestUtils.md5Hex(sourceDir)
        )
    }

    private fun enrichMetaInfo(metaInfo: MetaInfo, sourceDir: String, sources: Set<String>, classesRoot: Path) {
        enrichMetaInfo(metaInfo, sourceDir, sources, classesRoot, null)
    }

    private fun enrichMetaInfo(
        metaInfo: MetaInfo, sourceDir: String, sources: Set<String>, classesRoot: Path,
        outClassNames: MutableSet<String>?
    ) {
        metaInfo.addSources(
            sources.stream().collect(
                Collectors.toMap(Function.identity(),
                    { src: String -> hash(File(src)) })
            )
        )
        for (desc in CLASS_FILE_EXAMINER.examine(findAllClassFiles(classesRoot))) {
            metaInfo.classes[desc.fullClassName] = sourceDir + File.separator + desc.sourceFile
            for (dep in desc.dependsOn) {
                metaInfo.deps.computeIfAbsent(dep) { HashSet() }.add(desc.fullClassName)
            }
            outClassNames?.add(desc.fullClassName)
        }
    }

    private fun findAllSources(dir: String): Set<String> {
        return try {
            Files.find(
                Paths.get(dir),
                Int.MAX_VALUE,
                { path, attrs -> attrs.isRegularFile && path.toFile().name.endsWith(".java") }
            )
                .map { obj -> obj.toString() }
                .collect(Collectors.toSet())
        } catch (e: IOException) {
            throw RuntimeException("Failed to find source files", e)
        }
    }

    private fun findChangedAndNewSources(allSources: Set<String>, prevSourceHashes: Map<String, String>)
            : Map<String, String> {
        val result: MutableMap<String, String> = HashMap()
        for (src in allSources) {
            val oldHash = prevSourceHashes[src]
            val newHash = hash(Paths.get(src).toFile())
            debug(
                "Comparing hashes for " + src + ":" +
                        System.lineSeparator() + "old = " + oldHash +
                        System.lineSeparator() + "new = " + newHash
            )
            if (oldHash != newHash) {
                result[src] = newHash
            }
        }
        if (result.isNotEmpty()) {
            debug("Changed / new sources: " + System.lineSeparator() + result.keys.joinToString(System.lineSeparator()))
        } else {
            debug("No changed / new sources found")
        }
        return result
    }

    private fun copyClassFiles(src: Path, dst: Path, classNames: Set<String>) {
        if (classNames.isNotEmpty()) {
            debug(
                "Copying classes to " + dst + ":" + System.lineSeparator() +
                        classNames.joinToString(System.lineSeparator())
            )
        }
        for (className in classNames) {
            val relPathToClass = classNameToFileName(className)
            val srcFile = src.resolve(relPathToClass)
            val dstFile = dst.resolve(relPathToClass)
            try {
                Files.createDirectories(dstFile.parent)
                Files.copy(srcFile, dstFile)
            } catch (e: IOException) {
                throw RuntimeException("Failed to copy class from $srcFile to $dstFile", e)
            }
        }
    }

    private fun deleteClassFiles(classPath: Path, classNames: Set<String>) {
        for (className in classNames) {
            val filePath = classPath.resolve(classNameToFileName(className))
            debug("Deleting class file $filePath")
            try {
                Files.delete(filePath)
            } catch (e: IOException) {
                throw RuntimeException("Failed to delete class file $filePath", e)
            }
        }
    }

    private fun classNameToFileName(className: String): String {
        return className.replace(".", File.separator) + ".class"
    }

    private fun hash(file: File): String {
        return try {
            DigestUtils.sha256Hex(Files.readAllBytes(file.toPath()))
        } catch (e: IOException) {
            throw RuntimeException("Failed to calculate hash for $file", e)
        }
    }

    private fun getTmpDir(): Path {
        return try {
            Files.createTempDirectory(TMP_INCJC_PREFIX)
        } catch (e: IOException) {
            throw RuntimeException("Failed to create temporary directory", e)
        }
    }

    private fun javac(sources: Set<String>, classpath: String, dstDir: String): Boolean {
        var cp: String = classpath
        return try {
            val classpathEnv = System.getenv("CLASSPATH")
            if (classpathEnv != null) {
                cp += File.pathSeparator + classpathEnv
            }
            val cmd = Lists.newArrayList(getJdkExecutable("javac"), "-cp", cp, "-d", dstDir)
            cmd.addAll(sources)
            debug(java.lang.String.join(" ", cmd))
            val p = Runtime.getRuntime().exec(cmd.toArray(arrayOf()))
            val inputPump = inputStreamPump(p.inputStream, System.out)
            val errorPump = inputStreamPump(p.errorStream, System.err)
            inputPump.start()
            errorPump.start()
            val retval = p.waitFor()
            inputPump.join()
            errorPump.join()
            retval == 0
        } catch (e: IOException) {
            throw RuntimeException(e)
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }

    private fun findAllClassFiles(dir: Path): Set<Path> {
        return try {
            Files.find(dir, Int.MAX_VALUE,
                { path, attrs -> attrs.isRegularFile && path.toFile().name.endsWith(".class") }
            ).collect(Collectors.toSet())
        } catch (e: IOException) {
            throw RuntimeException("Failed while finding class files in $dir", e)
        }
    }
}