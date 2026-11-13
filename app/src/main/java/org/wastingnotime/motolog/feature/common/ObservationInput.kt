package org.wastingnotime.motolog.feature.common

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.wastingnotime.motolog.domain.model.Observation
import org.wastingnotime.motolog.domain.ride.observationAt

fun observationText(value: Observation): String =
    Instant.ofEpochMilli(value.epochMillis).atZone(ZoneId.of(value.zoneId))
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a z", Locale.US))

/** Null means observed now; the command freezes the instant on review, not on retry. */
@Composable
fun ObservationInput(selected: Long?, enabled: Boolean, change: (Long?) -> Unit) {
    val context = LocalContext.current
    val date = Instant.ofEpochMilli(selected ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault())
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text(if (selected == null) "I observed this reading now" else observationText(observationAt(selected)))
    if (selected != null) TextButton(enabled = enabled, onClick = { change(null) }) { Text("I observed this reading now") }
    TextButton(enabled = enabled, onClick = { expanded = !expanded }) { Text("Change observation time") }
    if (expanded) {
        OutlinedButton(enabled = enabled, onClick = {
            DatePickerDialog(context, { _, y, m, d ->
                change(date.withDayOfMonth(1).withYear(y).withMonth(m + 1).withDayOfMonth(d).toInstant().toEpochMilli())
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }) { Text("Choose observation date") }
        OutlinedButton(enabled = enabled, onClick = {
            TimePickerDialog(context, { _, h, m -> change(date.withHour(h).withMinute(m).withSecond(0).withNano(0).toInstant().toEpochMilli()) },
                date.hour, date.minute, false).show()
        }) { Text("Choose observation time") }
    }
}
