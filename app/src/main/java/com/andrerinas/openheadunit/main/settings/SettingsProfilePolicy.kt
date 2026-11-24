package com.andrerinas.openheadunit.main.settings

/**
 * Which settings are not offered on a unit that runs zLink (the QF head units this fork is for).
 *
 * Everything here is about how a phone is connected or about test tools, and each is either not used on such a unit or
 * can only make it stop working: the unit connects through zLink's hotspot and its Bluetooth module, so the other Wi-Fi
 * modes and the WiFi Direct transport, the wireless USB adapter and Android Bluetooth plumbing, and the
 * fault-injection tools have nothing to configure. A phone on a USB cable (wired Android Auto) is offered: the unit is a
 * USB host. The hotspot band, channel and Auto-enable rows are kept on purpose. The values behind the hidden
 * rows are pinned at app start (see App), so a hidden setting can not stay on a value that breaks the connection.
 */
object SettingsProfilePolicy {

    val HIDDEN_ON_ZLINK_UNIT: Set<String> = setOf(
        // The note saying which connection types' settings are shown: there is only one kind here
        "scopeNotice",
        // The setup wizard picks connection and video settings for a generic unit; here they are fixed and proven
        "autoOptimize",
        // Android Bluetooth and the wireless USB adapter: the unit's phone link is the Bluetooth module, not these.
        // The connection mode (Wi-Fi, USB cable), the USB listening switch and the libusb switch stay: wired Android Auto.
        "bluetoothAddress", "usbWirelessDongle",
        "bluetoothAdapterServiceName", "nativePokeAllPaired",
        // Wi-Fi modes and the WiFi Direct transport: only Native Android Auto over the hotspot is used
        "wifiConnectionMode", "nativeApTransport", "wireless24ghzWarning",
        "stationStandDownMode", "stationStandDownOverlay", "wifiDirectStableIdentity", "wifiDirectIdentityLegacy",
        "serverModeSelection", "headunitServerNetwork", "headunitServerAutoWifiScan",
        "helperStrategy", "waitForWifi", "waitForWifiTimeout", "staticBSSID", "wifiScanControl",
        "keepDummyVpnDuringSession", "projectionSetupQr",
        // The hotspot is zLink's, on 5 GHz (OHU keeps it there): OHU never picks a band or channel for it
        "hotspotBand", "fiveGhzChannel",
        // Typed-in hotspot values: OHU reads the real ones from the hotspot
        "hotspotSsidOverride", "hotspotPasswordOverride", "hotspotInterfaceOverride",
        // Stopping zLink would stop CarPlay and the hotspot
        "oemAppManagement",
        // Test tools that can only degrade the picture
        "debugVideoFaultInjection", "debugVideoFaultRate", "debugVideoFaultBudget", "debugVideoFeedHold",
        "debugForceMemoryProfile",
    )

    /**
     * Settings that only mean something to Android Auto: the picture it asks the phone for, its audio and microphone
     * handling, how it starts and ends, the keys and gestures it takes, navigation data, and the native wireless
     * machinery behind it. CarPlay's picture, audio and touch are fixed by zLink, so none of it applies there.
     */
    val ANDROID_AUTO_ONLY: Set<String> = setOf(
        "vehicleInfoSettings",
        "resolution", "startInFullscreenMode", "videoFitMode", "dpiPixelDensity", "customInsets", "pixelAspectRatioE4",
        "viewMode", "forcedScale",
        "videoCodec", "fpsLimit", "narrowBandProfileCap", "forceSoftwareDecoding", "softwareVideoDecoder", "debugVideoLowLatency",
        "enableAudioSink", "staticAudioFocus", "playbackFocusMode", "playbackFocusModeStaticHint", "speedVolumeMode",
        "audioStreamSettings", "useAAudioOutput", "useAacAudio", "attachHwDspEqualizer", "syncMediaSessionAaMetadata",
        "autoResumePlaybackOnReconnect", "micSettings", "audioVolumeOffsets", "audioMatchingMode", "audioMatchingModeHint",
        "audioLatencyMultiplier", "audioQueueCapacity",
        "autoStartSettings", "autoConnectSettings", "killOnDisconnect", "raiseProjectionDuringCall", "showOverLockScreen",
        "aaExitAction", "allowExternalConfiguration",
        "keymap", "gestures", "enableRotary", "mediaKeyRouting", "mediaKeyRoutingScopeHint", "volumeKeysToPhone", "voiceKeyFromBridge",
        "gpsNavigation", "showNavigationNotifications", "fullNavigationData", "thirdPartyAccess",
        "loadingScreen", "hideClock", "hidePhoneSignal", "hideBatteryLevel",
        "autoEnableHotspot", "externalBtModuleControlsHint", "externalBtModuleLastPhone",
        "nativeDriverSelectionMode", "nativeDriverSelectionTimeout", "nativePreferredDevice", "nativePokeBt",
        "nativeAaEnableBluetooth", "nativeAaWakeRemeasure", "bluetoothLink", "Esp32Relay", "zbtProbe",
        "wifiDirectBand", "autoRestartWifiWhenP2pStuck", "wifiDirectNewIdentity", "wifiDirectLastNetwork", "wifiDirectGroupIdentity",
    )

    /** Settings that only mean something to CarPlay (zLink's own screens). */
    val CARPLAY_ONLY: Set<String> = setOf("hideZlinkUi")

    /**
     * What is not offered: on a zLink unit the always-hidden rows, and whichever of the two projections is not the
     * running one has its own settings hidden. When [both] is true (projection availability is BOTH), both Android Auto
     * and CarPlay settings are offered. Shared settings (language, hotspot name and password, theming of the home,
     * launcher and floating button, backup, logs) are never in either set.
     */
    fun hiddenIds(zlinkUnit: Boolean, carPlayMode: Boolean = false, both: Boolean = false): Set<String> =
        if (!zlinkUnit) emptySet()
        else HIDDEN_ON_ZLINK_UNIT + when {
            both -> emptySet()
            carPlayMode -> ANDROID_AUTO_ONLY
            else -> CARPLAY_ONLY
        }
}
