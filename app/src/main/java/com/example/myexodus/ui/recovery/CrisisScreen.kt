package com.example.myexodus.ui.recovery

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.myexodus.R
import com.example.myexodus.recovery.CrisisResource
import com.example.myexodus.recovery.CrisisResources
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Need urgent help?" (V1 Req 8/16 surface, plan v3 W6): the verified contacts from
 * `assets/crisis_resources.json`, the device region's entries first. Deliberately not
 * privacy-gated and never behind a timer — the research on crisis surfaces is unambiguous that
 * this is the one screen that must open instantly and read calmly.
 */
@Composable
fun CrisisScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = remember {
        val json = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        CrisisResources.forRegion(
            CrisisResources.parse(json),
            Locale.getDefault().country,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.recovery_crisis_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.recovery_crisis_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        resources.forEach { resource -> CrisisResourceCard(resource) }
        Text(
            stringResource(R.string.recovery_crisis_not_emergency),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CrisisResourceCard(resource: CrisisResource) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(resource.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (resource.description.isNotBlank()) {
                Text(
                    resource.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (resource.phone.isNotBlank()) {
                    Button(
                        onClick = {
                            // DIAL, not CALL: the call button stays a user action (no CALL_PHONE
                            // permission, no surprise dialing).
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL, "tel:${resource.phone}".toUri()),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.recovery_crisis_call, resource.phone))
                    }
                }
                if (resource.url.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, resource.url.toUri())) }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.recovery_crisis_open))
                    }
                }
            }
            if (resource.lastVerifiedAt.isNotBlank()) {
                Text(
                    stringResource(R.string.recovery_crisis_verified, verifiedLabel(resource.lastVerifiedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "2026-09-19" → "Sep 2026"; unparseable values show as-is rather than blank. */
private fun verifiedLabel(isoDate: String): String = runCatching {
    LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))
}.getOrDefault(isoDate)

internal const val ASSET = "crisis_resources.json"
