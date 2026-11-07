package eth.serotine.desktop

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The app icon at runtime: the window's and the tray's. A 512 px PNG on the classpath, which
 * the build renders from the Android launcher icon (generateAppIcons, desktop-app/build.gradle.kts)
 * — the same image the deb installs, and the same bat the dmg's and the msi's icons carry.
 */
internal object AppIcon {

    private const val RESOURCE = "/serotine-icon.png"

    /**
     * Read once. Every Gradle build puts it on the classpath; a run that bypassed Gradle (an
     * IDE's own build before generateAppIcons ever ran) gets null, and the window opens without
     * an icon rather than not at all.
     */
    val image: BufferedImage? by lazy {
        val image = AppIcon::class.java.getResourceAsStream(RESOURCE)?.use(ImageIO::read)
        if (image == null) println("Punicus: no app icon ($RESOURCE is not on the classpath)")
        image
    }
}
