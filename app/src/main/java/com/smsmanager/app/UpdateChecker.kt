package com.smsmanager.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases API orqali yangi versiyani tekshiradi.
 *
 * Arxitektura:
 * - "latest" release body'si: {"versionCode": N}
 * - Joriy versionCode bilan solishtiradi
 * - Yangi versiya topilsa — UpdateInfo qaytaradi
 */
data class UpdateInfo(
    val versionCode: Int,
    val downloadUrl: String,
    val releaseTitle: String
)

object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/kmehmonov/sms_manager/releases/latest"
    private const val APK_ASSET_NAME = "sms-manager.apk"

    /**
     * GitHub'dan eng so'nggi versiyani tekshiradi.
     *
     * @param currentVersionCode O'rnatilgan versiyaning versionCode raqami
     * @return UpdateInfo — yangi versiya mavjud bo'lsa; null — aks holda
     */
    suspend fun checkForUpdate(currentVersionCode: Int): UpdateInfo? =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(API_URL).openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "SMS-Manager-Android")
                    connectTimeout = 10_000
                    readTimeout  = 10_000
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

                val responseText = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val root = JSONObject(responseText)

                // Release body'sidan versionCode o'qiymiz: {"versionCode": N}
                val body = root.optString("body", "{}")
                val remoteVersionCode = try {
                    JSONObject(body).optInt("versionCode", 0)
                } catch (e: Exception) { 0 }

                // Eski yoki bir xil versiya — yangilanish kerak emas
                if (remoteVersionCode <= currentVersionCode) return@withContext null

                // APK yuklab olish havolasini topamiz
                val assets = root.optJSONArray("assets") ?: return@withContext null
                var downloadUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name") == APK_ASSET_NAME) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                downloadUrl ?: return@withContext null

                UpdateInfo(
                    versionCode  = remoteVersionCode,
                    downloadUrl  = downloadUrl,
                    releaseTitle = root.optString("name", "Yangi versiya")
                )
            } catch (e: Exception) {
                null   // Tarmoq xatosi — banner ko'rsatmaymiz
            }
        }
}
