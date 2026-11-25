package dev.horizonmanager

import android.icu.text.TimeZoneNames
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

private val weekdays = listOf(R.string.ui_mon, R.string.ui_tue, R.string.ui_wed, R.string.ui_thu,
    R.string.ui_fri, R.string.ui_sat, R.string.ui_sun)
private val modes = listOf("off" to R.string.schedule_manual, "calendar" to R.string.ui_by_day_of_the_week,
    "interval" to R.string.ui_at_regular_intervals)
private val dayPresets = listOf(127 to R.string.schedule_every_day, 31 to R.string.schedule_workdays,
    96 to R.string.schedule_weekends)

private fun JSONObject.scheduleDayMask(): Int = optJSONArray("days")?.let { array ->
    (0 until array.length()).map { array.optInt(it, -1) }
        .filter { it in weekdays.indices }.fold(0) { mask, day -> mask or (1 shl day) }
} ?: 127

private fun targetTime(hour: Int, minute: Int): String =
    LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("HH:mm", appLocale()))

@Composable
private fun ScheduleDayChip(title: Int, selected: Boolean, enabled: Boolean, wash: State<Float>, select: () -> Unit) {
    val shape = feedGlassShape
    val surface = if (selected) Modifier.glassSurface(shape, outlined = false).savedNoticeWash(phase = wash)
        else Modifier.settingsControlGlass(enabled)
    Box(Modifier.heightIn(min = 48.dp).selectable(
        selected = selected, onClick = select, enabled = enabled, role = Role.Checkbox,
    ), contentAlignment = Alignment.Center) {
        Text(ui(title), surface.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelLarge,
            color = when {
                selected && enabled -> Color.White
                selected -> Color.White.copy(alpha = .74f)
                enabled -> ReadingGlass.text
                else -> ReadingGlass.muted
            })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleForm(panel: Panel, model: ManagerModel, dirtyChange: (Boolean) -> Unit) {
    val spec = panel.schedule.optJSONObject("spec")
    LaunchedEffect(panel.server?.id) { model.refreshSchedule() }
    if (spec == null) {
        Tile {
            Text(ui(R.string.schedule_unavailable))
            TextButton(enabled = !panel.busy, onClick = { model.refreshSchedule(quiet = false) }) {
                Text(ui(R.string.ui_retry))
            }
        }
        return
    }
    val saved = spec.toString()
    val savedMode = spec.optString("mode", "off")
    val savedHours = spec.optInt("hours", 6)
    val supportsIntervalStart = spec.has("interval_start")
    val savedStart = spec.optString("interval_start", "").takeUnless { spec.isNull("interval_start") }.orEmpty()
    val savedHour = spec.optInt("hour", 8)
    val savedMinute = spec.optInt("minute")
    val savedDays = spec.scheduleDayMask()
    val savedZone = spec.optString("zone", ZoneId.systemDefault().id)
    var mode by rememberSaveable(saved) { mutableStateOf(savedMode) }
    var hours by rememberSaveable(saved) { mutableStateOf(savedHours.toString()) }
    var intervalStart by rememberSaveable(saved) { mutableStateOf(savedStart) }
    var intervalZone by rememberSaveable(saved) {
        mutableStateOf(if (savedStart.isBlank()) ZoneId.systemDefault().id else savedZone)
    }
    var hour by rememberSaveable(saved) { mutableIntStateOf(savedHour) }
    var minute by rememberSaveable(saved) { mutableIntStateOf(savedMinute) }
    var days by rememberSaveable(saved) { mutableIntStateOf(savedDays) }
    var zone by rememberSaveable(saved) { mutableStateOf(savedZone) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var pickZone by rememberSaveable { mutableStateOf(false) }
    val start = intervalStart.split(':').takeIf { it.size == 2 }
    val startHour = start?.get(0)?.toIntOrNull() ?: 8
    val startMinute = start?.get(1)?.toIntOrNull() ?: 0
    val parsedHours = hours.toIntOrNull()
    val changed = mode != savedMode || when (mode) {
        "interval" -> parsedHours != savedHours || intervalStart != savedStart ||
            (intervalStart.isNotBlank() && intervalZone != savedZone)
        "calendar" -> hour != savedHour || minute != savedMinute || days != savedDays || zone != savedZone
        else -> false
    }
    SideEffect { dirtyChange(changed) }
    val palette = LocalHorizonPalette.current
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = palette.tint.copy(alpha = 0.17f),
        selectedLabelColor = palette.link,
    )
    val next = panel.schedule.optDouble("next")
    val nextRun = if (next.isFinite()) runCatching {
        val timeZone = if (savedMode == "calendar" || savedMode == "interval" && savedStart.isNotBlank()) ZoneId.of(savedZone) else ZoneId.systemDefault()
        Instant.ofEpochSecond(next.toLong()).atZone(timeZone)
    }.getOrNull() else null

    Tile(accented = true) {
        Text(ui(if (panel.connectionProblem.isBlank()) R.string.schedule_next else R.string.schedule_last_known),
            style = MaterialTheme.typography.labelLarge, color = palette.muted)
        val nextTime = nextRun?.format(DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm", appLocale()))
        Text(nextTime ?: ui(if (savedMode == "off") R.string.schedule_manual else R.string.schedule_unavailable),
            style = MaterialTheme.typography.titleMedium)
        if (nextRun != null) Text(zoneLabel(nextRun.zone.id, at = nextRun.toInstant()), color = palette.muted,
            style = MaterialTheme.typography.bodySmall)
        Text(ui(R.string.schedule_vps_hint), style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
    Tile {
        SettingHeading(ui(R.string.schedule_frequency), R.string.setting_help_schedule_modes)
        val radioColors = RadioButtonDefaults.colors(selectedColor = ReadingGlass.text,
            unselectedColor = ReadingGlass.muted, disabledSelectedColor = ReadingGlass.muted,
            disabledUnselectedColor = ReadingGlass.muted)
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            for ((value, title) in modes) {
                Row(Modifier.fillMaxWidth().settingsControlGlass(!panel.busy).heightIn(min = 48.dp).selectable(
                    selected = mode == value, enabled = !panel.busy, role = Role.RadioButton, onClick = { mode = value },
                ).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == value, onClick = null, enabled = !panel.busy, colors = radioColors)
                    Text(ui(title), Modifier.padding(start = 12.dp), color = ReadingGlass.text)
                }
            }
        }
        when (mode) {
            "interval" -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (preset in listOf(3, 6, 12, 24)) {
                        FilterChip(parsedHours == preset, { hours = preset.toString() },
                            { Text(ui(R.string.schedule_hours, preset)) }, enabled = !panel.busy, colors = chipColors)
                    }
                }
                OutlinedTextField(hours, { value -> hours = value.filter { it in '0'..'9' }.take(3) },
                    modifier = Modifier.fillMaxWidth(), label = { Text(ui(R.string.ui_interval_from_1_to_168_hours)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    isError = (parsedHours ?: 0) !in 1..168, singleLine = true, enabled = !panel.busy,
                    colors = horizonFieldColors(), shape = horizonFieldShape())
                if (supportsIntervalStart) {
                    GlassSettingRow(ui(R.string.schedule_interval_start),
                        if (intervalStart.isBlank()) ui(R.string.schedule_interval_pick_start)
                        else targetTime(startHour, startMinute), enabled = !panel.busy) { pickTime = true }
                    GlassSettingRow(ui(R.string.ui_time_zone), zoneLabel(intervalZone), enabled = !panel.busy,
                        help = R.string.setting_help_schedule_zone) { pickZone = true }
                } else {
                    Text(ui(R.string.schedule_interval_update_agent), style = MaterialTheme.typography.bodySmall)
                }
            }
            "calendar" -> {
                val wash = rememberNoticeWashPhase()
                Text(ui(R.string.schedule_days), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((mask, title) in dayPresets) {
                        ScheduleDayChip(title, days == mask, !panel.busy, wash) { days = mask }
                    }
                }
                BoxWithConstraints {
                    val fontScale = LocalConfiguration.current.fontScale
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                        maxItemsInEachRow = if (maxWidth >= 384.dp * fontScale) 7 else 4) {
                        weekdays.forEachIndexed { index, title ->
                            val day = 1 shl index
                            ScheduleDayChip(title, days and day != 0, !panel.busy, wash) { days = days xor day }
                        }
                    }
                }
                if (days == 0) Text(ui(R.string.schedule_pick_day), color = MaterialTheme.colorScheme.error)
                GlassSettingRow(ui(R.string.schedule_time), targetTime(hour, minute), enabled = !panel.busy) { pickTime = true }
                GlassSettingRow(ui(R.string.ui_time_zone), zoneLabel(zone), enabled = !panel.busy,
                    help = R.string.setting_help_schedule_zone) { pickZone = true }
            }
        }
        Reveal(changed) {
            Text(ui(R.string.editorial_unsaved), color = palette.warning)
            Button(enabled = !panel.busy && when (mode) {
                "interval" -> (parsedHours ?: 0) in 1..168 && (!supportsIntervalStart || intervalStart.isNotBlank())
                "calendar" -> days != 0
                else -> true
            }, onClick = {
                val payload = spec.copyObject().put("mode", mode)
                    .put("hours", parsedHours?.takeIf { it in 1..168 } ?: savedHours)
                    .put("hour", hour).put("minute", minute)
                    .put("zone", if (mode == "interval" && supportsIntervalStart) intervalZone else zone)
                    .put("days", JSONArray(weekdays.indices.filter { (days.takeIf { it != 0 } ?: savedDays) and (1 shl it) != 0 }))
                if (supportsIntervalStart) payload.put("interval_start", if (mode == "interval" && intervalStart.isNotBlank()) intervalStart else JSONObject.NULL)
                model.schedule(payload)
            }) { Text(ui(R.string.ui_save_schedule)) }
        }
    }
    val interval = mode == "interval"
    if (pickTime) {
        ScheduleTimePicker(if (interval) startHour else hour, if (interval) startMinute else minute,
            if (interval) R.string.schedule_interval_start else R.string.schedule_time, { pickTime = false }
        ) { newHour, newMinute ->
            if (interval) intervalStart = LocalTime.of(newHour, newMinute).toString()
            else {
                hour = newHour
                minute = newMinute
            }
            pickTime = false
        }
    }
    if (pickZone) ScheduleZonePicker(if (interval) intervalZone else zone, { pickZone = false }) {
        if (interval) intervalZone = it else zone = it
        pickZone = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimePicker(hour: Int, minute: Int, title: Int, close: () -> Unit, choose: (Int, Int) -> Unit) {
    val time = rememberTimePickerState(hour, minute, is24Hour = true)
    val configuration = LocalConfiguration.current
    val compact = configuration.screenHeightDp < 580 || configuration.screenWidthDp < 360 || configuration.fontScale > 1.3f
    HorizonAlertDialog(close, { Text(ui(title)) }, text = {
        if (compact || title == R.string.schedule_interval_start) TimeInput(time) else TimePicker(time)
    }, confirmButton = {
        TextButton(onClick = { choose(time.hour, time.minute) }) { Text(ui(R.string.editorial_done)) }
    }, dismissButton = { TextButton(onClick = close) { Text(ui(R.string.ui_cancel)) } })
}

private fun zoneLabel(id: String, at: Instant = Instant.now(),
                      names: TimeZoneNames = TimeZoneNames.getInstance(appLocale())): String {
    val zone = runCatching { ZoneId.of(id) }.getOrNull() ?: return id
    val city = names.getExemplarLocationName(id) ?: id.substringAfterLast('/').replace('_', ' ')
    val offset = zone.rules.getOffset(at).id
    return "$city · UTC${if (offset == "Z") "" else offset}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleZonePicker(current: String, close: () -> Unit, choose: (String) -> Unit) {
    val available = remember { ZoneId.getAvailableZoneIds() }
    val device = ZoneId.systemDefault().id.takeIf { it in available }
    val locale = appLocale()
    var query by rememberSaveable { mutableStateOf("") }
    val zones = remember(current, device, locale) {
        val now = Instant.now()
        val names = TimeZoneNames.getInstance(locale)
        (listOfNotNull(device, current, "UTC") + available.sorted())
            .distinct()
            .map { it to zoneLabel(it, now, names) }
    }
    val visible = remember(query, zones) { zones.filter { (id, name) ->
        id.contains(query.trim(), true) || name.contains(query.trim(), true)
    } }
    ModalBottomSheet(onDismissRequest = close, modifier = Modifier.imePadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(ui(R.string.ui_time_zone), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, { query = it }, label = { Text(ui(R.string.schedule_zone_search)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = horizonFieldColors(), shape = horizonFieldShape())
            if (device != null) TextButton(onClick = { choose(device) }) { Text(ui(R.string.schedule_device_zone)) }
            if (visible.isEmpty()) Text(ui(R.string.schedule_zone_empty), Modifier.padding(bottom = 16.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(visible, key = { it.first }) { (id, name) ->
                    ListItem(
                        headlineContent = { Text(name) }, supportingContent = { Text(id) },
                        trailingContent = { RadioButton(id == current, onClick = null, colors = RadioButtonDefaults.colors(
                            selectedColor = ReadingGlass.text, unselectedColor = ReadingGlass.muted)) },
                        modifier = Modifier.settingsControlGlass().selectable(id == current, role = Role.RadioButton) { choose(id) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent,
                            headlineColor = ReadingGlass.text, supportingColor = ReadingGlass.muted),
                    )
                }
            }
        }
    }
}
