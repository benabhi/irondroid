package com.irondroid.campaign

import androidx.annotation.StringRes
import com.irondroid.R
import com.irondroid.data.entity.CampaignStatus
import java.text.DateFormat
import java.util.Date

@StringRes
fun statusLabel(status: CampaignStatus): Int = when (status) {
    CampaignStatus.ACTIVE -> R.string.campaign_status_active
    CampaignStatus.DEAD -> R.string.campaign_status_dead
    CampaignStatus.DESOLATE -> R.string.campaign_status_desolate
    CampaignStatus.RETIRED -> R.string.campaign_status_retired
}

/** Short date and time in the phone's locale. */
fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
