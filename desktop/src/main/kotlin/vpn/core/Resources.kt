package vpn.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Utility to extract files bundled as resources into the app data directory. */
object Resources {

    /**
     * Copies a resource file (from the classpath) to the target file.
     * Returns true if the resource existed and was successfully copied.
     */
    fun extractResource(resourcePath: String, target: File): Boolean {
        val stream = Resources::class.java.getResourceAsStream(resourcePath)
        if (stream == null) {
            AppLog.i("Resources", "Resource not found: $resourcePath")
            return false
        }
        target.parentFile?.mkdirs()
        runCatching {
            Files.copy(stream, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { e ->
            AppLog.e("Resources", "Failed to copy $resourcePath: ${e.message}")
            return false
        }
        return true
    }

    /**
     * Extracts a list of resource files from the same base directory.
     * Returns the number of files successfully extracted.
     */
    fun extractAll(resourceBase: String, fileNames: List<String>, targetDir: File): Int {
        var count = 0
        fileNames.forEach { name ->
            val res = "$resourceBase/$name"
            val target = File(targetDir, name)
            if (extractResource(res, target)) count++
        }
        return count
    }
}