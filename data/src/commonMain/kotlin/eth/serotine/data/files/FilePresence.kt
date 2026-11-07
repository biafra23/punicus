package eth.serotine.data.files

/**
 * Whether a file is at [path]: true or false only when the file system says so, null when it
 * cannot tell (a directory that cannot be searched, an I/O error). kotlinx-io's `exists` says
 * false for both on the JVM, so a file that cannot be read there looks absent. Never throws.
 */
internal expect fun fileThere(path: String): Boolean?
