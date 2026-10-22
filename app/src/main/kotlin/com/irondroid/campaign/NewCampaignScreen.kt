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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.data.WorldNames
import com.irondroid.data.WorldSummary

/** What the player filled in to start a campaign. [worldId] is null for a new world named [newWorldName]. */
data class NewCampaign(val title: String, val characterName: String, val worldId: Long?, val newWorldName: String)

private const val NEW_WORLD = -1L

/**
 * Starts a campaign with a title, a character name and a world: a new one or one already played
 * (continuous world, RF-PAR-04). Guided creation with truths and stats comes later (IRON-PLAN-11).
 * A world with no campaigns can be deleted here, after confirming (IRON-119).
 */
@Composable
fun NewCampaignScreen(
    worlds: List<WorldSummary>,
    onCreate: (NewCampaign) -> Unit,
    onDeleteWorld: (Long) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val worldNames = worlds.map { it.name }
    val defaultWorldName = WorldNames.suggest(stringResource(R.string.world_default_name), worldNames)
    var title by rememberSaveable { mutableStateOf("") }
    var characterName by rememberSaveable { mutableStateOf("") }
    var worldName by rememberSaveable { mutableStateOf(defaultWorldName) }
    var selectedWorld by rememberSaveable { mutableLongStateOf(NEW_WORLD) }
    var pendingDelete by remember { mutableStateOf<WorldSummary?>(null) }
    val isNewWorld = selectedWorld == NEW_WORLD
    val worldNameTaken = isNewWorld && WorldNames.isTaken(worldName, worldNames)
    val canCreate = title.isNotBlank() && characterName.isNotBlank() &&
        (!isNewWorld || (worldName.isNotBlank() && !worldNameTaken))

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
                isError = worldNameTaken,
                supportingText = if (worldNameTaken) {
                    { Text(stringResource(R.string.campaign_new_world_taken, WorldNames.suggest(worldName, worldNames))) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp),
            )
        }
        worlds.forEach { world ->
            val residents = if (world.characterNames.isEmpty()) {
                stringResource(R.string.campaign_new_world_empty)
            } else {
                world.characterNames.joinToString(", ")
            }
            WorldOption(
                stringResource(R.string.campaign_new_world_existing, world.name, residents),
                selected = selectedWorld == world.id,
                onDelete = if (world.characterNames.isEmpty()) {
                    { pendingDelete = world }
                } else {
                    null
                },
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

    pendingDelete?.let { world ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.campaign_new_world_delete_title)) },
            text = { Text(stringResource(R.string.campaign_new_world_delete_message, world.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    if (selectedWorld == world.id) selectedWorld = NEW_WORLD
                    onDeleteWorld(world.id)
                }) { Text(stringResource(R.string.campaign_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun WorldOption(label: String, selected: Boolean, onDelete: (() -> Unit)? = null, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp).weight(1f))
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.campaign_new_world_delete)) }
        }
    }
}
