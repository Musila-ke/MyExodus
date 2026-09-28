package com.example.myexodus.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.domainusage.Counter
import com.example.myexodus.domainusage.DomainUsageEngine
import com.example.myexodus.domainusage.DomainUsageSettings
import com.example.myexodus.domainusage.EngineMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * §14 "in-app" leg, debug builds only (the caller gates on `BuildConfig.DEBUG`): every counter
 * plus the current [com.example.myexodus.domainusage.EngineStatus]. Counters are polled, not
 * pushed — the engine has no counter flow by design; the debug surface pays the polling cost.
 * Values are selectable so a device run can be copied out without a logcat scrape.
 */
@Composable
internal fun DomainUsageDebugSection(engine: DomainUsageEngine) {
    var counters by remember { mutableStateOf(engine.counters.snapshot()) }
    val status by engine.status.collectAsStateWithLifecycle()

    LaunchedEffect(engine) {
        while (true) {
            counters = engine.counters.snapshot()
            delay(2_000)
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Domain usage (debug)", style = MaterialTheme.typography.titleMedium)
        Text(
            "mode=${engine.mode}  status=$status",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(null, EngineMode.OFF, EngineMode.ACCOUNT, EngineMode.ENFORCE).forEach { mode ->
                TextButton(onClick = { scope.launch { DomainUsageSettings.setModeOverride(context, mode) } }) {
                    Text(mode?.name ?: "clear")
                }
            }
        }
        Text(
            "Mode override applies at the next VPN start.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Counter.entries.forEach { counter ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        counter.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    SelectionContainer {
                        Text(
                            (counters[counter] ?: 0L).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
