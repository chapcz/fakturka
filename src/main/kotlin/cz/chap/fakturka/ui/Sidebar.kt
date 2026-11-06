package cz.chap.fakturka.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cz.chap.fakturka.i18n.Messages

enum class Screen(val labelKey: String, val icon: ImageVector) {
    ISSUED("ui.nav.issued", Icons.Outlined.Send),
    RECEIVED("ui.nav.received", Icons.Outlined.MailOutline),
    CONTACTS("ui.nav.contacts", Icons.Outlined.Person),
    TEMPLATES("ui.nav.templates", Icons.AutoMirrored.Outlined.List),
    VAT("ui.nav.vat", Icons.Outlined.DateRange),
    STATS("ui.nav.stats", ChartIcon),
    SETTINGS("ui.nav.settings", Icons.Outlined.Settings),
}

@Composable
fun Sidebar(current: Screen, enabled: Boolean, onSelect: (Screen) -> Unit) {
    PermanentDrawerSheet(Modifier.width(200.dp).fillMaxHeight(), drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(
            Messages.get("app.title"),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 16.dp),
        )
        Screen.entries.forEach { screen ->
            // Until the settings are saved only the settings screen is usable.
            val usable = enabled || screen == Screen.SETTINGS
            if (screen == Screen.SETTINGS) Spacer(Modifier.height(12.dp))
            NavigationDrawerItem(
                label = { Text(Messages.get(screen.labelKey), style = MaterialTheme.typography.labelLarge) },
                icon = { Icon(screen.icon, contentDescription = null) },
                selected = screen == current,
                onClick = { if (usable) onSelect(screen) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).height(44.dp).alpha(if (usable) 1f else 0.38f),
            )
        }
    }
}
