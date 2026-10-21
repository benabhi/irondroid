package com.irondroid.campaign

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.data.dao.CampaignSummary

/** Start screen: every saved campaign, most recently played first (RF-PAR-01). */
@Composable
fun CampaignListScreen(
    campaigns: List<CampaignSummary>?,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onDelete: (Long) -> Unit,
    onOpenDicePrototype: () -> Unit,
    onOpenAiTest: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<CampaignSummary?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.campaigns_title), style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.campaigns_new))
        }
        when {
            campaigns == null -> Spacer(Modifier.weight(1f))
            campaigns.isEmpty() -> Text(
                text = stringResource(R.string.campaigns_empty),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 48.dp),
            )
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(campaigns, key = { it.id }) { campaign ->
                    CampaignRow(campaign, onOpen = { onOpen(campaign.id) }, onDelete = { pendingDelete = campaign })
                }
            }
        }
        OutlinedButton(onClick = onOpenDicePrototype, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dice_proto_open))
        }
        onOpenAiTest?.let {
            OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ai_test_open))
            }
        }
    }

    pendingDelete?.let { campaign ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.campaign_delete_title)) },
            text = { Text(stringResource(R.string.campaign_delete_message, campaign.title)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(campaign.id)
                }) { Text(stringResource(R.string.campaign_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun CampaignRow(campaign: CampaignSummary, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(campaign.title, style = MaterialTheme.typography.titleMedium)
                campaign.characterName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    text = stringResource(
                        R.string.campaign_row_details,
                        stringResource(statusLabel(campaign.status)),
                        formatDateTime(campaign.updatedAt),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.campaign_delete)) }
        }
    }
}
