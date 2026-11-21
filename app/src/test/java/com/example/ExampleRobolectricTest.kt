package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name and firestore named database resource are configured`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("M-Sageer Pay", context.getString(R.string.app_name))
        val dbId = context.getString(R.string.firestore_database_id)
        assertTrue("Expected non-empty named Firestore database ID", dbId.startsWith("ai-studio-android-"))
    }
}
