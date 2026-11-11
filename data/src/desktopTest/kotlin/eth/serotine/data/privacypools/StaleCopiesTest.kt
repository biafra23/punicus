package eth.serotine.data.privacypools

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The removal of the library copies earlier runs left (`PrivacyPoolsNative.removeStaleCopies`), which
 * the binding runs on Windows alone; tested here on every host, as it is plain file handling.
 */
class StaleCopiesTest {
    private val dir = Files.createTempDirectory("stale-copies-test").toFile()
    private val now = 1_000_000_000_000L
    private val hour = 60 * 60 * 1000L

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(name: String, age: Long) = dir.resolve(name).also {
        it.writeText(name)
        it.setLastModified(now - age)
    }

    @Test
    fun removes_only_the_old_copies_of_the_library() {
        file("punicus-privacy-pools1.dll", 2 * hour)
        file("punicus-privacy-pools2.dll", hour + 1)
        file("punicus-privacy-pools3.dll", hour - 1_000)
        file("punicus-privacy-pools4.dll", 0)
        file("notes.txt", 2 * hour)
        dir.resolve("old.dll").also { it.mkdir(); it.setLastModified(now - 2 * hour) }

        PrivacyPoolsNative.removeStaleCopies(dir, ".dll", now)

        assertEquals(
            listOf("notes.txt", "old.dll", "punicus-privacy-pools3.dll", "punicus-privacy-pools4.dll"),
            dir.list()!!.sorted(),
        )
    }

    @Test
    fun a_missing_directory_is_no_error() {
        PrivacyPoolsNative.removeStaleCopies(dir.resolve("absent"), ".dll", now)
    }
}
