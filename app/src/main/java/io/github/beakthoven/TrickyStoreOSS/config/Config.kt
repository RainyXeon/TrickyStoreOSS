/*
 * Copyright 2025 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.config

import android.content.pm.IPackageManager
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.os.IInterface
import android.os.ServiceManager
import android.os.SystemProperties
import io.github.beakthoven.TrickyStoreOSS.AttestUtils.TEEStatus
import io.github.beakthoven.TrickyStoreOSS.KeyBoxUtils
import io.github.beakthoven.TrickyStoreOSS.logging.Logger
import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlIndentation
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.TomlOutputConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

object PkgConfig {
    private val hackPackages = mutableSetOf<String>()
    private val generatePackages = mutableSetOf<String>()
    private val packageModes = mutableMapOf<String, Mode>()

    enum class Mode {
        AUTO, LEAF_HACK, GENERATE
    }

    private fun updateTargetPackages(f: File?) = runCatching {
        hackPackages.clear()
        generatePackages.clear()
        packageModes.clear()
        f?.readLines()?.forEach {
            if (it.isNotBlank() && !it.startsWith("#")) {
                val n = it.trim()
                when {
                    n.endsWith("!") -> {
                        val pkg = n.removeSuffix("!").trim()
                        generatePackages.add(pkg)
                        packageModes[pkg] = Mode.GENERATE
                    }
                    n.endsWith("?") -> {
                        val pkg = n.removeSuffix("?").trim()
                        hackPackages.add(pkg)
                        packageModes[pkg] = Mode.LEAF_HACK
                    }
                    else -> {
                        // Auto mode
                        packageModes[n] = Mode.AUTO
                    }
                }
            }
        }
        Logger.i("update hack packages: $hackPackages, generate packages=$generatePackages, packageModes=$packageModes")
    }.onFailure {
        Logger.e("failed to update target files", it)
    }

    private fun updateKeyBox(f: File?) = runCatching {
        KeyBoxUtils.readFromXml(f?.readText())
    }.onFailure {
        Logger.e("failed to update keybox", it)
    }

    private const val CONFIG_PATH = "/data/adb/tricky_store"
    private const val TARGET_FILE = "target.txt"
    private const val KEYBOX_FILE = "keybox.xml"
    private const val TEE_STATUS_FILE = "tee_status"
    private const val DEV_CONFIG_FILE = "config.toml"
    private val root = File(CONFIG_PATH)

    @Volatile
    private var teeBroken: Boolean? = null

    private fun storeTEEStatus(root: File) {
        val statusFile = File(root, TEE_STATUS_FILE)
        teeBroken = !TEEStatus
        try {
            statusFile.writeText("teeBroken=${teeBroken}")
            Logger.i("TEE status written to $statusFile: teeBroken=$teeBroken") 
        } catch (e: Exception) {
            Logger.e("Failed to write TEE status: ${e.message}")
        }
    }

    private fun loadTEEStatus(root: File) {
        val statusFile = File(root, TEE_STATUS_FILE)
        if (statusFile.exists()) {
            val line = statusFile.readText().trim()
            teeBroken = line == "teeBroken=true"
        } else {
            teeBroken = null
        }
    }

    object ConfigObserver : FileObserver(root, CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO) {
        override fun onEvent(event: Int, path: String?) {
            path ?: return
            val f = when (event) {
                CLOSE_WRITE, MOVED_TO -> File(root, path)
                DELETE, MOVED_FROM -> null
                else -> return
            }
            when (path) {
                TARGET_FILE -> updateTargetPackages(f)
                KEYBOX_FILE -> updateKeyBox(f)
                DEV_CONFIG_FILE -> parseDevConfig(f)
            }
        }
    }

    fun initialize() {
        root.mkdirs()
        val scope = File(root, TARGET_FILE)
        if (scope.exists()) {
            updateTargetPackages(scope)
        } else {
            Logger.e("target.txt file not found, please put it to $scope !")
        }
        val keybox = File(root, KEYBOX_FILE)
        if (!keybox.exists()) {
            Logger.e("keybox file not found, please put it to $keybox !")
        } else {
            updateKeyBox(keybox)
        }
        val fDevConfig = File(root, DEV_CONFIG_FILE)
        if (!fDevConfig.exists()) {
            fDevConfig.createNewFile()
            fDevConfig.writeText(Toml.encodeToString(devConfig))
        } else {
            parseDevConfig(fDevConfig)
        }
        storeTEEStatus(root)
        ConfigObserver.startWatching()
    }

    private var iPm: IPackageManager? = null
    private val packageManagerDeathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            (iPm as? IInterface)?.asBinder()?.unlinkToDeath(this, 0)
            iPm = null
        }
    }

    fun getPm(): IPackageManager? {
        if (iPm == null) {
            val binder = waitAndGetSystemService("package") ?: return null
            binder.linkToDeath(packageManagerDeathRecipient, 0)
            iPm = IPackageManager.Stub.asInterface(binder)
        }
        return iPm
    }

    fun needHack(callingUid: Int): Boolean = kotlin.runCatching {
        val ps = getPm()?.getPackagesForUid(callingUid) ?: return false
        if (teeBroken == null) loadTEEStatus(root)
        for (pkg in ps) {
            when (packageModes[pkg]) {
                Mode.LEAF_HACK -> return true
                Mode.AUTO -> {
                    if (teeBroken == false) return true
                }
                else -> {}
            }
        }
        return false
    }.onFailure { Logger.e("failed to get packages", it) }.getOrNull() ?: false

    fun needGenerate(callingUid: Int): Boolean = kotlin.runCatching {
        val ps = getPm()?.getPackagesForUid(callingUid) ?: return false
        if (teeBroken == null) loadTEEStatus(root)
        for (pkg in ps) {
            when (packageModes[pkg]) {
                Mode.GENERATE -> return true
                Mode.AUTO -> {
                    if (teeBroken == true) return true
                }
                else -> {}
            }
        }
        return false
    }.onFailure { Logger.e("failed to get packages", it) }.getOrNull() ?: false

    private val toml = Toml(
        inputConfig = TomlInputConfig(
            ignoreUnknownNames = false,
            allowEmptyValues = true,
            allowNullValues = true,
            allowEscapedQuotesInLiteralStrings = true,
            allowEmptyToml = true,
            ignoreDefaultValues = false,
        ),
        outputConfig = TomlOutputConfig(
            indentation = TomlIndentation.FOUR_SPACES,
        )
    )

    var devConfig = DeviceConfig()
        private set

    @Serializable
    data class DeviceConfig(
        val securityPatch: String = Build.VERSION.SECURITY_PATCH,
        val osVersion: Int = Build.VERSION.SDK_INT,
        val brand: String = Build.BRAND,
        val device: String = Build.DEVICE,
        val product: String = Build.PRODUCT,
        val manufacturer: String = Build.MANUFACTURER,
        val model: String = Build.MODEL,
        val serial: String = SystemProperties.get("ro.serialno", ""),
        val meid: String = SystemProperties.get("ro.ril.oem.imei", ""),
        val imei: String = SystemProperties.get("ro.ril.oem.meid", ""),
        val imei2: String = SystemProperties.get("ro.ril.oem.imei2", ""),
    )

    fun parseDevConfig(f: File?) = runCatching {
        f ?: return@runCatching
        if (!f.exists()) return@runCatching
        devConfig = toml.decodeFromString(DeviceConfig.serializer(), f.readText())
        // in case there're new updates for device config
        f.writeText(Toml.encodeToString(devConfig))
    }.onFailure {
        Logger.e("", it)
    }

    private fun waitAndGetSystemService(name: String): IBinder? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return ServiceManager.waitForService(name)
        }

        var tryCount = 0
        while (tryCount++ < 70) {
            val service = ServiceManager.getService(name)
            if (service != null) {
                Logger.d("Got $name service after $tryCount tries")
                return service
            }
            Thread.sleep(500)
        }

        Logger.e("Failed to get $name service")
        return null
    }
}
