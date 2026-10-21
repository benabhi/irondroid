package com.irondroid.campaign

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.data.entity.WorldEntity

/** What the player filled in to start a campaign. [worldId] is null for a new world named [newWorldName]. */
data class NewCampaign(val title: String, val characterName: String, val worldId: Long?, val newWorldName: String)

private const val NEW_WORLD = -1L

/**
 * Starts a campaign with a title, a character name and a world: a new one or one already played
 * (continuous world, RF-PAR-04). Guided creation with truths and stats comes later (IRON-PLAN-11).
 */
@Composable
fun NewCampaignScreen(
    worlds: List<WorldEntity>,
    onCreate: (NewCampaign) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaultWorldName = stringResource(R.string.world_default_name)
    var title by rememberSaveable { mutableStateOf("") }
    var characterName by rememberSaveable { mutableStateOf("") }
    var worldName by rememberSaveable { mutableStateOf(defaultWorldName) }
    var selectedWorld by rememberSaveable { mutableLongStateOf(NEW_WORLD) }
    val isNewWorld = selectedWorld == NEW_WORLD
    val canCreate = title.isNotBlank() && characterName.isNotBlank() && (!isNewWorld || worldName.isNotBlank())

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.campaign_new_title), style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.campaign_new_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = characterName,
            onValueChange = { characterName = it },
            label = { Text(stringResource(R.string.campaign_new_character)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.campaign_new_world), style = MaterialTheme.typography.titleMedium)
        WorldOption(stringResource(R.string.campaign_new_world_new), selected = isNewWorld) { selectedWorld = NEW_WORLD }
        if (isNewWorld) {
            OutlinedTextField(
                value = worldName,
                onValueChange = { worldName = it },
                label = { Text(stringResource(R.string.campaign_new_world_name)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp),
            )
        }
        worlds.forEach { world ->
            WorldOption(
                stringResource(R.string.campaign_new_world_existing, world.name),
                selected = selectedWorld == world.id,
            ) { selectedWorld = world.id }
        }
        Text(stringResource(R.string.campaign_new_pending_sheet), style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                onCreate(
                    NewCampaign(
                        title = title.trim(),
                        characterName = characterName.trim(),
                        worldId = selectedWorld.takeUnless { it == NEW_WORLD },
                        newWorldName = worldName.trim(),
                    ),
                )
            },
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.campaign_new_create)) }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_cancel))
        }
    }
}

@Composable
private fun WorldOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}
