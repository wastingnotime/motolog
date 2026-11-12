package org.wastingnotime.motolog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.wastingnotime.motolog.data.repository.RegistrationRepository
import org.wastingnotime.motolog.data.repository.MaintenanceRepository
import org.wastingnotime.motolog.data.repository.FuelRepository
import org.wastingnotime.motolog.feature.registration.RegistrationScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val database = (application as MotoLogApplication).database
        val repository = RegistrationRepository(database)
        val maintenance = MaintenanceRepository(database)
        val fuel = FuelRepository(database)
        setContent { RegistrationScreen(repository, maintenance, fuel) }
    }
}
