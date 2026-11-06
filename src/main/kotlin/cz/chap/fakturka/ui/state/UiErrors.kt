package cz.chap.fakturka.ui.state

import cz.chap.fakturka.i18n.Messages

/** Runs [block] and captures any exception so the UI can show it instead of crashing. */
fun <T> runCatchingUi(block: () -> T): Result<T> = runCatching(block)

/** Czech error text from the bundle followed by the exception message (storage errors carry the file path). */
fun Throwable.uiMessage(): String {
    val detail = message ?: javaClass.simpleName
    return "${Messages.get("ui.error.generic")} $detail"
}
