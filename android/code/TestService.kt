package com.devisaacson.snitesterpro

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class TestService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var testJob: Job? = null

    private val SSL_PORTS = setOf(443, 8443, 2096, 2087, 2053, 8883)

    companion object {
        const val CHANNEL_ID = "test_service_channel"
        const val NOTIFICATION_ID = 1

        var isRunning = false
        var currentSni = "aguardando..."
        var currentPort = 0
        var progress = 0.0f
        var testedCount = 0
        var totalToTest = 0
        var isDeepScanning = false
        var startTimeMillis: Long = 0L
        val results = mutableStateListOf<TestResult>()
        val logs = mutableStateListOf<String>()

        private val listeners = mutableSetOf<() -> Unit>()

        var onUpdate: (() -> Unit)? = null
            set(value) {
                if (field != null) listeners.remove(field)
                field = value
                if (value != null) listeners.add(value)
            }

        fun addUpdateListener(listener: () -> Unit) {
            listeners.add(listener)
        }

        fun removeUpdateListener(listener: () -> Unit) {
            listeners.remove(listener)
        }

        fun notifyUpdate() {
            listeners.toList().forEach { it.invoke() }
        }
    }

    private suspend fun addLog(message: String) = withContext(Dispatchers.Main) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logs.add(0, "[$time] $message")
        notifyUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            stopTesting()
            return START_NOT_STICKY
        }

        val sniList = intent?.getStringArrayListExtra("sniList") ?: emptyList<String>()
        val selectedPorts = intent?.getIntegerArrayListExtra("ports") ?: emptyList<Int>()
        val operator = intent?.getStringExtra("operator") ?: "AUTO"
        val isDeepScan = intent?.getBooleanExtra("deepScan", false) ?: false

        startForegroundService()
        startTesting(sniList, selectedPorts, operator, isDeepScan)

        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification("Iniciando testes...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, TestService::class.java).apply { action = "STOP" }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val openResultsIntent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_RESULTS"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(this, 1, openResultsIntent, PendingIntent.FLAG_IMMUTABLE)

        val largeIcon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SNI Tester PRO")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setLargeIcon(largeIcon)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "PARAR", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Canal de Testes SNI", NotificationManager.IMPORTANCE_LOW
            ))
            manager.createNotificationChannel(NotificationChannel(
                "result_channel", "Resultados de Teste", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                lightColor = Color.GREEN
                enableVibration(true)
            })
        }
    }

    private fun startTesting(sniList: List<String>, selectedPorts: List<Int>, operator: String, isDeepScan: Boolean) {
        testJob?.cancel()

        testJob = serviceScope.launch {
            withContext(Dispatchers.Main) {
                testedCount = 0
                progress = 0f
                totalToTest = sniList.size * selectedPorts.size
                isRunning = true
                startTimeMillis = System.currentTimeMillis()
                
                results.clear()
                notifyUpdate()
            }

            addLog("🚀 Iniciando varredura inteligente...")
            addLog("📋 Configuração: ${sniList.size} hosts | ${selectedPorts.size} portas")

            val semaphore = Semaphore(10)
            val jobs = mutableListOf<Job>()

            // FASE 1: Varredura Básica
            for (sni in sniList) {
                for (port in selectedPorts) {
                    if (!isRunning) break

                    val job = launch {
                        semaphore.withPermit {
                            if (!isRunning) return@withPermit

                            withContext(Dispatchers.Main) {
                                currentSni = sni
                                currentPort = port
                                updateNotification("Testando: $sni:$port")
                                notifyUpdate()
                            }

                            try {
                                val resolvedIp = withContext(Dispatchers.IO) {
                                    try {
                                        val addresses = java.net.InetAddress.getAllByName(sni)
                                        val ipv4 = addresses.find { it is java.net.Inet4Address }?.hostAddress
                                        val ipv6 = addresses.find { it is java.net.Inet6Address }?.hostAddress
                                        
                                        when {
                                            ipv4 != null && ipv6 != null -> "ipv4: $ipv4\nipv6: $ipv6"
                                            ipv4 != null -> "ipv4: $ipv4"
                                            ipv6 != null -> "ipv6: $ipv6"
                                            else -> null
                                        }
                                    } catch (e: Exception) { null }
                                }
                                
                                withTimeout(5000) {
                                    val start = System.currentTimeMillis()
                                    val socket = if (port in SSL_PORTS) {
                                        (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket().apply {
                                            soTimeout = 4000
                                            connect(InetSocketAddress(sni, port), 4000)
                                            (this as SSLSocket).startHandshake()
                                        }
                                    } else {
                                        Socket().apply {
                                            soTimeout = 4000
                                            connect(InetSocketAddress(sni, port), 4000)
                                        }
                                    }
                                    val latency = System.currentTimeMillis() - start

                                    withContext(Dispatchers.Main) {
                                        results.add(TestResult(sni = sni, resolvedIp = resolvedIp, port = port, status = "200 OK", latency = latency, operator = operator))
                                    }
                                    addLog("✅ Sucesso: $sni:$port (${latency}ms)")
                                    socket.close()
                                }
                            } catch (e: Exception) {
                                val errStatus = if (e is TimeoutCancellationException || e is SocketTimeoutException) "TIMEOUT" else "FAILED"
                                val resolvedIpErr = withContext(Dispatchers.IO) {
                                    try {
                                        val addresses = java.net.InetAddress.getAllByName(sni)
                                        val ipv4 = addresses.find { it is java.net.Inet4Address }?.hostAddress
                                        val ipv6 = addresses.find { it is java.net.Inet6Address }?.hostAddress
                                        when {
                                            ipv4 != null && ipv6 != null -> "ipv4: $ipv4\nipv6: $ipv6"
                                            ipv4 != null -> "ipv4: $ipv4"
                                            ipv6 != null -> "ipv6: $ipv6"
                                            else -> null
                                        }
                                    } catch (e: Exception) { null }
                                }
                                withContext(Dispatchers.Main) {
                                    results.add(TestResult(sni = sni, resolvedIp = resolvedIpErr, port = port, status = errStatus, latency = 0, operator = operator))
                                }
                                addLog("❌ ${if (errStatus == "TIMEOUT") "Timeout" else "Falha"}: $sni:$port")
                            }

                            withContext(Dispatchers.Main) {
                                testedCount++
                                progress = if (totalToTest > 0) testedCount.toFloat() / totalToTest else 0f
                                notifyUpdate()
                            }
                        }
                    }
                    jobs.add(job)
                }
            }

            jobs.joinAll()

            // FASE 2: Varredura Profunda (Deep Scan)
            if (isDeepScan && isRunning) {
                val functionalSnis = results.filter { it.status == "200 OK" }.toList()
                if (functionalSnis.isNotEmpty()) {
                    addLog("⏳ Aguardando para iniciar fase profunda...")
                    delay(2000)

                    withContext(Dispatchers.Main) {
                        isDeepScanning = true
                        testedCount = 0
                        progress = 0f
                        totalToTest = functionalSnis.size
                        notifyUpdate()
                    }

                    addLog("🔍 Iniciando FASE 2: Validação Semântica Profunda...")

                    functionalSnis.forEach { result ->
                        if (!isRunning) return@forEach

                        withContext(Dispatchers.Main) {
                            currentSni = "[DEEP] ${result.sni}"
                            currentPort = result.port
                            updateNotification("Deep Scan: ${result.sni}")
                            notifyUpdate()
                        }

                        addLog("🧪 Analisando: ${result.sni}...")
                        val deepResult = checkDeepValidation(result.sni, result.port)

                        withContext(Dispatchers.Main) {
                            val listIndex = results.indexOfFirst { it.id == result.id }
                            if (listIndex != -1) {
                                results[listIndex] = results[listIndex].copy(
                                    isDeepVerified = deepResult.isValid,
                                    deepResult = deepResult
                                )
                            }

                            addLog(deepResult.reason)
                            if (deepResult.isValid) {
                                val tunnelStatus = if (deepResult.tunnelWorking) "Tunnel: OK" else "Tunnel: NO"
                                addLog("📊 $tunnelStatus | Data: ${deepResult.bytesReceived / 1024}KB | Speed: ${deepResult.speedKbps}KB/s")
                            }

                            testedCount++
                            progress = if (totalToTest > 0) testedCount.toFloat() / totalToTest else 0f
                            notifyUpdate()
                        }
                    }

                    withContext(Dispatchers.Main) {
                        isDeepScanning = false
                        notifyUpdate()
                    }
                }
            }

            withContext(Dispatchers.Main) {
                val successCount = results.count { it.status == "200 OK" }
                val verifiedCount = results.count { it.isDeepVerified }
                val failCount = results.count { it.status == "FAILED" }
                val timeoutCount = results.count { it.status == "TIMEOUT" }

                showFinalNotification(successCount, failCount, timeoutCount)
                addLog("🏁 Varredura concluída! Ativos: $successCount | Zero Rating: $verifiedCount | Falhas: $failCount | Timeout: $timeoutCount")

                isRunning = false
                notifyUpdate()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun checkDeepValidation(host: String, port: Int): DeepScanResult = withContext(Dispatchers.IO) {
        // Phase A: Hijack & Semantic Detection
        addLog("  ↳ [FASE A] Verificando Sequestro & Conteúdo Semântico...")
        val phaseA = try {
            val socket = connectSocket(host, port, 5000)
            if (socket == null) return@withContext DeepScanResult(false, reason = "❌ Conexão falhou")
            
            if (socket is SSLSocket) {
                val session = socket.session
                val certs = session.peerCertificates
                if (certs.isNotEmpty()) {
                    val x509 = certs[0] as java.security.cert.X509Certificate
                    val issuer = x509.issuerX500Principal.name.lowercase()
                    val bannedIssuers = listOf("fortinet", "mikrotik", "sonicwall", "checkpoint", "palo alto", "watchguard", "barracuda")
                    if (bannedIssuers.any { issuer.contains(it) }) {
                        socket.close()
                        return@withContext DeepScanResult(false, reason = "🚫 Hijack: Firewall detectado ($issuer)")
                    }
                }
            }

            val output = socket.getOutputStream()
            val randomPath = "/zr_probe_${System.currentTimeMillis()}"
            val request = "GET $randomPath HTTP/1.1\r\nHost: $host\r\nUser-Agent: SNI-Tester-PRO\r\nAccept: */*\r\nConnection: close\r\n\r\n"
            output.write(request.toByteArray())
            output.flush()

            val inputStream = socket.getInputStream()
            val reader = inputStream.bufferedReader()
            val statusLine = reader.readLine() ?: ""
            val headers = mutableMapOf<String, String>()
            
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotBlank()) {
                if (line!!.contains(":")) {
                    val key = line!!.substringBefore(":").trim().lowercase()
                    val value = line!!.substringAfter(":").trim()
                    headers[key] = value
                }
            }

            // Análise Semântica do Corpo (Prevenção de Falsos Positivos)
            val bodyBuilder = StringBuilder()
            val buffer = CharArray(1024)
            var charsRead: Int
            var totalRead = 0
            while (reader.read(buffer).also { charsRead = it } != -1 && totalRead < 4096) {
                bodyBuilder.append(buffer, 0, charsRead)
                totalRead += charsRead
            }
            val body = bodyBuilder.toString().lowercase()
            
            socket.close()

            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            val serverHeader = headers["server"]?.lowercase() ?: ""
            val bannedServers = listOf("mikrotik", "squid", "nginx-proxy", "varnish", "bluecoat", "websense")
            
            if (bannedServers.any { serverHeader.contains(it) }) {
                return@withContext DeepScanResult(false, reason = "🚫 Hijack: Proxy detectado ($serverHeader)")
            }

            // Super Inteligência Semântica
            val portalKeywords = listOf("recarga", "saldo", "insuficiente", "captive portal", "login", "comprar dados", "renew", "top up")
            if (portalKeywords.any { body.contains(it) }) {
                return@withContext DeepScanResult(false, reason = "⚠️ Hijack: Portal de Captura detectado")
            }

            when (statusCode) {
                200 -> {
                    // Se deu 200 OK num caminho que NÃO existe, é certamente um Captive Portal
                    DeepScanResult(false, reason = "⚠️ Hijack: 200 OK em caminho inexistente (Mock Page)")
                }
                301, 302, 307, 308 -> {
                    val location = headers["location"] ?: ""
                    if (location.isNotEmpty() && !location.contains(host)) {
                        DeepScanResult(false, reason = "⚠️ Hijack: Redirecionamento para $location")
                    } else {
                        DeepScanResult(true) // Legitimate internal redirect
                    }
                }
                400, 403, 404, 405, 410 -> DeepScanResult(true) // Site real respondendo erro esperado
                in 500..599 -> DeepScanResult(true) // Server error legítimo
                else -> DeepScanResult(false, reason = "❓ Resposta desconhecida: $statusCode")
            }
        } catch (e: Exception) {
            DeepScanResult(false, reason = "❌ Erro na Fase A: ${e.message}")
        }

        if (!phaseA.isValid) return@withContext phaseA

        // Phase B: HTTP CONNECT Tunnel Test
        addLog("  ↳ [FASE B] Testando Túnel HTTP CONNECT...")
        val phaseBTunnelResult = withTimeoutOrNull(6000) {
            try {
                val socket = connectSocket(host, port, 4000)
                if (socket == null) return@withTimeoutOrNull false
                
                val output = socket.getOutputStream()
                val request = "CONNECT connectivitycheck.gstatic.com:443 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com:443\r\nProxy-Connection: keep-alive\r\n\r\n"
                output.write(request.toByteArray())
                output.flush()

                val reader = socket.getInputStream().bufferedReader()
                val lineStatus = reader.readLine() ?: ""
                socket.close()
                lineStatus.contains("200")
            } catch (e: Exception) { false }
        } ?: false

        // Phase C: Real Data Flow Measurement
        addLog("  ↳ [FASE C] Medindo fluxo de dados real...")
        val phaseC = withTimeoutOrNull(10000) {
            try {
                val startTime = System.currentTimeMillis()
                val socket = connectSocket(host, port, 4000)
                if (socket == null) return@withTimeoutOrNull null
                
                val output = socket.getOutputStream()
                val request = "GET / HTTP/1.1\r\nHost: $host\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
                output.write(request.toByteArray())
                output.flush()

                val inputStream = socket.getInputStream()
                val bufferData = ByteArray(4096)
                var totalBytes = 0L
                val maxBytes = 256 * 1024L // 256KB max

                while (totalBytes < maxBytes) {
                    val read = inputStream.read(bufferData)
                    if (read == -1) break
                    totalBytes += read
                }
                val duration = System.currentTimeMillis() - startTime
                socket.close()

                val speed = if (duration > 0) (totalBytes / 1024f) / (duration / 1000f) else 0f
                Triple(totalBytes, speed, totalBytes >= 8192)
            } catch (e: Exception) { null }
        }

        val dataOk = phaseC?.third ?: false
        val bytes = phaseC?.first ?: 0L
        val speed = phaseC?.second ?: 0f

        return@withContext when {
            dataOk -> DeepScanResult(true, bytes, speed, phaseBTunnelResult, "🛡️ DEEP OK: Conexão Real Estabelecida")
            phaseBTunnelResult -> DeepScanResult(false, bytes, speed, true, "⚠️ Túnel OK, mas fluxo de dados bloqueado")
            else -> DeepScanResult(false, bytes, speed, false, "⚠️ Sem fluxo de dados real: ${bytes/1024}KB recebidos")
        }
    }

    private fun connectSocket(host: String, port: Int, timeout: Int): Socket? {
        return try {
            val socket = if (port in SSL_PORTS) {
                (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket()
            } else {
                Socket()
            }
            socket.soTimeout = timeout
            socket.connect(InetSocketAddress(host, port), timeout)
            if (socket is SSLSocket) socket.startHandshake()
            socket
        } catch (e: Exception) {
            null
        }
    }

    private fun showFinalNotification(success: Int, fail: Int, timeout: Int) {
        if (!MainActivity.isAppInForeground) {
            val openIntent = Intent(this, MainActivity::class.java).apply {
                action = "OPEN_RESULTS"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(this, 2, openIntent, PendingIntent.FLAG_IMMUTABLE)

            val largeIcon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

            val notification = NotificationCompat.Builder(this, "result_channel")
                .setContentTitle("Teste Concluído!")
                .setContentText("Ativos: $success | Falhas: $fail | Timeout: $timeout")
                .setSmallIcon(R.drawable.ic_stat_name)
                .setLargeIcon(largeIcon)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(99, notification)
        }
    }

    private fun updateNotification(content: String) {
        (getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun stopTesting() {
        isRunning = false
        testJob?.cancel()
        testJob = null

        serviceScope.launch(Dispatchers.Main) {
            currentSni = "aguardando..."
            currentPort = 0
            progress = 0f
            testedCount = 0
            isDeepScanning = false
            notifyUpdate()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

