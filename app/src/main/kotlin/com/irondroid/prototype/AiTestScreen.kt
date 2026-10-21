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
import com.irondroid.ai.AiErrorKind
import com.irondroid.ai.AiException
import com.irondroid.ai.AiProvider
import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ChatRequest
import com.irondroid.ai.ReasoningEffort
import com.irondroid.ai.TokenUsage
import com.irondroid.ai.collectReply
import com.irondroid.ai.tools.ActionTools
import com.irondroid.engine.action.DispatchResult
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
 * [provider] is null when no API key was loaded. With [tools] (IRON-48) the AI can run the game's
 * actions on [campaignTitle], the last campaign played. The system prompt is a short stand-in until
 * the narrator prompt exists (IRON-51), so it is not player-facing text.
 */
@Composable
fun AiTestScreen(provider: AiProvider?, tools: ActionTools?, campaignTitle: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(provider?.info?.defaultModel?.id.orEmpty()) }
    var result by remember { mutableStateOf<AiTestResult?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var withActions by remember { mutableStateOf(false) }
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
                        result = ask(provider, model, input.trim(), tools.takeIf { withActions }) { result = it }
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
    input: String,
    tools: ActionTools?,
    onUpdate: (AiTestResult) -> Unit,
): AiTestResult {
    val started = SystemClock.elapsedRealtime()
    var current = AiTestResult()
    val conversation = mutableListOf<ChatMessage>(ChatMessage.System(if (tools == null) TEST_PROMPT else TOOLS_PROMPT), ChatMessage.User(input))
    var usage: TokenUsage? = null
    return try {
        for (step in 1..MAX_STEPS) {
            // Text from a new round of the tool loop starts a new paragraph.
            var separator = if (current.text.isEmpty()) "" else "

"
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
        current.copy(error = errorText(e.kind), errorDetail = e.message, totalMs = SystemClock.elapsedRealtime() - started)
    }
}

@StringRes
private fun errorText(kind: AiErrorKind) = when (kind) {
    AiErrorKind.AUTHENTICATION -> R.string.ai_error_authentication
    AiErrorKind.INSUFFICIENT_BALANCE -> R.string.ai_error_balance
    AiErrorKind.RATE_LIMITED -> R.string.ai_error_rate_limited
    AiErrorKind.MODEL_NOT_FOUND -> R.string.ai_error_model
    AiErrorKind.INVALID_REQUEST -> R.string.ai_error_invalid_request
    AiErrorKind.NETWORK -> R.string.ai_error_network
    AiErrorKind.SERVER -> R.string.ai_error_server
    AiErrorKind.MALFORMED_RESPONSE -> R.string.ai_error_malformed
}

private const val TAG = "AiTest"

/** Most tool rounds in one test answer. */
private const val MAX_STEPS = 6

/** Stand-in instructions for the test; the real narrator prompt is IRON-51. */
private const val TEST_PROMPT =
    "You narrate a dark fantasy solo game set in the Ironlands, in neutral Latin American Spanish, " +
        "in second person, in 2 to 4 sentences, ending by giving control back to the player."

/** Stand-in instructions to play with the actions; the real narrator prompt is IRON-51. */
private const val TOOLS_PROMPT = TEST_PROMPT + " When the player's action is risky, roll the move that fits with the tools; " +
    "never invent a roll. After the roll, narrate the outcome without numbers. If the player has to choose, " +
    "mark your suggestion with suggest_option and do not narrate the choice."
