package com.feldman.ha.ui.cards

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

interface GenericCardScope {
    fun addPickerRow(
        label: String,
        items: List<PickerItem>,
        selected: String,
        onPick: (String) -> Unit,
        defaultRemoved: Boolean = false
    )
    fun addSliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit)
    fun addCounterRow(
        label: String,
        count: () -> Int,
        incIcon: Painter,   // ← your icon (e.g. painterResource(R.drawable.ic_plus))
        decIcon: Painter,   // ← your icon (e.g. painterResource(R.drawable.ic_minus))
        onInc: () -> Unit,
        onDec: () -> Unit
    )
    fun addGradientSliderRow(
        value: Float,
        onValueChange: (Float) -> Unit,
        onValueChangeFinished: () -> Unit,
        range: ClosedFloatingPointRange<Float>,
        gradient: List<Color>,
        height: Dp = 40.dp,
        label: String
    )

    fun addTextSetting(label: String, value: () -> String, onChange: (String) -> Unit)
    fun addSliderSetting(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit)
    fun addSwitchSetting(label: String, checked: Boolean, onToggle: (Boolean) -> Unit)
    fun addButtonSetting(label: String, onClick: () -> Unit)
    fun addCustomSetting(content: @Composable ColumnScope.() -> Unit)
    fun addRadioPickerSetting(
        label: String,
        options: List<String>,
        selected: String,
        onSelect: (String) -> Unit
    )

    fun addCheckboxSetting(
        label: String,
        options: List<String>,
        selected: Set<String>,
        max: Int = Int.MAX_VALUE,
        onChange: (Set<String>) -> Unit
    )
    fun addCustomRow(key: String, content: @Composable ColumnScope.() -> Unit)

}


class GenericCardScopeImpl : GenericCardScope {
    val blocks = mutableListOf<@Composable ColumnScope.() -> Unit>()
    val settings = mutableListOf<@Composable ColumnScope.() -> Unit>()
    val rowOrder = mutableListOf<String>()
    val rowMap = mutableMapOf<String, @Composable ColumnScope.() -> Unit>()
    // Keys declared during the current build() pass. Used by GenericCard to reconcile
    // rowOrder/rowMap. When build() is skipped by Compose (inputs unchanged) this stays
    // empty, signalling that the previous rows should be kept rather than cleared.
    val touchedKeys = mutableListOf<String>()
    var orderSettingAdded = false


    override fun addCustomRow(key: String, content: @Composable ColumnScope.() -> Unit) {
        addOrderedRow(key, content)
    }

    private fun addOrderedRow(key: String, content: @Composable ColumnScope.() -> Unit) {
        if (key.isNotBlank()) {
            if (!rowOrder.contains(key)) rowOrder.add(key)
            if (!touchedKeys.contains(key)) touchedKeys.add(key)
        }
        rowMap[key] = content
    }


    override fun addPickerRow(
        label: String,
        items: List<PickerItem>,
        selected: String,
        onPick: (String) -> Unit,
        defaultRemoved: Boolean
    ) {
        val key = "Picker: $label"

        if (key.isNotBlank() && !rowOrder.contains(key)) {
            if (!defaultRemoved) {
                rowOrder.add(key)
            }
        }

        addOrderedRow(key) {
            val colorScheme = MaterialTheme.colorScheme
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
                    .padding(4.dp)
                    .height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val sel = item.value == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(if (sel) colorScheme.primaryContainer else Color.Transparent)
                            .clickable(enabled = !sel) { onPick(item.value) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = item.icon,
                            contentDescription = item.value,
                            tint = if (sel) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }


    override fun addSliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
        val trackHeight = 36.dp

        addOrderedRow("Slider") {
            val colorScheme = MaterialTheme.colorScheme
            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
            ) {
                Slider(
                    value = value,
                    onValueChange = onChange,
                    valueRange = range,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackHeight),
                    colors = SliderDefaults.colors(
                        thumbColor = contentColorFor(cardButtonBackgroundColor()),
                    ),

                    )
            }
        }

    }


    override fun addCounterRow(
        label: String,
        count: () -> Int,
        incIcon: Painter,
        decIcon: Painter,
        onInc: () -> Unit,
        onDec: () -> Unit) {
        addOrderedRow("Counter $label") {
            val colorScheme = MaterialTheme.colorScheme
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDec, modifier = Modifier
                    .size(48.dp)
                    .weight(1f)) {
                    Icon(decIcon, contentDescription = "dec", tint = colorScheme.primary, modifier = Modifier.size(24.dp))
                }
                Text(
                    text = "${count()}°C",              // ← live value every recomposition
                    fontSize = 20.sp,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                IconButton(onClick = onInc, modifier = Modifier
                    .size(48.dp)
                    .weight(1f)) {
                    Icon(incIcon, contentDescription = "inc", tint = colorScheme.primary, modifier = Modifier.size(24.dp))
                }
            }
        }
    }


    override fun addGradientSliderRow(
        value: Float,
        onValueChange: (Float) -> Unit,
        onValueChangeFinished: () -> Unit,
        range: ClosedFloatingPointRange<Float>,
        gradient: List<Color>,
        height: Dp,
        label: String
    ) {
        addOrderedRow("Gradient Slider: $label") {
            val colorScheme = MaterialTheme.colorScheme
            // Thumb sits on the gradient itself, so contrast against its middle stop
            // rather than assuming a dark backdrop.
            val buttonBg = cardButtonBackgroundColor()
            val thumb = contentColorFor(gradient.getOrNull(gradient.size / 2) ?: buttonBg)
            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(height))
                    .background(Brush.horizontalGradient(gradient)),
                contentAlignment = Alignment.Center
            ) {
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    onValueChangeFinished = onValueChangeFinished,
                    valueRange = range,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height), // ensures proper vertical centering
                    colors = SliderDefaults.colors(
                        thumbColor = thumb,
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent
                    )
                )
            }

        }
    }





    override fun addTextSetting(label: String, value: () -> String, onChange: (String) -> Unit) {
        settings += {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = value(),
                onValueChange = onChange,
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }


    override fun addSliderSetting(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
        settings += {
            val colorScheme = MaterialTheme.colorScheme
            Spacer(Modifier.height(16.dp))

            Text(label, color = colorScheme.onSurface)
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    override fun addSwitchSetting(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        settings += {
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(!checked) }
            ) {
                Switch(checked = checked, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
    }

    override fun addButtonSetting(label: String, onClick: () -> Unit) {
        settings += {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Text(label)
            }
        }
    }

    override fun addCustomSetting(content: @Composable ColumnScope.() -> Unit) {
        settings += content
    }

    override fun addRadioPickerSetting(
        label: String,
        options: List<String>,
        selected: String,
        onSelect: (String) -> Unit
    ) {
        settings += {
            val colorScheme = MaterialTheme.colorScheme
            Spacer(Modifier.height(16.dp))

            // ✅ this is now inside a @Composable lambda, so `by remember` works
            var expanded by remember { mutableStateOf(false) }

            Column {
                Text(label, color = colorScheme.onSurface)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colorScheme.surfaceContainerHighest)
                        .clickable { expanded = true }
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Text(selected, color = colorScheme.onSurface)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                expanded = false
                                onSelect(option)
                            }
                        )
                    }
                }
            }

        }
    }


    override fun addCheckboxSetting(
        label: String,
        options: List<String>,
        selected: Set<String>,
        max: Int,
        onChange: (Set<String>) -> Unit
    ) {
        settings += {
            val colorScheme = MaterialTheme.colorScheme
            Spacer(Modifier.height(16.dp))
            val selectedSet = remember { mutableStateSetOf(*selected.toTypedArray()) }

            Column {
                Text(label, color = colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))

                options.forEach { option ->
                    val checked = option in selectedSet
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (checked) {
                                    selectedSet.remove(option)
                                } else if (selectedSet.size < max) {
                                    selectedSet.add(option)
                                }
                                onChange(selectedSet.toSet())
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(option, color = colorScheme.onSurface)
                    }
                }
            }

        }
    }


    @Composable
    fun SettingsDialog(
        onDismiss: () -> Unit,
        onSave: () -> Unit,
        title: String = "Card settings"
    ) {
        val scrollState = rememberScrollState()
        val keyboardController = LocalSoftwareKeyboardController.current
        var keyboardVisible by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            snapshotFlow { keyboardController != null }
                .collect { keyboardVisible = it }
        }

        BackHandler(enabled = true) {
            if (keyboardVisible) {
                keyboardController?.hide()
            } else {
                onDismiss()
            }
        }

        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = onSave) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
            title = { Text(title) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(top = 8.dp)
                ) {
                    settings.forEach { setting ->
                        key(setting.hashCode()) {
                            setting()
                        }
                    }
                }
            },
            properties = DialogProperties(
                dismissOnClickOutside = false,
                dismissOnBackPress = false
            )
        )
    }


}

