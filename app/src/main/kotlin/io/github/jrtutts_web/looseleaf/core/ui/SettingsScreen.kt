package io.github.jrtutts_web.looseleaf.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.jrtutts_web.looseleaf.BuildConfig
import io.github.jrtutts_web.looseleaf.R
import io.github.jrtutts_web.looseleaf.core.module.EditorModule
import io.github.jrtutts_web.looseleaf.core.settings.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: Settings, modules: List<EditorModule>, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.settings_modules))
            Text(
                stringResource(R.string.settings_modules_hint),
                Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (m in modules) {
                val on = settings.isEnabled(m)
                ListItem(
                    modifier = Modifier.toggleable(on, role = Role.Switch) { settings.setEnabled(m, it) },
                    leadingContent = { Icon(m.icon, null) },
                    headlineContent = { Text(stringResource(m.title)) },
                    supportingContent = { Text(stringResource(m.description)) },
                    trailingContent = { Switch(checked = on, onCheckedChange = null) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle(stringResource(R.string.settings_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME) },
                supportingContent = { Text(stringResource(R.string.about_text)) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
