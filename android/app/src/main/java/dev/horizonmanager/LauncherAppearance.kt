package dev.horizonmanager

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal enum class LauncherAppearance(val alias: String, @DrawableRes val preview: Int, @StringRes val title: Int, val background: Color) {
    Light("LauncherLight", R.drawable.hovq_mark_black, R.string.hovq_icon_light, Color.White),
    Dark("LauncherDark", R.drawable.hovq_mark_white, R.string.hovq_icon_dark, Color.Black);

    fun component(context: Context) = ComponentName(context.packageName, "${MainActivity::class.java.name.substringBeforeLast('.')}.$alias")

    fun apply(context: Context) {
        val manager = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            manager.setComponentEnabledSettings(entries.map { item ->
                PackageManager.ComponentEnabledSetting(item.component(context),
                    if (item == this) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP)
            })
        } else {
            manager.setComponentEnabledSetting(component(context), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            val other = entries.first { it != this }
            manager.setComponentEnabledSetting(other.component(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    companion object {
        fun current(context: Context): LauncherAppearance =
            if (context.packageManager.getComponentEnabledSetting(Dark.component(context)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED) Dark else Light
    }
}

@Composable
internal fun IconSettings(selected: LauncherAppearance, change: (LauncherAppearance) -> Unit) {
    Tile {
        Text(ui(R.string.hovq_icon_detail), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LauncherAppearance.entries.forEach { item ->
                Row(Modifier.fillMaxWidth().settingsControlGlass()
                    .selectable(selected == item, role = Role.RadioButton, onClick = { change(item) })
                    .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(item.preview), null, Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(item.background))
                    Text(ui(item.title), Modifier.weight(1f), color = ReadingGlass.text)
                    RadioButton(selected == item, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = ReadingGlass.text, unselectedColor = ReadingGlass.muted))
                }
            }
        }
    }
}
