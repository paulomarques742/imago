package eu.studio742.imago.core.designsystem.i18n

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The text files of every module, and the code that should use them.
 *
 * It lives here because this is where the language infrastructure is; it reads the whole repository
 * from the root (where `settings.gradle.kts` is). Two guards:
 *
 * - every translation has the same keys and the same arguments as English — a missing key silently
 *   fell back to English, and an extra `%2$s` blew up when formatting;
 * - interface code has no hand-written sentences. It is not a Kotlin parser: it looks for literals
 *   where the interface shows them, and only those that look like sentences (words separated by
 *   spaces, accents, or a capitalised word). Animation labels and units pass because they do not
 *   look like sentences.
 */
class TranslationFilesTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val resourceFolders: List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".gradle" && it.name != "node_modules" && it.name != ".git" }
        .filter { it.isDirectory && it.name == "composeResources" }
        .filter { File(it, "values/strings.xml").isFile }
        .toList()

    @Test fun everyModuleWithTextsHasTheEnglishBaseAndThePortuguese() {
        assertTrue("Expected the texts of several modules, found ${resourceFolders.size}", resourceFolders.size >= 6)
        resourceFolders.forEach { folder ->
            assertTrue("${folder.relativeTo(root)}: values-pt is missing", File(folder, "values-pt/strings.xml").isFile)
        }
    }

    @Test fun translationsHaveTheSameKeysAndArgumentsAsEnglish() {
        val problems = mutableListOf<String>()
        resourceFolders.forEach { folder ->
            val base = parse(File(folder, "values/strings.xml"))
            folder.listFiles { file -> file.isDirectory && file.name.startsWith("values-") }.orEmpty().forEach { language ->
                val file = File(language, "strings.xml")
                if (!file.isFile) return@forEach
                val translated = parse(file)
                val where = file.relativeTo(root).path
                (base.keys - translated.keys).forEach { problems += "$where: $it is missing" }
                (translated.keys - base.keys).forEach { problems += "$where: $it does not exist in English" }
                base.keys.intersect(translated.keys).forEach { key ->
                    val expected = base.getValue(key).flatMap(::arguments).toSet()
                    val actual = translated.getValue(key).flatMap(::arguments).toSet()
                    if (expected != actual) problems += "$where: $key has the arguments $actual, English has $expected"
                }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test fun textsAreWrittenTheWayComposeResourcesReadThem() {
        val problems = mutableListOf<String>()
        resourceFolders.flatMap { folder ->
            folder.listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty().map { File(it, "strings.xml") }
        }.filter(File::isFile).forEach { file ->
            parse(file).forEach { (key, values) ->
                val where = "${file.relativeTo(root).path}: $key"
                // Compose Resources does not undo Android's `\'`: the backslash showed on screen.
                if (values.any { "\\'" in it }) problems += "$where uses \\' — write ’"
                if (values.any(String::isBlank)) problems += "$where is empty"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    /**
     * Date patterns (`*_pattern`) are `DateTimeFormatter` syntax: literal text is quoted with the ASCII
     * apostrophe. A typographic `’` quotes nothing — "d ’de’ MMM" read the `e` of "de" as the day of
     * the week, and the date came out with extra letters.
     */
    @Test fun datePatternsAreValidAndQuoteWithTheAsciiApostrophe() {
        val problems = mutableListOf<String>()
        resourceFolders.flatMap { folder ->
            folder.listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty().map { File(it, "strings.xml") }
        }.filter(File::isFile).forEach { file ->
            parse(file).filterKeys { it.endsWith("_pattern") }.forEach { (key, values) ->
                val where = "${file.relativeTo(root).path}: $key"
                val pattern = values.single().replace("&amp;", "&")
                if ('’' in pattern) problems += "$where quotes with ’ — in a date pattern it is '"
                runCatching {
                    java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.ROOT)
                        .format(java.time.LocalDateTime.of(2026, 9, 24, 16, 30))
                }.onFailure { problems += "$where is not a valid pattern: ${it.message}" }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test fun pluralsAlwaysHaveTheOtherForm() {
        val problems = mutableListOf<String>()
        resourceFolders.forEach { folder ->
            folder.listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty().forEach { language ->
                val file = File(language, "strings.xml").takeIf(File::isFile) ?: return@forEach
                PLURAL.findAll(file.readText()).forEach { plural ->
                    if ("quantity=\"other\"" !in plural.groupValues[2]) {
                        problems += "${file.relativeTo(root).path}: ${plural.groupValues[1]} without the other form"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test fun interfaceCodeHasNoHandWrittenSentences() {
        val sources = listOf("feature", "core/designsystem", "desktop", "app").map { File(root, it) }
            .flatMap { dir -> dir.walkTopDown().onEnter { it.name != "build" && !it.name.endsWith("Test") }.filter { it.extension == "kt" }.toList() }
            .filter { file -> "${File.separator}src${File.separator}" in file.path && !file.path.contains("Test${File.separator}") }
        val problems = mutableListOf<String>()
        sources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
                UI_LITERAL.findAll(line).forEach { match ->
                    val text = match.groupValues[1]
                    if (looksLikeASentence(text)) problems += "${file.relativeTo(root).path}:${index + 1}: \"$text\""
                }
            }
        }
        if (problems.isNotEmpty()) fail("Interface texts outside the text files:\n" + problems.joinToString("\n"))
    }

    /** The detector itself: a guard that never fires guards nothing. */
    @Test fun theDetectorTellsSentencesFromIdentifiers() {
        fun flagged(line: String) = UI_LITERAL.findAll(line).any { looksLikeASentence(it.groupValues[1]) }
        assertTrue(flagged("""Text("Save changes")"""))
        assertTrue(flagged("""notice("Template saved.")"""))
        assertTrue(flagged("""ToolButton("Duplicate", Icons.Outlined.ContentCopy)"""))
        assertTrue(flagged("""contentDescription = "Page ${'$'}{page + 1}""""))
        assertTrue(flagged("""label = "café""""))
        assertTrue(!flagged("""label = "composerChrome","""))
        assertTrue(!flagged("""suffix = { Text("px") }"""))
        assertTrue(!flagged("""contentDescription = "IMAGO""""))
        assertTrue(!flagged("""Text("${'$'}{page.index + 1}")"""))
    }

    private fun parse(file: File): Map<String, List<String>> {
        val xml = file.readText()
        val strings = STRING.findAll(xml).associate { it.groupValues[1] to listOf(it.groupValues[2]) }
        val plurals = PLURAL.findAll(xml).associate { plural ->
            plural.groupValues[1] to ITEM.findAll(plural.groupValues[2]).map { it.groupValues[1] }.toList()
        }
        return strings + plurals
    }

    private fun arguments(text: String): List<String> = ARGUMENT.findAll(text).map { it.value }.toList()

    private fun looksLikeASentence(literal: String): Boolean {
        val text = literal.replace(TEMPLATE, " ").trim()
        if (text.isEmpty() || text in ALLOWED) return false
        return WORDS.containsMatchIn(text) || text.any { it.isLetter() && it.code > 0x7F } || CAPITALISED.matches(text)
    }

    private companion object {
        val STRING = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLURAL = Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
        val ITEM = Regex("""<item quantity="[^"]+">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
        val ARGUMENT = Regex("""%\d+\$[sd]""")

        /** The places where a literal reaches the screen. */
        val UI_LITERAL = Regex(
            """(?:\bText\(|contentDescription = |\btitle = |\bsubtitle = |\btext = |\blabel = |\bplaceholder = |""" +
                """ToolButton\(|ToolMenuItem\(|ToolMenuButton\(|InspectorSlider\(|showSnackbar\(|actionLabel = |""" +
                """CustomAccessibilityAction\(|onClick\(label = |\bnotice\(|\bmessage = |\berror = )"((?:[^"\\]|\\.)*)"""",
        )
        val TEMPLATE = Regex("""\$\{[^}]*}|\$[A-Za-z_][A-Za-z0-9_.]*""")
        val WORDS = Regex("""\p{L}{2,}\s+\p{L}""")
        val CAPITALISED = Regex("""\p{Lu}\p{Ll}{2,}""")

        /** Proper names and brands: the same in every language. */
        val ALLOWED = setOf("IMAGO", "Immich")
    }
}
