package com.irondroid.prototype

import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.ai.AiException
import com.irondroid.ai.AiProvider
import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ChatRequest
import com.irondroid.ai.ReasoningEffort
import com.irondroid.ai.TokenUsage
import com.irondroid.ai.collectReply
import com.irondroid.ai.narrator.NarratorPrompt
import com.irondroid.ai.narrator.NarratorStyle
import com.irondroid.ai.tools.ActionTools
import com.irondroid.engine.action.DispatchResult
import com.irondroid.settings.aiErrorText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the AI test screen shows after (or during) one request. */
private data class AiTestResult(
    val text: String = "",
    val firstTextMs: Long? = null,
    val totalMs: Long? = null,
    val usage: TokenUsage? = null,
    /** The actions the AI ran, one line each, when it played with the tools (IRON-48). */
    val steps: List<String> = emptyList(),
    @StringRes val error: Int? = null,
    val errorDetail: String? = null,
)

/**
 * Development-only screen (IRON-47) to talk to the real AI provider and watch the answer stream in.
 * [provider] is null when no API key is saved; [defaultModel] is the one chosen in the settings. With [tools] (IRON-48) the AI can run the game's
 * actions on [campaignTitle], the last campaign played, whose character is [characterName]. The
 * system prompt is the narrator's (IRON-51), in [language], with the style chosen on the screen.
 */
@Composable
fun AiTestScreen(
    provider: AiProvider?,
    defaultModel: String,
    tools: ActionTools?,
    campaignTitle: String?,
    characterName: String?,
    language: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(defaultModel) }
    var result by remember { mutableStateOf<AiTestResult?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var withActions by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(NarratorStyle.PERFORMED) }
    val running = job?.isActive == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.ai_test_back)) }
            Text(stringResource(R.string.ai_test_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        }
        if (provider == null) {
            Text(stringResource(R.string.ai_test_no_key), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        Text(stringResource(R.string.ai_test_provider, provider.info.name), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            provider.info.models.forEach { option ->
                FilterChip(selected = model == option.id, onClick = { model = option.id }, label = { Text(option.id) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = withActions,
                enabled = tools != null,
                onClick = { withActions = !withActions },
                label = { Text(stringResource(R.string.ai_test_with_actions)) },
            )
            FilterChip(
                selected = style == NarratorStyle.PERFORMED,
                onClick = { style = NarratorStyle.PERFORMED },
                label = { Text(stringResource(R.string.ai_test_style_performed)) },
            )
            FilterChip(
                selected = style == NarratorStyle.SOBER,
                onClick = { style = NarratorStyle.SOBER },
                label = { Text(stringResource(R.string.ai_test_style_sober)) },
            )
        }
        Text(
            if (campaignTitle != null) stringResource(R.string.ai_test_campaign, campaignTitle) else stringResource(R.string.ai_test_no_campaign),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.ai_test_input)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !running && input.isNotBlank(),
                onClick = {
                    job = scope.launch {
                        result = AiTestResult()
                        val prompt = NarratorPrompt.build(characterName ?: DEFAULT_CHARACTER, language, style)
                        result = ask(provider, model, prompt, input.trim(), tools.takeIf { withActions }) { result = it }
                    }
                },
            ) { Text(stringResource(R.string.ai_test_send)) }
            OutlinedButton(enabled = running, onClick = { job?.cancel() }) { Text(stringResource(R.string.ai_test_stop)) }
        }
        result?.let { shown ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.steps.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (shown.text.isNotEmpty()) Text(shown.text, style = MaterialTheme.typography.bodyLarge)
                    else if (shown.error == null) Text(stringResource(R.string.ai_test_waiting), style = MaterialTheme.typography.bodyMedium)
                    shown.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    shown.errorDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (shown.totalMs != null) {
                        Text(
                            stringResource(
                                R.string.ai_test_metrics,
                                shown.firstTextMs ?: 0L,
                                shown.totalMs,
                                shown.usage?.promptTokens ?: 0,
                                shown.usage?.cachedPromptTokens ?: 0,
                                shown.usage?.completionTokens ?: 0,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sends the player's input and reports the answer as it streams in; returns the final state. With
 * [tools], the AI's tool calls run on the open campaign and their results go back to it, for up to
 * [MAX_STEPS] rounds (the real turn loop is IRON-52).
 */
private suspend fun ask(
    provider: AiProvider,
    model: String,
    narratorPrompt: String,
    input: String,
    tools: ActionTools?,
    onUpdate: (AiTestResult) -> Unit,
): AiTestResult {
    val started = SystemClock.elapsedRealtime()
    var current = AiTestResult()
    val conversation = mutableListOf<ChatMessage>(ChatMessage.System(if (tools == null) narratorPrompt + NO_TOOLS_NOTE else narratorPrompt), ChatMessage.User(input))
    var usage: TokenUsage? = null
    return try {
        for (step in 1..MAX_STEPS) {
            // Text from a new round of the tool loop starts a new paragraph.
            var separator = if (current.text.isEmpty()) "" else "\n\n"
            val reply = provider.chat(
                ChatRequest(model, conversation, tools?.specs.orEmpty(), reasoningEffort = ReasoningEffort.LOW),
            ).collectReply { piece ->
                val first = current.firstTextMs ?: (SystemClock.elapsedRealtime() - started)
                current = current.copy(text = current.text + separator + piece, firstTextMs = first)
                separator = ""
                onUpdate(current)
            }
            usage = reply.usage?.let { usage?.plus(it) ?: it } ?: usage
            conversation += reply.toMessage() ?: break
            if (tools == null || reply.toolCalls.isEmpty()) break
            reply.toolCalls.forEach { call ->
                val run = tools.run(call)
                conversation += run.message
                val line = "${call.name} ${call.arguments} → " + when (val result = run.result) {
                    is DispatchResult.Success -> result.result.summary
                    is DispatchResult.Failure -> "error: ${result.error.message}"
                    is DispatchResult.NeedsConfirmation -> "needs confirmation"
                }
                Log.i(TAG, line)
                current = current.copy(steps = current.steps + line)
                onUpdate(current)
            }
        }
        val total = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "model=$model tools=${tools != null} firstText=${current.firstTextMs}ms total=${total}ms usage=$usage")
        current.copy(totalMs = total, usage = usage)
    } catch (e: AiException) {
        Log.w(TAG, "model=$model failed: ${e.kind} ${e.message}")
        current.copy(error = aiErrorText(e.kind), errorDetail = e.message, totalMs = SystemClock.elapsedRealtime() - started)
    }
}

private const val TAG = "AiTest"

/** Most tool rounds in one test answer. */
private const val MAX_STEPS = 6

/** Name for the narrator when there is no campaign to play on; model-facing, not shown. */
private const val DEFAULT_CHARACTER = "the wanderer"

/** Without actions the tools the prompt names are missing: the model must know it cannot call them. */
private const val NO_TOOLS_NOTE = "\n## Test without tools\n\n" +
    "In this test no tools are available: narrate only, and leave anything that would need a roll unresolved.\n"
