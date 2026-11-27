package com.andrerinas.openheadunit.tools

import android.os.IBinder

/**
 * Switches the built-in display off and straight back on through SurfaceFlinger, leaving Android awake. The panel driver
 * programs itself again on the way back, which is when it takes up a new refresh rate (see ScreenRefresh). Run as root:
 *
 *     CLASSPATH=<path to this apk> app_process /system/bin com.andrerinas.openheadunit.tools.DisplayPowerTool
 */
object DisplayPowerTool {

    private const val OFF = 0
    private const val NORMAL = 2

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val surfaces = Class.forName("android.view.SurfaceControl")
            val display = surfaces.getMethod("getInternalDisplayToken").invoke(null)
            val setPower = surfaces.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
            try {
                setPower.invoke(null, display, OFF)
            } finally {
                setPower.invoke(null, display, NORMAL)
            }
            System.exit(0)
        } catch (e: Throwable) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            println("error: ${cause.javaClass.simpleName}: ${cause.message}")
            System.exit(1)
        }
    }
}
