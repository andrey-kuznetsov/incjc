package incjc

import java.nio.file.Path

fun interface ClassFileExaminer {
    fun examine(classFiles: Collection<Path>): Collection<ClassFileDesc>
}