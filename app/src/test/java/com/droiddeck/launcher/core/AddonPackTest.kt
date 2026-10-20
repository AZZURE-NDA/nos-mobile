package com.droiddeck.launcher.core

import android.content.Context
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AddonPackTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val current = "b".repeat(64)
    private val pack = AddonPack("test", current, 1, emptyList())

    @After fun clean() {
        File(context.filesDir, "addons").deleteRecursively()
    }

    private fun version(sha256: String) = File(context.filesDir, "addons/test/$sha256").apply {
        mkdirs()
        File(this, AddonPack.COMPLETE).writeText(sha256)
    }

    @Test fun anotherBuildsVersionIsOutdatedUntilThisBuildsIsInstalled() {
        assertFalse(pack.outdated(context))
        File(context.filesDir, "addons/test/${"c".repeat(64)}").mkdirs()
        assertFalse(pack.outdated(context))
        version("a".repeat(64))
        assertTrue(pack.outdated(context))
        assertFalse(pack.installed(context))
        version(current)
        assertTrue(pack.installed(context))
        assertFalse(pack.outdated(context))
    }

    @Test fun removeDeletesEveryVersion() {
        version("a".repeat(64))
        version(current)
        assertEquals(AddonPack.State.Ready, pack.refresh(context))
        assertTrue(pack.remove(context))
        assertFalse(File(context.filesDir, "addons/test").exists())
        assertEquals(AddonPack.State.Missing, pack.state.value)
        assertFalse(pack.installed(context))
    }
}
