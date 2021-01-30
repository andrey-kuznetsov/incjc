package incjc

import com.google.common.collect.Lists
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.stream.Collectors
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class JdkBasedClassFileExaminer : ClassFileExaminer {
    override fun examine(classFiles: Collection<Path>): Collection<ClassFileDesc> {
        val classFileList = classFiles.stream().map { obj: Path -> obj.toString() }.collect(Collectors.toList())

        val javapCmd = Lists.newArrayList(getJdkExecutable("javap"))
        javapCmd.addAll(classFileList)
        val javapOut = getProcessOutput(javapCmd.toArray(arrayOf()))

        val srcPattern = Regex("Compiled from \"(.*\\.java)\"\n.*(class|interface) ([^\\s<]+).*\\{")
        val results = srcPattern.findAll(javapOut)
        val descMap: MutableMap<String, ClassFileDesc> = HashMap()
        for (res in results) {
            val className = res.groupValues[3]
            var packagePathPrefix = ""
            val lastDotIndex = className.lastIndexOf('.')
            if (lastDotIndex != -1) {
                packagePathPrefix =
                    className.substring(0, lastDotIndex).replace('.', File.separatorChar) + File.separatorChar
            }
            val srcFile = packagePathPrefix + res.groupValues[1]
            descMap[className] = ClassFileDesc(className, HashSet(), srcFile)
        }

        fillDependencies(classFileList, descMap)

        return descMap.values
    }

    private fun fillDependencies(classFileList: Collection<String>, target: Map<String, ClassFileDesc>) {
        val jdepsCmd = Lists.newArrayList(getJdkExecutable("jdeps"), "-v")
        jdepsCmd.addAll(classFileList)
        val jdepsOut = getProcessOutput(jdepsCmd.toArray(arrayOf()))
        val leadWhitespacePattern = Regex("\\s+.*")
        val depPattern = Regex("(\\S+)\\s+->\\s+(\\S+)")
        Arrays.stream(jdepsOut.split(System.lineSeparator()).toTypedArray())
            .filter { line -> leadWhitespacePattern.matches(line) }
            .map { line -> depPattern.find(line) }
            .filter { res -> res != null }
            .map { res -> requireNotNull(res) }
            .filter { res -> !isStandardLibraryClass(res.groupValues[2]) }
            .peek { res ->
                if (!target.contains(res.groupValues[1]))
                    throw RuntimeException("Internal error; class not found: " + res.groupValues[1])
            }
            .forEach { res -> target[res.groupValues[1]]!!.dependsOn.add(res.groupValues[2]) }
    }

    private fun isStandardLibraryClass(className: String): Boolean {
        return (className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("javafx."))
    }
}