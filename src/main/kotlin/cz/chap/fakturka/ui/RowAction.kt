package cz.chap.fakturka.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cz.chap.fakturka.i18n.Messages

/** Document icon (Material "Description") used for PDF actions; not part of material-icons-core. */
val PdfIcon: ImageVector by lazy {
    materialIcon(name = "Fakturka.Pdf") {
        materialPath {
            moveTo(14f, 2f); horizontalLineTo(6f)
            curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
            lineTo(4f, 20f)
            curveToRelative(0f, 1.1f, 0.89f, 2f, 1.99f, 2f)
            horizontalLineTo(18f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(8f); lineToRelative(-6f, -6f); close()
            moveTo(16f, 18f); horizontalLineTo(8f); verticalLineToRelative(-2f); horizontalLineToRelative(8f); verticalLineToRelative(2f); close()
            moveTo(16f, 14f); horizontalLineTo(8f); verticalLineToRelative(-2f); horizontalLineToRelative(8f); verticalLineToRelative(2f); close()
            moveTo(13f, 9f); verticalLineTo(3.5f); lineTo(18.5f, 9f); horizontalLineTo(13f); close()
        }
    }
}

/** Bar chart icon (Material "BarChart") for the statistics menu entry; not part of material-icons-core. */
val ChartIcon: ImageVector by lazy {
    materialIcon(name = "Fakturka.Chart") {
        materialPath {
            moveTo(5f, 9.2f); horizontalLineToRelative(3f); verticalLineTo(19f); horizontalLineTo(5f); close()
            moveTo(10.6f, 5f); horizontalLineToRelative(2.8f); verticalLineToRelative(14f); horizontalLineToRelative(-2.8f); close()
            moveTo(16.2f, 13f); horizontalLineTo(19f); verticalLineToRelative(6f); horizontalLineToRelative(-2.8f); close()
        }
    }
}

/** Icon button for actions inside list rows; the text from [labelKey] shows as a tooltip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowAction(
    icon: ImageVector,
    labelKey: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    val label = Messages.get(labelKey)
    TooltipArea(
        tooltip = {
            Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = MaterialTheme.shapes.extraSmall) {
                Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        },
        delayMillis = 400,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp), tint = if (enabled) tint else LocalContentColor.current)
            }
        }
    }
}

@Composable fun EditAction(onClick: () -> Unit) = RowAction(Icons.Outlined.Edit, "ui.common.edit", onClick)

@Composable fun DeleteAction(onClick: () -> Unit, enabled: Boolean = true) = RowAction(Icons.Outlined.Delete, "ui.common.delete", onClick, enabled)
