package eu.studio742.imago.core.designsystem.i18n

import eu.studio742.imago.core.designsystem.resources.Res
import eu.studio742.imago.core.designsystem.resources.message_trash_failed
import eu.studio742.imago.core.designsystem.resources.sync_pending
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLanguageTest {
    private val original = Locale.getDefault()

    @After
    fun restore() = Locale.setDefault(original)

    @Suppress("DEPRECATION")
    @Test
    fun `the language of an untranslated locale is English`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of(Locale.GERMANY))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of(Locale.JAPAN))
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.of(Locale("pt", "BR")))
    }

    @Test
    fun `the formatting locale keeps the system region when the language is the same`() {
        assertEquals(Locale.UK, AppLanguage.ENGLISH.formattingLocale(Locale.UK))
        assertEquals("PT", AppLanguage.PORTUGUESE.formattingLocale(Locale.UK).country)
    }

    @Test
    fun `texts come out in the locale's language and without the XML backslashes`() = runBlocking {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("Couldn’t move it to the Recycle Bin.", appString(Res.string.message_trash_failed))
        Locale.setDefault(Locale.forLanguageTag("pt-PT"))
        assertEquals("Não foi possível mover para a Reciclagem.", appString(Res.string.message_trash_failed))
    }

    @Test
    fun `a language without a translation falls back to English`() = runBlocking {
        Locale.setDefault(Locale.JAPAN)
        assertEquals("Couldn’t move it to the Recycle Bin.", appString(Res.string.message_trash_failed))
    }

    @Test
    fun `plurals and typed messages resolve in the right language`() = runBlocking {
        Locale.setDefault(Locale.forLanguageTag("pt-PT"))
        assertEquals("3 por enviar", uiPlural(Res.plurals.sync_pending, 3).resolveNow())
        val text = RuntimeException(UserMessageException(UserMessage.TRASH_FAILED)).toUiText()
        assertEquals("Não foi possível mover para a Reciclagem.", text.resolveNow())
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("1 to send", uiPlural(Res.plurals.sync_pending, 1).resolveNow())
    }

    /** What sync writes into the data comes out in the language the app has when it writes it. */
    @Test
    fun `sync names come out in the app language`() = runBlocking {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("Trip (conflict · Studio laptop)", AppSyncTexts.conflictCopyName("Trip", "Studio laptop"))
        assertEquals("another device", AppSyncTexts.unknownDevice())
        Locale.setDefault(Locale.forLanguageTag("pt-PT"))
        assertEquals("Trip (conflito · Studio laptop)", AppSyncTexts.conflictCopyName("Trip", "Studio laptop"))
        assertEquals("outro aparelho", AppSyncTexts.unknownDevice())
    }
}
