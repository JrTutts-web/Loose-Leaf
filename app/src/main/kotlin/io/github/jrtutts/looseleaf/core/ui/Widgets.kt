package io.github.jrtutts.looseleaf.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.jrtutts.looseleaf.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Small building blocks that modules share for their option rows. */

class NamedColor(val argb: Int, @StringRes val name: Int)

/** Colours for things drawn or written on a page; shared so every module offers the same ones. */
object Palette {
    val ink = listOf(
        NamedColor(0xFF111111.toInt(), R.string.color_black), NamedColor(0xFF1746A2.toInt(), R.string.color_blue),
        NamedColor(0xFFC62828.toInt(), R.string.color_red), NamedColor(0xFF2E7D32.toInt(), R.string.color_green),
    )
}

@Composable
fun ColorDots(colors: List<NamedColor>, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (c in colors) {
            val isSelected = c.argb == selected
            val name = stringResource(c.name)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onPick(c.argb) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (isSelected) 30.dp else 24.dp)
                        .background(Color(c.argb), CircleShape)
                        .border(
                            if (isSelected) 3.dp else 1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ),
                )
            }
        }
    }
}

/** "− 12 +" */
@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, minusLabel: String, plusLabel: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMinus) { Icon(Icons.Filled.Remove, minusLabel) }
        Text(value, style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = onPlus) { Icon(Icons.Filled.Add, plusLabel) }
    }
}

@Composable
fun OptionHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One of a row of icon choices (shape kinds, stamp kinds). */
@Composable
fun IconChoice(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
    }
}

@Composable
fun Gap(width: Int = 8) = Spacer(Modifier.width(width.dp))
