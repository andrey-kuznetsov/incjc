package incjc

import org.apache.commons.io.IOUtils
import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

fun getJdkExecutable(name: String): String {
    val jdkHome = System.getenv("JDK_HOME") ?: System.getenv("JAVA_HOME")
    return if (jdkHome != null) Paths.get(jdkHome, "bin", name).toString() else name
}

fun getProcessOutput(commandLine: Array<String?>): String {
    val output = StringWriter()
    try {
        val p = Runtime.getRuntime().exec(commandLine)
        val inputPump = inputStreamPump(p.inputStream, output)
        inputPump.start()
        val retval = p.waitFor()
        inputPump.join()
        if (retval != 0) {
            val cmd = commandLine.joinToString(" ")
            throw RuntimeException("Nonzero return value of $retval was returned by $cmd")
        }
    } catch (e: IOException) {
        throw RuntimeException(e)
    } catch (e: InterruptedException) {
        throw RuntimeException(e)
    }
    return output.toString()
}

fun inputStreamPump(from: InputStream, to: OutputStream): Thread {
    return Thread {
        try {
            IOUtils.copy(from, to)
        } catch (e: IOException) {
            e.printStackTrace(System.err)
        }
    }
}

fun inputStreamPump(from: InputStream?, to: Writer?): Thread {
    return Thread {
        try {
            IOUtils.copy(from, to, StandardCharsets.US_ASCII)
        } catch (e: IOException) {
            e.printStackTrace(System.err)
        }
    }
}
