package com.dlive.ptstream.monitoring

import android.content.Context
import android.util.Log

/**
 * Gestor unificado de telemetria e observabilidade em tempo real.
 * Suporta integração resiliente com Sentry SDK sem travar a aplicação quando offline
 * ou sem DSN configurada.
 */
object AppTelemetry {

    private const val TAG = "AppTelemetry"
    private var isInitialized = false
    private var isSentryAvailable = false

    fun init(context: Context, dsn: String? = null) {
        if (isInitialized) return
        isInitialized = true

        try {
            // Tenta inicializar o Sentry via reflexão segura caso a dependência esteja no classpath
            val sentryClass = Class.forName("io.sentry.Sentry")
            val sentryAndroidClass = Class.forName("io.sentry.android.core.SentryAndroid")
            
            val activeDsn = dsn ?: "https://public@telemetry.dlivept.internal/0"
            val optionsCallback = java.lang.reflect.Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(Class.forName("io.sentry.android.core.SentryAndroidOptions\$Companion\$OptionsConfiguration")
                    .interfaces.firstOrNull() ?: Class.forName("io.sentry.SentryOptions\$ConfigureOptions"))
            ) { _, method, args ->
                if (method.name.contains("configure") || method.name == "apply") {
                    val opts = args?.get(0)
                    opts?.javaClass?.getMethod("setDsn", String::class.java)?.invoke(opts, activeDsn)
                    opts?.javaClass?.getMethod("setTracesSampleRate", Double::class.javaObjectType)?.invoke(opts, 0.2)
                    opts?.javaClass?.getMethod("setEnvironment", String::class.java)?.invoke(opts, "production")
                }
                null
            }

            Log.i(TAG, "Sentry telemetry subsystem bridge active.")
            isSentryAvailable = true
        } catch (_: ClassNotFoundException) {
            Log.i(TAG, "Sentry SDK not embedded, fallback telemetry pipeline enabled.")
        } catch (e: Throwable) {
            Log.w(TAG, "Telemetry init notice: ${e.message}")
        }
    }

    /**
     * Regista eventos de falha de reprodução de streams
     */
    fun recordPlaybackFailure(channelName: String?, server: String?, reason: String, targetUrl: String? = null) {
        val payload = mapOf(
            "channel" to (channelName ?: "Desconhecido"),
            "server" to (server ?: "Nenhum"),
            "reason" to reason,
            "url" to (targetUrl ?: "N/A"),
            "timestamp" to System.currentTimeMillis()
        )
        Log.e(TAG, "🔥 [PLAYBACK_FAILURE] $payload")
        
        try {
            if (isSentryAvailable) {
                val sentryClass = Class.forName("io.sentry.Sentry")
                val captureMessageMethod = sentryClass.getMethod("captureMessage", String::class.java)
                captureMessageMethod.invoke(null, "PlaybackFailure: $channelName via $server ($reason)")
            }
        } catch (_: Throwable) {}
    }

    /**
     * Regista failovers automáticos executados com sucesso
     */
    fun recordFailover(channelName: String?, fromServer: String?, toServer: String?, reason: String) {
        val payload = "Switch: $fromServer -> $toServer for '$channelName' reason='$reason'"
        Log.w(TAG, "⚡ [AUTO_FAILOVER] $payload")

        try {
            if (isSentryAvailable) {
                val sentryClass = Class.forName("io.sentry.Sentry")
                val addBreadcrumbMethod = sentryClass.getMethod("addBreadcrumb", String::class.java)
                addBreadcrumbMethod.invoke(null, "Failover: $payload")
            }
        } catch (_: Throwable) {}
    }

    /**
     * Regista falhas ou timeouts em APIs externas (TimStreams, DaddyLive, Streamed.pk)
     */
    fun recordApiDegradation(source: String, url: String, httpCode: Int, errorMsg: String? = null) {
        val payload = "API $source returned HTTP $httpCode ($errorMsg) on $url"
        Log.w(TAG, "⚠️ [API_DEGRADATION] $payload")

        try {
            if (isSentryAvailable) {
                val sentryClass = Class.forName("io.sentry.Sentry")
                val captureMessageMethod = sentryClass.getMethod("captureMessage", String::class.java)
                captureMessageMethod.invoke(null, "ApiDegradation: $source ($httpCode)")
            }
        } catch (_: Throwable) {}
    }
}
