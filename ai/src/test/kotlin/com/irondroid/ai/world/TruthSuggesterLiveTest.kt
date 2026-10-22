package com.irondroid.ai.world

import com.irondroid.ai.ProviderRegistry
import com.irondroid.ai.ProviderSettings
import com.irondroid.ai.openai.DeepSeek
import com.irondroid.engine.truth.TruthLoader
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals

/** Asks the real DeepSeek for the truths of a new world (IRON-77): `.\gradlew.bat :ai:liveTest`. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class TruthSuggesterLiveTest {
    private val deepseek = ProviderRegistry(listOf(DeepSeek.registration()))
        .create(DeepSeek.ID, ProviderSettings(apiKey = System.getenv("DEEPSEEK_API_KEY")))

    private val catalog = TruthLoader.fromResources()

    private val choices = catalog.categories.map { category ->
        TruthChoices(
            category.id,
            catalog.text(category.id, "en")!!.name,
            category.options.associate { it.id to catalog.optionText(category.id, it.id, "en")!!.text },
        )
    }

    @Test
    fun `every category gets a suggestion with a reason in Spanish`() = runBlocking {
        DeepSeek.INFO.models.forEach { model ->
            val started = System.currentTimeMillis()
            val picks = TruthSuggester(deepseek, model.id, choices).suggest("La cruzada del hierro", "Kira", "Las Tierras de Hierro", "es")
            println("${model.id} in ${System.currentTimeMillis() - started} ms:")
            picks.forEach { (category, pick) -> println("  $category -> ${pick.optionId}: ${pick.reason}") }
            assertEquals(catalog.categories.map { it.id }.toSet(), picks.keys, model.id)
        }
    }
}
