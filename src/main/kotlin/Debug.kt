package incjc

val DEBUG_ENABLED = setOf("1", "true", "TRUE", "yes", "Y").contains(System.getenv("INCJC_DEBUG"))

fun debug(msg: String) {
    if (DEBUG_ENABLED) {
        println("[DEBUG] $msg")
    }
}
