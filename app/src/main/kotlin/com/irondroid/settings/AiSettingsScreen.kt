package com.irondroid.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.ai.AiErrorKind
import com.irondroid.ai.ConnectionResult
import com.irondroid.ai.ProviderInfo
import com.irondroid.ai.ReasoningEffort
import com.irondroid.theme.ThemeMode
import com.irondroid.theme.TextSize
import kotlinx.coroutines.launch

/** What the settings screen needs from the app, so the screen holds no storage or network code. */
interface AiSettingsActions {
    val providers: List<ProviderInfo>
    var provider: ProviderInfo
    fun model(provider: ProviderInfo): String
    fun setModel(provider: ProviderInfo, model: String)

    /** The saved key of [provider] masked to its last characters, or null when there is none. */
    fun maskedKey(provider: ProviderInfo): String?
    fun saveKey(provider: ProviderInfo, key: String)
    fun deleteKey(provider: ProviderInfo)

    /** Checks [provider] with its saved key and chosen model (RF-IA-05). */
    suspend fun checkConnection(provider: ProviderInfo): ConnectionResult

    /** Whether the narrator's moves wait for the player before the dice (RF-ENV-04). */
    var confirmMoves: Boolean

    /** How hard the narrator thinks (IRON-109). */
    var narratorEffort: ReasoningEffort

    /** Whether each turn shows what it cost (IRON-111). */
    var showMetrics: Boolean

    /** Light, dark or as the phone (RF-UI-04). */
    var themeMode: ThemeMode

    /** Whether changes show without movement (IRON-68). */
    var reduceMotion: Boolean

    /** How large the text is (IRON-69). */
    var textSize: TextSize
}

/** AI settings (RF-IA-03, RF-IA-05): provider, model, API key and a connection check. */
@Composable
fun AiSettingsScreen(actions: AiSettingsActions, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(actions.provider) }
    var model by remember(provider) { mutableStateOf(actions.model(provider)) }
    var masked by remember(provider) { mutableStateOf(actions.maskedKey(provider)) }
    var keyInput by remember(provider) { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var check by remember(provider, model, masked) { mutableStateOf<ConnectionResult?>(null) }

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
            TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        }

        SectionTitle(R.string.settings_play_title)
        var confirmMoves by remember { mutableStateOf(actions.confirmMoves) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_confirm_moves), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_confirm_moves_note), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = confirmMoves, onCheckedChange = {
                actions.confirmMoves = it
                confirmMoves = it
            })
        }

        SectionTitle(R.string.settings_ai_section)

        Text(stringResource(R.string.settings_ai_provider), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.providers.forEach { option ->
                FilterChip(
                    selected = option.id == provider.id,
                    onClick = {
                        actions.provider = option
                        provider = option
                    },
                    label = { Text(option.name) },
                )
            }
        }

        Text(stringResource(R.string.settings_ai_model), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            provider.models.forEach { option ->
                FilterChip(
                    selected = option.id == model,
                    onClick = {
                        actions.setModel(provider, option.id)
                        model = option.id
                    },
                    label = { Text(option.id) },
                )
            }
        }

        if (provider.needsApiKey) {
            Text(stringResource(R.string.settings_ai_key), style = MaterialTheme.typography.titleMedium)
            Text(
                masked?.let { stringResource(R.string.settings_ai_key_saved, it) } ?: stringResource(R.string.settings_ai_key_missing),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(stringResource(if (masked == null) R.string.settings_ai_key_paste else R.string.settings_ai_key_replace)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = keyInput.isNotBlank(), onClick = {
                    actions.saveKey(provider, keyInput)
                    keyInput = ""
                    masked = actions.maskedKey(provider)
                }) { Text(stringResource(R.string.settings_ai_key_save)) }
                OutlinedButton(enabled = masked != null, onClick = {
                    actions.deleteKey(provider)
                    masked = null
                }) { Text(stringResource(R.string.settings_ai_key_delete)) }
            }
            Text(stringResource(R.string.settings_ai_key_note), style = MaterialTheme.typography.bodySmall)
        }

        Button(
            enabled = !checking && (masked != null || !provider.needsApiKey),
            onClick = {
                checking = true
                check = null
                scope.launch {
                    check = actions.checkConnection(provider)
                    checking = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (checking) R.string.settings_ai_checking else R.string.settings_ai_check)) }

        check?.let { result ->
            val (text, ok) = when (result) {
                is ConnectionResult.Ready -> R.string.settings_ai_check_ready to true
                is ConnectionResult.NoTools -> R.string.settings_ai_check_no_tools to false
                is ConnectionResult.Failed -> aiErrorText(result.error.kind) to false
            }
            Text(
                stringResource(text),
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(stringResource(R.string.settings_ai_check_time, result.millis), style = MaterialTheme.typography.bodySmall)
        }


        var effort by remember { mutableStateOf(actions.narratorEffort) }
        Text(stringResource(R.string.settings_effort), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ReasoningEffort.LOW to R.string.settings_effort_low,
                ReasoningEffort.HIGH to R.string.settings_effort_high,
                ReasoningEffort.MAX to R.string.settings_effort_max,
            ).forEach { (option, label) ->
                FilterChip(selected = effort == option, onClick = {
                    actions.narratorEffort = option
                    effort = option
                }, label = { Text(stringResource(label)) })
            }
        }
        Text(stringResource(R.string.settings_effort_note), style = MaterialTheme.typography.bodySmall)

        var showMetrics by remember { mutableStateOf(actions.showMetrics) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_metrics), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_metrics_note), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = showMetrics, onCheckedChange = {
                actions.showMetrics = it
                showMetrics = it
            })
        }

        SectionTitle(R.string.settings_look_title)
        var themeMode by remember { mutableStateOf(actions.themeMode) }
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ThemeMode.LIGHT to R.string.settings_theme_light,
                ThemeMode.DARK to R.string.settings_theme_dark,
                ThemeMode.SYSTEM to R.string.settings_theme_system,
            ).forEach { (option, label) ->
                FilterChip(selected = themeMode == option, onClick = {
                    actions.themeMode = option
                    themeMode = option
                }, label = { Text(stringResource(label)) })
            }
        }

        var reduceMotion by remember { mutableStateOf(actions.reduceMotion) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_reduce_motion), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_reduce_motion_note), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = reduceMotion, onCheckedChange = {
                actions.reduceMotion = it
                reduceMotion = it
            })
        }

        var textSize by remember { mutableStateOf(actions.textSize) }
        Text(stringResource(R.string.settings_text_size), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                TextSize.SMALL to R.string.settings_text_small,
                TextSize.NORMAL to R.string.settings_text_normal,
                TextSize.LARGE to R.string.settings_text_large,
                TextSize.HUGE to R.string.settings_text_huge,
            ).forEach { (option, label) ->
                FilterChip(selected = textSize == option, onClick = {
                    actions.textSize = option
                    textSize = option
                }, label = { Text(stringResource(label)) })
            }
        }
        Text(stringResource(R.string.settings_text_size_note), style = MaterialTheme.typography.bodySmall)
    }
}

/** The title of a group of settings (IRON-69), with a thin line above it. */
@Composable
private fun SectionTitle(@StringRes label: Int) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 16.dp))
    Text(stringResource(label), style = MaterialTheme.typography.titleLarge)
}

/** What to tell the player when the AI fails with [kind]. */
@StringRes
fun aiErrorText(kind: AiErrorKind) = when (kind) {
    AiErrorKind.AUTHENTICATION -> R.string.ai_error_authentication
    AiErrorKind.INSUFFICIENT_BALANCE -> R.string.ai_error_balance
    AiErrorKind.RATE_LIMITED -> R.string.ai_error_rate_limited
    AiErrorKind.MODEL_NOT_FOUND -> R.string.ai_error_model
    AiErrorKind.INVALID_REQUEST -> R.string.ai_error_invalid_request
    AiErrorKind.NETWORK -> R.string.ai_error_network
    AiErrorKind.SERVER -> R.string.ai_error_server
    AiErrorKind.MALFORMED_RESPONSE -> R.string.ai_error_malformed
}
