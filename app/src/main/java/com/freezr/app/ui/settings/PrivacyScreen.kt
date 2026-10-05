package com.freezr.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freezr.app.R
import com.freezr.app.ui.components.FrostCard

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val sections = listOf(
        R.string.privacy_h_summary to R.string.privacy_b_summary,
        R.string.privacy_h_accessibility to R.string.privacy_b_accessibility,
        R.string.privacy_h_usage to R.string.privacy_b_usage,
        R.string.privacy_h_apps to R.string.privacy_b_apps,
        R.string.privacy_h_location to R.string.privacy_b_location,
        R.string.privacy_h_storage to R.string.privacy_b_storage,
        R.string.privacy_h_sharing to R.string.privacy_b_sharing,
        R.string.privacy_h_delete to R.string.privacy_b_delete,
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            sections.forEach { (h, b) ->
                item {
                    FrostCard {
                        Text(stringResource(h), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(b), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
