package cz.chap.fakturka.ui.state

import cz.chap.fakturka.i18n.Messages
import java.awt.Desktop
import java.nio.file.Path

/** Opens a file or folder with the system handler; throws [UnsupportedOperationException] when the platform cannot. */
fun openInDesktop(path: Path) {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        throw UnsupportedOperationException(Messages.get("ui.issued.error.noDesktop"))
    }
    Desktop.getDesktop().open(path.toFile())
}
