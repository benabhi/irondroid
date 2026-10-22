package com.irondroid.ai.narrator

import com.irondroid.ai.ProviderRegistry
import com.irondroid.ai.ProviderSettings
import com.irondroid.ai.ReasoningEffort
import com.irondroid.ai.TokenUsage
import com.irondroid.ai.openai.DeepSeek
import com.irondroid.ai.tools.ActionTools
import com.irondroid.engine.action.ActionHistory
import com.irondroid.engine.character.Character
import com.irondroid.engine.dice.Dice
import com.irondroid.engine.dice.Die
import com.irondroid.engine.dice.DieRoll
import com.irondroid.engine.dice.SeededDice
import com.irondroid.engine.game.GameAccess
import com.irondroid.engine.game.GameActions
import com.irondroid.engine.move.MoveLoader
import com.irondroid.engine.oracle.OracleTableLoader
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test

/**
 * The narration bench (IRON-110): fixed scenes played against the real AI with the narrator prompt
 * of [NarratorPrompt.VERSION], checked automatically and written to a report, so every change of
 * the prompt is compared with numbers instead of by eye. `.\gradlew.bat :ai:narrationBench`; it
 * needs DEEPSEEK_API_KEY and never runs with `check`. System properties: `bench.model` (default
 * deepseek-flash), `bench.efforts` (default low,high,max) and `bench.out` (the report folder).
 */
@Tag("bench")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class NarrationBench {
    private val provider = ProviderRegistry(listOf(DeepSeek.registration()))
        .create(DeepSeek.ID, ProviderSettings(apiKey = System.getenv("DEEPSEEK_API_KEY")))
    private val model = System.getProperty("bench.model") ?: "deepseek-flash"
    private val efforts = (System.getProperty("bench.efforts") ?: "low,high,max").split(",").map { ReasoningEffort.valueOf(it.trim().uppercase()) }
    private val out = File(System.getProperty("bench.out") ?: "build/reports/narration-bench")
    private val moves = MoveLoader.fromResources()
    private val oracles = OracleTableLoader.fromResources()

    /**
     * The dice of a scene: the action die and challenge dice of the first action roll are [values]
     * (d6, then the two d10), so its outcome is the one the scene needs; every other die, such as
     * the d100 of the oracle tables, is random.
     */
    private class SceneDice(values: List<Int>) : Dice {
        private val d6 = ArrayDeque(values.take(1))
        private val d10 = ArrayDeque(values.drop(1))
        private val rest = SeededDice.fromSeed(110)

        override fun roll(die: Die): DieRoll {
            val fixed = when (die) {
                Die.D6 -> d6.removeFirstOrNull()
                Die.D10 -> d10.removeFirstOrNull()
                else -> null
            }
            return fixed?.let { DieRoll(die, it) } ?: rest.roll(die)
        }
    }

    private class Game(override var sheet: Character) : GameAccess {
        override val characterName = "Kira"
        override val history: ActionHistory<Character>? = null
        override fun update(change: (Character) -> Character) {
            sheet = change(sheet)
        }

        override fun save() {}
    }

    private val past = listOf(
        PastTurn(
            "Llego a Graywatch al caer la tarde y pregunto por la mujer que dirige la aldea.",
            "Graywatch es un puñado de casas de turba tras una empalizada torcida. Cera, una mercenaria de manos cansadas, " +
                "te recibe en la puerta grande: la gente que toma el camino del norte no vuelve. ¿Qué haces?",
        ),
    )

    @Test
    fun `run the bench`() = runBlocking {
        out.mkdirs()
        val results = efforts.flatMap { effort -> SCENES.map { scene -> play(scene, effort) } }
        val file = File(out, "prompt-v${NarratorPrompt.VERSION}.md")
        file.writeText(report(results))
        println("Narration bench: ${results.count { it.passed }} of ${results.size} scenes passed every check. Report: ${file.absolutePath}")
    }

    private suspend fun play(scene: Scene, effort: ReasoningEffort): Result {
        val game = Game(Character.create(edge = 2, heart = 1, iron = 2, shadow = 1, wits = 3))
        val actions = GameActions(game, moves, oracles, SceneDice(scene.dice), "es")
        val context = TurnContext(
            "Kira",
            game.sheet.stats.entries.associate { it.key.name.lowercase() to it.value },
            health = 5,
            spirit = 5,
            supply = 5,
            momentum = 2,
            facts = listOf("Settlement Name: Prefix: Gray- (gris)", "Settlement Name: Suffix: -watch (vigía)", "Character Role: Mercenario", "Ironlander Names (1-100): Cera"),
        )
        val narrator = Narrator(
            provider,
            model,
            ActionTools.forNarrator(actions.dispatcher()),
            { NarratorPrompt.build("Kira", "es", NarratorStyle.PERFORMED, CharacterGender.FEMININE) },
            context = { context },
            effort = { effort },
        )
        val at = Instant.now()
        val outcome = scene.play(narrator, past)
        val choiceOpen = actions.dispatcher().choices.current != null
        val checks = checks(scene, outcome, choiceOpen)
        val cost = outcome.usage?.let { usage -> DeepSeek.INFO.model(model)?.pricing?.cost(usage, at) }
        return Result(scene, effort, outcome, choiceOpen, checks, cost)
    }

    private fun checks(scene: Scene, outcome: TurnOutcome, choiceOpen: Boolean): List<Check> {
        val text = outcome.narration
        // Each tool as "action" or "action:move" or "action:table", to check what the scene expects.
        val tools = outcome.steps.map { step ->
            val target = MOVE.find(step.arguments) ?: TABLE.find(step.arguments)
            step.action + target?.groupValues?.get(1)?.let { ":$it" }.orEmpty()
        }
        val sentences = SENTENCE.findAll(text).count()
        return listOf(
            Check("Narró", text.isNotBlank() && outcome.error == null, outcome.error?.message.orEmpty()),
            Check("Largo (2 a 5 oraciones)", sentences in 2..5, "$sentences oraciones"),
            Check("Un solo párrafo", "\n\n" !in text.trim(), ""),
            if (choiceOpen) {
                Check("Sin pregunta con una elección abierta", !text.trim().endsWith("?"), "")
            } else {
                Check("Termina con una pregunta", text.trim().endsWith("?"), text.trim().takeLast(40))
            },
            Check("Sin voseo", VOSEO.find(text) == null, VOSEO.find(text)?.value.orEmpty()),
            Check("Sin números", DIGIT.find(text) == null, DIGIT.find(text)?.value.orEmpty()),
            Check("Sin frases de asistente", ASSISTANT.find(text) == null, ASSISTANT.find(text)?.value.orEmpty()),
        ) + scene.expect.map { (label, test) -> Check(label, test(tools, text), tools.joinToString(", ")) }
    }

    private fun report(results: List<Result>): String = buildString {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        append("# Banco de pruebas de la narración · prompt v${NarratorPrompt.VERSION}\n\n")
        append("Corrido el $now con `$model`, esfuerzos ${efforts.joinToString(", ") { it.name.lowercase() }}. ")
        append("Kira (femenino), filo 2, corazón 1, hierro 2, sombra 1, ingenio 3; un turno previo en Graywatch con Cera. ")
        append("Los dados de cada escena están fijados para forzar el resultado. Generado por `NarrationBench` (IRON-110).\n\n")

        append("## Resumen por esfuerzo\n\n")
        append("| Esfuerzo | Escenas que pasan todo | Chequeos que pasan | Tiempo medio | Tokens de entrada (en caché) | Tokens de salida | Costo total |\n|---|---|---|---|---|---|---|\n")
        for (effort in efforts) {
            val runs = results.filter { it.effort == effort }
            val usage = runs.mapNotNull { it.outcome.usage }.fold(TokenUsage(0, 0)) { a, b -> a + b }
            append("| ${effort.name.lowercase()} | ${runs.count { it.passed }}/${runs.size} | ${runs.sumOf { r -> r.checks.count { it.ok } }}/${runs.sumOf { it.checks.size }} ")
            append("| ${seconds(runs.map { it.outcome.totalMs }.average().toLong())} | ${usage.promptTokens} (${usage.cachedPromptTokens}) | ${usage.completionTokens} ")
            append("| US$ ${String.format(Locale.ROOT, "%.4f", runs.sumOf { it.cost ?: 0.0 })} |\n")
        }

        append("\n## Chequeos por escena\n\n")
        append("| Escena | ${efforts.joinToString(" | ") { it.name.lowercase() }} |\n|---|${efforts.joinToString("") { "---|" }}\n")
        for (scene in SCENES) {
            append("| ${scene.title} |")
            for (effort in efforts) {
                val result = results.first { it.scene == scene && it.effort == effort }
                val failed = result.checks.filterNot { it.ok }
                append(if (failed.isEmpty()) " ✓ |" else " ✗ ${failed.joinToString("; ") { it.label }} |")
            }
            append("\n")
        }

        append("\n## Narraciones\n")
        for (scene in SCENES) {
            append("\n### ${scene.title}\n\n> ${scene.said}\n")
            for (effort in efforts) {
                val result = results.first { it.scene == scene && it.effort == effort }
                val outcome = result.outcome
                append("\n**${effort.name.lowercase()}** · ${seconds(outcome.totalMs)} · ")
                append("${outcome.usage?.promptTokens ?: 0} → ${outcome.usage?.completionTokens ?: 0} tokens · ")
                append("US$ ${String.format(Locale.ROOT, "%.4f", result.cost ?: 0.0)}\n\n")
                if (outcome.steps.isNotEmpty()) append("Herramientas: ${outcome.steps.joinToString("; ") { "`${it.action}` ${it.summary}" }}\n\n")
                append(outcome.narration.ifBlank { "(sin narración: ${outcome.error?.message})" }.lines().joinToString("\n") { "> $it" }).append("\n\n")
                val failed = result.checks.filterNot { it.ok }
                if (failed.isNotEmpty()) append("Falló: ${failed.joinToString("; ") { "${it.label}${if (it.detail.isNotBlank()) " (${it.detail})" else ""}" }}\n")
            }
        }
    }

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

    /** One scene of the bench: what the player does, the dice it forces and what it expects of the tools and text. */
    private class Scene(
        val title: String,
        val said: String,
        val dice: List<Int> = emptyList(),
        val expect: List<Pair<String, (List<String>, String) -> Boolean>> = emptyList(),
        val play: suspend (Narrator, List<PastTurn>) -> TurnOutcome = { narrator, past -> narrator.play(said, past) },
    )

    private class Check(val label: String, val ok: Boolean, val detail: String)

    private class Result(
        val scene: Scene,
        val effort: ReasoningEffort,
        val outcome: TurnOutcome,
        val choiceOpen: Boolean,
        val checks: List<Check>,
        val cost: Double?,
    ) {
        val passed: Boolean get() = checks.all { it.ok }
    }

    private companion object {
        val MOVE = Regex("\"move\"\\s*:\\s*\"([a-z_]+)\"")
        val TABLE = Regex("\"table\"\\s*:\\s*\"([a-z_0-9]+)\"")
        val SENTENCE = Regex("[.!?…](?=\\s|$|—)")
        val DIGIT = Regex("\\d+")
        val ASSISTANT = Regex("(?i)\\b(claro que|por supuesto|como asistente|en qué puedo|buena elección|qué interesante|excelente idea|¡perfecto)")

        /** Common voseo forms; "estás", "más" and the like are neutral and left out. */
        val VOSEO = Regex(
            "(?i)\\b(tenés|podés|sos|querés|hacés|sabés|ves|decís|sentís|seguís|venís|tirá|mirá|elegí|hacé|decí|pensá|andá|vení|fijate|contame|decime|imaginá|preguntá|marcá|sumá|tomá)\\b",
        )

        fun rolled(move: String): (List<String>, String) -> Boolean = { tools, _ -> "roll_move:$move" in tools }
        val noMove: (List<String>, String) -> Boolean = { tools, _ -> tools.none { it.startsWith("roll_move") } }

        val SCENES = listOf(
            Scene(
                "Llegar a un lugar nuevo",
                "Entro a la aldea vecina, que nunca visité, y busco a alguien que me venda provisiones.",
                expect = listOf("Tira en las tablas antes de inventar" to { tools, _ -> tools.any { it.startsWith("roll_oracle_table") } }),
            ),
            Scene(
                "Turno sin riesgo",
                "Me siento junto al fuego de Cera y escucho lo que cuentan los aldeanos.",
                expect = listOf("No tira movimientos" to noMove),
            ),
            Scene(
                "Éxito parcial con elección",
                "Intento convencer al guardia de la empalizada de que me deje salir de noche.",
                // d6 4 + heart 1 = 5 against 3 and 8: a weak hit.
                dice = listOf(4, 3, 8),
                expect = listOf(
                    "Tira un movimiento" to { tools, _ -> tools.any { it.startsWith("roll_move") } },
                    "Marca su sugerencia" to { tools, _ -> "suggest_option" in tools },
                ),
            ),
            Scene(
                "Fallo con Pagar el precio",
                "Salto el barranco helado para cortar camino.",
                // d6 1 + edge 2 = 3 against 9 and 10: a miss.
                dice = listOf(1, 9, 10),
                expect = listOf(
                    "Tira Pagar el precio" to rolled("pay_the_price"),
                    "Marca su sugerencia" to { tools, _ -> "suggest_option" in tools },
                ),
            ),
            Scene(
                "Fallo con doble",
                "Me lanzo contra el bandido que bloquea el puente.",
                // d6 1 + iron 2 = 3 against 8 and 8: a miss with a match.
                dice = listOf(1, 8, 8),
                expect = listOf("Usa Giro mayor de la trama" to { tools, _ -> "roll_oracle_table:major_plot_twist" in tools }),
            ),
            Scene(
                "Pedir un movimiento",
                "Hago Reunir información +Ingenio",
                dice = listOf(5, 2, 4),
                expect = listOf("Tira lo pedido" to rolled("gather_information")),
                play = { narrator, past -> narrator.requestMove("Hago Reunir información +Ingenio", "gather_information", "wits", past) },
            ),
            Scene(
                "Corrección",
                "Cruzo el río helado a nado.  ✎ Kira no sabe nadar: busca un vado.",
                expect = listOf("No vuelve a nadar" to { _, text -> !Regex("(?i)\\bnad(as|ando|aste)\\b").containsMatchIn(text) }),
                play = { narrator, past ->
                    narrator.correct("Cruzo el río helado a nado.", "Te lanzas al agua helada y nadas hasta la otra orilla.", "Kira no sabe nadar: busca un vado.", past)
                },
            ),
            Scene(
                "Pedido fuera de la ficción",
                "Decime cuántos puntos de salud tengo y explicame las reglas de Enfrentar el peligro.",
                expect = listOf("No tira movimientos" to noMove),
            ),
        )
    }
}
