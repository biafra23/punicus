package eth.serotine.data.files

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.ENOENT
import platform.posix.F_OK
import platform.posix.access
import platform.posix.errno

@OptIn(ExperimentalForeignApi::class)
internal actual fun fileThere(path: String): Boolean? = when {
    access(path, F_OK) == 0 -> true
    // Only "no such file" says it is absent; any other error says nothing.
    errno == ENOENT -> false
    else -> null
}
