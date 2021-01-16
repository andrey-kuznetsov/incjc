package incjc;

import java.util.Set;

public class ClassFileDesc {
    public final String fullClassName;
    public final Set<String> dependsOn;
    public final String sourceFile;

    public ClassFileDesc(String fullClassName, Set<String> dependsOn, String sourceFile) {
        this.fullClassName = fullClassName;
        this.dependsOn = dependsOn;
        this.sourceFile = sourceFile;
    }
}
