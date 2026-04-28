/*
 * Copyright 2025 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS

import android.content.pm.IPackageManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.ServiceManager
import android.os.SystemProperties
import android.telephony.TelephonyManager
import io.github.beakthoven.TrickyStoreOSS.AttestUtils.CachedAttestData
import io.github.beakthoven.TrickyStoreOSS.config.PkgConfig
import io.github.beakthoven.TrickyStoreOSS.logging.Logger
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.DERTaggedObject
import java.security.MessageDigest
import java.util.concurrent.ThreadLocalRandom

object AndroidUtils {

    val bootKey: ByteArray by lazy {
        randomBytes()
    }

    fun setupBootHash() {
        getBootHashFromProp()?.also { 
            Logger.d("Using boot hash from system property: ${it.toHex()}")
        }
        ?: getBootHashFromAttestation()?.also { 
            Logger.d("Using boot hash from attestation: ${it.toHex()}")
            setBootHashProp(it)
        }
        ?: randomBytes().also { 
            Logger.d("Generating random boot hash: ${it.toHex()}")
            setBootHashProp(it)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun getBootHashFromProp(): ByteArray? {
        val digest = SystemProperties.get("ro.boot.vbmeta.digest", null) ?: return null
        Logger.d("System property ro.boot.vbmeta.digest: $digest")
        
        if (digest.isBlank()) {
            Logger.d("Property is blank")
            return null
        }
        
        return if (digest.length == 64) digest.hexToByteArray() else null
    }

    private fun getBootHashFromAttestation(): ByteArray? {
        return try {
            CachedAttestData?.verifiedBootHash
        } catch (e: Exception) {
            Logger.e("Failed to get boot hash from attestation: ${e.message}")
            null
        }
    }

    private fun setBootHashProp(bytes: ByteArray) {
        val hex = bytes.toHex()
        try {
            Logger.d("Setting ro.boot.vbmeta.digest to: $hex")
            SystemProperties.set("ro.boot.vbmeta.digest", hex)
        } catch (e: Exception) {
            Logger.e("Exception setting vbmeta digest: ${e.message}")
        }
    }

    private fun randomBytes(): ByteArray = ByteArray(32).also { 
        ThreadLocalRandom.current().nextBytes(it) 
    }

    val patchLevel: Int
        get() = runCatching {
            PkgConfig.devConfig.securityPatch.convertPatchLevel(false)
        }.getOrDefault(Build.VERSION.SECURITY_PATCH.convertPatchLevel(false))

    val patchLevelLong: Int
        get() = runCatching {
            PkgConfig.devConfig.securityPatch.convertPatchLevel(true)
        }.getOrDefault(Build.VERSION.SECURITY_PATCH.convertPatchLevel(false))

    val vendorPatchLevel: Int
        get() = runCatching {
            PkgConfig.devConfig.securityPatch.convertPatchLevel(true)
        }.getOrDefault(Build.VERSION.SECURITY_PATCH.convertPatchLevel(false))

    val vendorPatchLevelLong: Int
        get() = runCatching {
            PkgConfig.devConfig.securityPatch.convertPatchLevel(true)
        }.getOrDefault(Build.VERSION.SECURITY_PATCH.convertPatchLevel(false))

    val bootPatchLevel: Int
        get() = runCatching {
            PkgConfig.devConfig.securityPatch.convertPatchLevel(true)
        }.getOrDefault(Build.VERSION.SECURITY_PATCH.convertPatchLevel(false))

    val bootPatchLevelLong: Int
        get() = runCatching {
            PkgConfig.devConfig.securityPatch.convertPatchLevel(true)
        }.getOrDefault(Build.VERSION.SECURITY_PATCH.convertPatchLevel(false))

    private val osVersionMap = mapOf(
        Build.VERSION_CODES.BAKLAVA to 160000,
        Build.VERSION_CODES.VANILLA_ICE_CREAM to 150000,
        Build.VERSION_CODES.UPSIDE_DOWN_CAKE to 140000,
        Build.VERSION_CODES.TIRAMISU to 130000,
        Build.VERSION_CODES.S_V2 to 120100,
        Build.VERSION_CODES.S to 120000,
        Build.VERSION_CODES.R to 110000,
        Build.VERSION_CODES.Q to 100000
    )

    val osVersion: Int
        // get() = CachedAttestData?.osVersion ?: osVersionMap[Build.VERSION.SDK_INT] ?: 160000
        get() = PkgConfig.devConfig.osVersion.run {
            if (this > 0) return@run getOsVersion(this)
            else return@run getOsVersion(Build.VERSION.SDK_INT)
        }

    private fun getOsVersion(num: Int) = when (num) {
        Build.VERSION_CODES.BAKLAVA -> 160000
        Build.VERSION_CODES.VANILLA_ICE_CREAM -> 150000
        Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> 140000
        Build.VERSION_CODES.TIRAMISU -> 130000
        Build.VERSION_CODES.S_V2 -> 120100
        Build.VERSION_CODES.S -> 120000
        Build.VERSION_CODES.R -> 110000
        Build.VERSION_CODES.Q -> 100000
        else -> 160000
    }

    // private val attestVersionMap = mapOf(
    //     Build.VERSION_CODES.Q to 4,                   // Keymaster 4.1
    //     Build.VERSION_CODES.R to 4,                   // Keymaster 4.1
    //     Build.VERSION_CODES.S to 100,                 // KeyMint 1.0
    //     Build.VERSION_CODES.S_V2 to 100,              // KeyMint 1.0
    //     Build.VERSION_CODES.TIRAMISU to 200,          // KeyMint 2.0
    //     Build.VERSION_CODES.UPSIDE_DOWN_CAKE to 300,  // KeyMint 3.0
    //     Build.VERSION_CODES.VANILLA_ICE_CREAM to 300, // KeyMint 3.0
    //     Build.VERSION_CODES.BAKLAVA to 400            // KeyMint 4.0
    // )

    // INFO: this is just a workaround to make some device can pass wallet
    val attestVersion: Int
        // get() = CachedAttestData?.attestVersion ?: attestVersionMap[Build.VERSION.SDK_INT] ?: 400
        get() = 400

    val keymasterVersion: Int
        // get() = CachedAttestData?.keymasterVersion ?: if (attestVersion == 4) 41 else attestVersion
        get() = 400

    fun String.convertPatchLevel(isLong: Boolean): Int = runCatching {
        val parts = split("-")
        when {
            isLong && parts.size >= 3 -> parts[0].toInt() * 10000 + parts[1].toInt() * 100 + parts[2].toInt()
            parts.size >= 2 -> parts[0].toInt() * 100 + parts[1].toInt()
            else -> throw IllegalArgumentException("Invalid patch level format: $this")
        }
    }.onFailure { 
        Logger.e("Invalid patch level format: $this", it) 
    }.getOrDefault(202404)

    val apexInfos: List<Pair<String, Long>> by lazy {
        runCatching {
            val packageManager = IPackageManager.Stub.asInterface(ServiceManager.getService("package"))
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.MATCH_APEX.toLong(), 0)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(PackageManager.MATCH_APEX, 0)
            }
            
            packages.list
                .map { it.packageName to it.longVersionCode }
                .sortedBy { it.first }
        }.getOrElse { 
            Logger.e("Failed to get APEX package information")
            emptyList() 
        }
    }

    val moduleHash: ByteArray by lazy {
        runCatching {
            val encodables = apexInfos.flatMap { (packageName, versionCode) ->
                listOf(
                    DEROctetString(packageName.toByteArray()),
                    ASN1Integer(versionCode)
                )
            }
            
            val sequence = DERSequence(encodables.toTypedArray())
            MessageDigest.getInstance("SHA-256").digest(sequence.encoded)
        }.getOrElse {
            Logger.e("Failed to compute module hash", it)
            ByteArray(32)
        }
    }

    val telephonyInfos by lazy {
        mutableListOf<DERTaggedObject>().apply {
            add(DERTaggedObject(true, 710, DEROctetString(PkgConfig.devConfig.brand.toByteArray())))
            add(DERTaggedObject(true, 711, DEROctetString(PkgConfig.devConfig.device.toByteArray())))
            add(DERTaggedObject(true, 712, DEROctetString(PkgConfig.devConfig.product.toByteArray())))
            add(DERTaggedObject(true, 716, DEROctetString(PkgConfig.devConfig.manufacturer.toByteArray())))
            add(DERTaggedObject(true, 717, DEROctetString(PkgConfig.devConfig.model.toByteArray())))

            add(DERTaggedObject(true, 713, DEROctetString(PkgConfig.devConfig.serial.toByteArray())))
            add(DERTaggedObject(true, 714, DEROctetString(PkgConfig.devConfig.imei.toByteArray())))
            add(DERTaggedObject(true, 715, DEROctetString(PkgConfig.devConfig.meid.toByteArray())))
            add(DERTaggedObject(true, 723, DEROctetString(PkgConfig.devConfig.imei2.toByteArray())))
        }.toList()
    }
}

fun String.trimLine(): String = trim().split("\n").joinToString("\n") { it.trim() }
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }