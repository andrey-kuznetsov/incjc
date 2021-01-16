package incjc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import org.apache.commons.io.IOUtils;

public abstract class ProcHelpers {

    public static String getJdkExecutable(String name) {
        String jdkHome = Optional.ofNullable(System.getenv("JDK_HOME")).orElse(System.getenv("JAVA_HOME"));
        return jdkHome != null ? Paths.get(jdkHome, "bin", name).toString() : name;
    }

    public static String getProcessOutput(String[] commandLine) {
        Writer output = new StringWriter();
        try {
            Process p = Runtime.getRuntime().exec(commandLine);
            Thread inputPump = inputStreamPump(p.getInputStream(), output);
            inputPump.start();
            int retval = p.waitFor();
            inputPump.join();
            if (retval != 0) {
                throw new RuntimeException(String.format("Nonzero return value of %d was returned by %s",
                    retval, String.join(" ", Arrays.asList(commandLine))));
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        return output.toString();
    }

    public static Thread inputStreamPump(InputStream from, OutputStream to) {
        return new Thread(() -> {
            try {
                IOUtils.copy(from, to);
            } catch (IOException e) {
                e.printStackTrace(System.err);
            }
        });
    }

    public static Thread inputStreamPump(InputStream from, Writer to) {
        return new Thread(() -> {
            try {
                IOUtils.copy(from, to, StandardCharsets.US_ASCII);
            } catch (IOException e) {
                e.printStackTrace(System.err);
            }
        });
    }
}
