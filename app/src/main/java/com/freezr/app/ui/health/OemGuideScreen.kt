package com.freezr.app.ui.health

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.freezr.app.R
import com.freezr.app.platform.oem.Oem
import com.freezr.app.platform.oem.OemGuide
import com.freezr.app.ui.components.FrostCard
import com.freezr.app.ui.components.IconBadge
import com.freezr.app.ui.components.SectionHeader

@Composable
fun OemGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val detected = remember { OemGuide.detect() }
    var oem by rememberSaveable { mutableStateOf(detected) }
    val shortcuts = remember(oem) { OemGuide.shortcuts(context, oem) }
    val steps = stringArrayResource(oem.stepsRes)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.oem_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FrostCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.PhoneAndroid, MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.oem_detected, detected.displayName), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(stringResource(R.string.oem_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Oem.entries) { o ->
                        FilterChip(o == oem, { oem = o }, { Text(o.displayName.substringBefore(" /")) })
                    }
                }
            }
            if (shortcuts.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.oem_shortcuts)) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        shortcuts.forEach { s ->
                            FilledTonalButton(
                                onClick = {
                                    try {
                                        context.startActivity(s.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } catch (_: ActivityNotFoundException) {
                                    } catch (_: SecurityException) {
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(s.labelRes), modifier = Modifier.weight(1f))
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.oem_steps)) }
            itemsIndexed(steps.toList()) { i, step ->
                FrostCard(contentPadding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge) }
                        Spacer(Modifier.width(14.dp))
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.oem_force_stop_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
