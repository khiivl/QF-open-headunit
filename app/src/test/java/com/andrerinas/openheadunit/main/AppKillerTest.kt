package com.andrerinas.openheadunit.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppKillerTest {

    @Test
    fun `real package names pass`() {
        assertTrue(AppKiller.isSafePackage("com.google.android.apps.maps"))
        assertTrue(AppKiller.isSafePackage("org.videolan.vlc"))
        assertTrue(AppKiller.isSafePackage("com.app_name.v2"))
    }

    @Test
    fun `anything a shell would act on is refused`() {
        assertFalse(AppKiller.isSafePackage(""))
        assertFalse(AppKiller.isSafePackage("com.a; reboot"))
        assertFalse(AppKiller.isSafePackage("com.a && id"))
        assertFalse(AppKiller.isSafePackage("com.a\nid"))
        assertFalse(AppKiller.isSafePackage("\$(id)"))
        assertFalse(AppKiller.isSafePackage("nodots"))
    }
}
