package org.wastingnotime.motolog

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.wastingnotime.motolog.data.local.MotoLogDatabase

class SchemaTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        MotoLogDatabase::class.java)

    @Test fun exportedInitialSchemaValidatesWithoutInventedMigration() {
        helper.createDatabase("schema-test", 1).close()
        helper.runMigrationsAndValidate("schema-test", 1, true).close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase("schema-test")
    }
}
