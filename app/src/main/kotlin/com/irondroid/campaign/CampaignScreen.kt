package com.irondroid.campaign

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.data.SavedCharacter
import com.irondroid.data.entity.CharacterGender
import com.irondroid.data.entity.CampaignEntity
import com.irondroid.data.entity.WorldEntity
import com.irondroid.engine.character.Stat

/** Everything the campaign screen shows, loaded from the database. */
data class CampaignDetails(val campaign: CampaignEntity, val world: WorldEntity?, val character: SavedCharacter?)

/** The campaign and its character sheet, opened from the play screen until the full sheet exists (Interfaz plan). */
@Composable
fun CampaignScreen(
    details: CampaignDetails?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** How the narrator refers to the character (IRON-56), with [onGender] to change it; hidden when null is passed for both. */
    gender: CharacterGender? = null,
    onGender: ((CharacterGender) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (details != null) {
            val (campaign, world, character) = details
            Text(campaign.title, style = MaterialTheme.typography.headlineMedium)
            world?.let { Text(stringResource(R.string.campaign_world, it.name)) }
            Text(stringResource(R.string.campaign_status, stringResource(statusLabel(campaign.status))))
            Text(stringResource(R.string.campaign_created, formatDateTime(campaign.createdAt)))
            Text(stringResource(R.string.campaign_updated, formatDateTime(campaign.updatedAt)))
            character?.let { saved ->
                val sheet = saved.sheet
                Text(saved.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                onGender?.let { change ->
                    Text(stringResource(R.string.campaign_gender), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            CharacterGender.FEMININE to R.string.gender_feminine,
                            CharacterGender.MASCULINE to R.string.gender_masculine,
                            CharacterGender.NEUTRAL to R.string.gender_neutral,
                        ).forEach { (option, label) ->
                            // No choice yet reads as neutral, which is how the narrator treats it.
                            FilterChip(selected = (gender ?: CharacterGender.NEUTRAL) == option, onClick = { change(option) }, label = { Text(stringResource(label)) })
                        }
                    }
                }
                if (sheet.stats.values.all { it == 0 }) {
                    Text(stringResource(R.string.campaign_sheet_pending))
                } else {
                    Text(
                        stringResource(
                            R.string.campaign_stats,
                            sheet.stats.getValue(Stat.EDGE),
                            sheet.stats.getValue(Stat.HEART),
                            sheet.stats.getValue(Stat.IRON),
                            sheet.stats.getValue(Stat.SHADOW),
                            sheet.stats.getValue(Stat.WITS),
                        ),
                    )
                }
                Text(
                    stringResource(
                        R.string.campaign_meters,
                        sheet.meters.health.value,
                        sheet.meters.spirit.value,
                        sheet.meters.supply.value,
                        sheet.momentum.value,
                    ),
                )
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_back))
        }
    }
}
