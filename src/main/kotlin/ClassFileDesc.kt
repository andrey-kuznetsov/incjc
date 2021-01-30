package incjc

class ClassFileDesc(val fullClassName: String, val dependsOn: MutableSet<String>, val sourceFile: String)
