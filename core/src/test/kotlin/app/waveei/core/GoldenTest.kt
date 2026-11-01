package app.waveei.core

import app.waveei.core.field.AlreadyInField
import app.waveei.core.text.Separator
import app.waveei.core.text.separatorBefore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the shared cases in `golden/` (see golden/README.md). */
class GoldenTest {
    @Test
    fun alreadyInField() = runCases("already_in_field.json") { case ->
        assertEquals(case.bool("expected"), AlreadyInField.check(case.str("dictation"), case.str("field")))
    }

    @Test
    fun separator() = runCases("separator.json") { case ->
        val separator = Separator.valueOf(case.str("separator"))
        assertEquals(case.str("expected"), separatorBefore(case.str("before"), separator))
    }

    private fun runCases(file: String, check: (JsonObject) -> Unit) {
        val dir = File(System.getProperty("golden.dir") ?: error("golden.dir is not set"))
        val cases = Json.parseToJsonElement(File(dir, file).readText()).jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty(), "$file has no cases")
        val failures = cases.mapNotNull { case ->
            runCatching { check(case) }.exceptionOrNull()?.let { "${case.str("name")}: ${it.message}" }
        }
        assertTrue(failures.isEmpty(), "$file:\n" + failures.joinToString("\n"))
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
}
