package org.wastingnotime.motolog

import android.app.Application
import org.wastingnotime.motolog.data.local.MotoLogDatabase
import org.wastingnotime.motolog.data.repository.LocalStore

class MotoLogApplication : Application() {
    // App-scoped handles; SQLite opens on the caller's IO dispatcher, never the UI thread.
    val database by lazy { MotoLogDatabase.open(this) }
    val store by lazy { LocalStore(database) }
}
