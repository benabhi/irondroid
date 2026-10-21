package com.irondroid.campaign

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.data.SavedCharacter
import com.irondroid.data.entity.CampaignEntity
import com.irondroid.data.entity.WorldEntity
import com.irondroid.engine.character.Stat

/** Everything the campaign screen shows, loaded from the database. */
data class CampaignDetails(val campaign: CampaignEntity, val world: WorldEntity?, val character: SavedCharacter?)

/** Temporary campaign screen with the saved data, until the game screen exists. */
@Composable
fun CampaignScreen(details: CampaignDetails?, onBack: () -> Unit, modifier: Modifier = Modifier) {
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
            Text(
                stringResource(R.string.campaign_game_soon),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_back))
        }
    }
}
