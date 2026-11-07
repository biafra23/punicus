package eth.serotine.data.files

import java.nio.file.Files
import java.nio.file.Paths

internal actual fun fileThere(path: String): Boolean? = try {
    val file = Paths.get(path)
    when {
        Files.exists(file) -> true
        // True only when the file system said there is no such file.
        Files.notExists(file) -> false
        else -> null
    }
} catch (e: Exception) {
    null
}
