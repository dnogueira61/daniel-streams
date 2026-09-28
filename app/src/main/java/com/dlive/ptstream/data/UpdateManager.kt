package com.dlive.ptstream.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val versionName: String,
    val releaseTitle: String,
    val changelog: String,
    val downloadUrl: String,
    val apkFileName: String,
    val apkSize: Long = 0L
)

class UpdateManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("daniel_streams_update_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_REPO = "dnogueira61/daniel-streams"
        private const val PREF_REPO_KEY = "github_repo"
        private const val PREF_TOKEN_KEY = "github_token"
        private const val PREF_CHECK_ON_START = "check_on_start"
    }

    fun getGitHubRepo(): String {
        return prefs.getString(PREF_REPO_KEY, DEFAULT_REPO) ?: DEFAULT_REPO
    }

    fun setGitHubRepo(repo: String) {
        val clean = repo.trim()
            .removePrefix("https://github.com/")
            .removeSuffix(".git")
            .removeSuffix("/")
        prefs.edit().putString(PREF_REPO_KEY, clean).apply()
    }

    fun getGitHubToken(): String {
        return prefs.getString(PREF_TOKEN_KEY, "") ?: ""
    }

    fun setGitHubToken(token: String) {
        prefs.edit().putString(PREF_TOKEN_KEY, token.trim()).apply()
    }

    fun isCheckOnStartEnabled(): Boolean {
        return prefs.getBoolean(PREF_CHECK_ON_START, true)
    }

    fun setCheckOnStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_CHECK_ON_START, enabled).apply()
    }

    fun getCurrentVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    fun getCurrentVersionCode(): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            1L
        }
    }

    /**
     * Verifica na API do GitHub se existe uma versão mais recente
     */
    suspend fun checkForUpdate(repoOverride: String? = null): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        try {
            val repo = (repoOverride ?: getGitHubRepo()).trim()
            val apiUrl = "https://api.github.com/repos/$repo/releases/latest"
            
            val token = getGitHubToken()
            val url = URL(apiUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "DanielStreams-App")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                connectTimeout = 10000
                readTimeout = 10000
            }

            val responseCode = conn.responseCode
            if (responseCode == 404) {
                return@withContext Result.failure(Exception("Nenhum lançamento (Release) encontrado em github.com/$repo"))
            }
            if (responseCode != 200) {
                return@withContext Result.failure(Exception("Erro ao contactar GitHub (HTTP $responseCode)"))
            }

            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonText)

            val tagName = json.optString("tag_name", "")
            val title = json.optString("name", "Nova Versão")
            val body = json.optString("body", "Melhorias de desempenho e correções.")
            
            // Localizar o ficheiro .apk nos assets
            val assetsArray = json.optJSONArray("assets")
            var downloadUrl: String? = null
            var apkName: String? = null
            var apkSize = 0L

            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url")
                        apkName = name
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (downloadUrl.isNullOrBlank()) {
                return@withContext Result.failure(Exception("A versão mais recente ($tagName) ainda não tem um ficheiro .apk anexado no GitHub."))
            }

            val currentVer = getCurrentVersionName()
            if (isVersionNewer(tagName, currentVer)) {
                Result.success(
                    ReleaseInfo(
                        versionName = tagName.removePrefix("v").removePrefix("V"),
                        releaseTitle = title,
                        changelog = body,
                        downloadUrl = downloadUrl,
                        apkFileName = apkName ?: "DanielStreams_update.apk",
                        apkSize = apkSize
                    )
                )
            } else {
                Result.success(null) // Já está na versão mais recente
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Descarrega o APK com progresso e suporte a redirects do GitHub
     */
    suspend fun downloadApk(
        urlStr: String,
        targetFileName: String = "DanielStreams_update.apk",
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            var currentUrl = urlStr
            var conn: HttpURLConnection
            var redirectCount = 0

            val token = getGitHubToken()
            // Seguir redirecionamentos (301, 302, 307, 308)
            while (true) {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "DanielStreams-App")
                    if (token.isNotBlank()) {
                        if (!currentUrl.contains("objects.githubusercontent.com") && !currentUrl.contains("amazonaws.com")) {
                            setRequestProperty("Authorization", "Bearer $token")
                        }
                    }
                    connectTimeout = 15000
                    readTimeout = 30000
                }

                val status = conn.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308
                ) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (!location.isNullOrBlank() && redirectCount < 5) {
                        currentUrl = location
                        redirectCount++
                        continue
                    }
                }
                break
            }

            val totalSize = conn.contentLength.toLong()
            val destFile = File(context.cacheDir, targetFileName)
            if (destFile.exists()) {
                destFile.delete()
            }

            val input: InputStream = conn.inputStream
            val output = FileOutputStream(destFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var downloaded: Long = 0

            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                downloaded += bytesRead
                val percent = if (totalSize > 0) ((downloaded * 100) / totalSize).toInt() else 0
                onProgress(percent, downloaded, totalSize)
            }

            output.flush()
            output.close()
            input.close()
            conn.disconnect()

            Result.success(destFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Verifica se a app tem permissão para instalar APKs
     */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Abre as definições do Android para conceder permissão de instalação
     */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Inicia o instalador nativo do Android
     */
    fun installApk(apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Compara versões semânticas (ex: "v1.5" > "1.4")
     */
    fun isVersionNewer(remoteVer: String, currentVer: String): Boolean {
        try {
            val cleanRemote = remoteVer.trim().removePrefix("v").removePrefix("V")
            val cleanCurrent = currentVer.trim().removePrefix("v").removePrefix("V")

            val rParts = cleanRemote.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val cParts = cleanCurrent.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }

            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        } catch (_: Exception) {}
        return false
    }
}
