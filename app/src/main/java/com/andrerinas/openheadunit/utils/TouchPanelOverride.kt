package com.andrerinas.openheadunit.utils

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Raises a Goodix GT9xx touch panel's report rate through the driver's config node. A QF K706 ships reporting every
 * 10.5 ms (about 95 Hz); the controller's refresh byte at its minimum gives 5.7 ms (about 175 Hz), measured.
 *
 * The node is read first and nothing is written unless it holds a config in the expected layout with a valid checksum.
 * Only that one byte and the checksum change. The node takes the config as hex text. The controller goes back to its
 * own config at a reboot, so the app sets it again at each start while the setting is on. Writing needs a root shell.
 */
object TouchPanelOverride {

    private const val NODE = "/proc/gt9xx_config"
    private const val TEMP = "/data/local/tmp/ohu_gt9xx.txt"
    private const val LENGTH = 186
    private const val CHECKSUM_AT = 184
    private const val REFRESH_AT = 15
    private const val FASTEST = 0

    /** The refresh byte a K706 ships with, for a panel already overridden when the app first looks. */
    const val STOCK_REFRESH = 5

    private val lock = Mutex()

    /** The live config in the node's output, or null when it is not one this knows how to change. */
    fun parse(output: String): IntArray? {
        val live = output.substringAfter("====real value====", "")
        val bytes = Regex("0x([0-9A-Fa-f]{2})").findAll(live).map { it.groupValues[1].toInt(16) }.take(LENGTH).toList()
        if (bytes.size < LENGTH) return null
        val config = bytes.toIntArray()
        val width = config[1] or (config[2] shl 8)
        val height = config[3] or (config[4] shl 8)
        if (width !in 100..8192 || height !in 100..8192) return null
        return config.takeIf { checksum(it) == it[CHECKSUM_AT] }
    }

    /** [config] with another refresh byte, its checksum and the "new config" flag, as the text the node accepts. */
    fun encode(config: IntArray, refresh: Int): String {
        val next = config.copyOf(LENGTH)
        next[REFRESH_AT] = refresh
        next[CHECKSUM_AT] = checksum(next)
        next[LENGTH - 1] = 1
        return next.joinToString(",") { "0x%02X".format(it) }
    }

    private fun checksum(config: IntArray): Int = (-(0 until CHECKSUM_AT).sumOf { config[it].toInt() }) and 0xFF

    /** Sets the fastest rate, or the panel's own one back. True when the panel reads back as asked. */
    suspend fun set(context: Context, fast: Boolean): Boolean = withContext(Dispatchers.IO) {
        // CoD OS: the touch rate is a system feature with one writer (the framework's trial manager); /proc/gt9xx_config is
        // root-owned, so this app could not write it as uid 1000 anyway. Nothing is read or written here.
        if (com.andrerinas.openheadunit.BuildConfig.CODOS) {
            AppLog.i("TouchPanelOverride: not set (CoD OS keeps one system switch for the touch rate)")
            return@withContext false
        }
        lock.withLock {
            val app = context.applicationContext
            val settings = Settings(app)
            try {
                val config = read(app) ?: return@withLock fail("no config this can change at $NODE")
                val now = config[REFRESH_AT]
                if (fast && now != FASTEST) settings.touchPanelStockRefresh = now
                val target = if (fast) FASTEST else settings.touchPanelStockRefresh
                if (now == target) return@withLock true
                OemAppManager.executeCommand(
                    app,
                    "printf '%s' '${encode(config, target)}' > $TEMP && dd if=$TEMP of=$NODE bs=4096 count=1; sleep 1; rm -f $TEMP"
                )
                val after = read(app)?.get(REFRESH_AT)
                AppLog.i("TouchPanelOverride: refresh byte $now -> $after (asked for $target)")
                after == target
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** [set] for callers that do not wait, after [delayMs]. */
    fun apply(context: Context, fast: Boolean, delayMs: Long = 0L) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            delay(delayMs)
            set(app, fast)
        }
    }

    private suspend fun read(context: Context): IntArray? =
        parse(OemAppManager.executeCommand(context, "cat $NODE").second)

    private fun fail(why: String): Boolean {
        AppLog.w("TouchPanelOverride: $why")
        return false
    }
}
