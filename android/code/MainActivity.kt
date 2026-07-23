package com.devisaacson.snitesterpro

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.devisaacson.snitesterpro.ui.theme.SNITesterPROTheme
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextPaint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.random.Random

// --- DATA MODELS ---
enum class Screen { Principal, Settings, Results, About, Credits, Logs }

data class TestSession(
    val id: String = UUID.randomUUID().toString(),
    val date: Long = System.currentTimeMillis(),
    val operator: String,
    val results: List<TestResult>
)

class MainActivity : ComponentActivity() {
    companion object {
        var isAppInForeground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Verificação de Root
        if (isDeviceRooted()) {
            Toast.makeText(this, "⚠️ Segurança: Este app não pode ser executado em dispositivos com ROOT.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            
            // Solicitação de Permissões
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { /* Permissões tratadas pelo sistema */ }

            LaunchedEffect(Unit) {
                val permissions = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.READ_PHONE_STATE)
                }
                if (permissions.isNotEmpty()) {
                    permissionLauncher.launch(permissions.toTypedArray())
                }
            }

            val sharedPref = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
            val systemThemeInDark = isSystemInDarkTheme()
            
            var themeMode by remember {
                mutableStateOf(sharedPref.getString("theme_mode", "SYSTEM") ?: "SYSTEM")
            }

            val isDarkMode = when (themeMode) {
                "LIGHT" -> false
                "DARK" -> true
                else -> systemThemeInDark
            }

            // Atualiza o estilo das barras do sistema sempre que o tema mudar
            LaunchedEffect(isDarkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        AndroidColor.TRANSPARENT,
                        AndroidColor.TRANSPARENT,
                    ) { isDarkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        AndroidColor.TRANSPARENT,
                        AndroidColor.TRANSPARENT,
                    ) { isDarkMode }
                )
            }
            
            // Navegação via Notificação
            val initialScreen = if (intent?.action == "OPEN_RESULTS") Screen.Results else Screen.Principal

            SNITesterPROTheme(darkTheme = isDarkMode) {
                // Fundo Tahoe Premium (Gradiente Dinâmico Profundo)
                val tahoeBackground = Brush.linearGradient(
                    colors = if (isDarkMode) {
                        listOf(Color(0xFF0F172A), Color(0xFF1E1B4B), Color(0xFF312E81))
                    } else {
                        listOf(Color(0xFFF8FAFC), Color(0xFFE2E8F0), Color(0xFFCBD5E1))
                    },
                    start = Offset(0f, 0f),
                    end = Offset(1000f, 1000f)
                )

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent // Transparência para o gradiente brilhar
                ) {
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .background(tahoeBackground)
                    ) {
                        // Círculos de luz suaves para dar profundidade ao vidro
                        LightOrbs()

                        var showTour by remember { 
                            mutableStateOf(!sharedPref.getBoolean("hide_tour", false)) 
                        }
                        
                        val currentVersion = 11
                        var showWhatsNew by remember {
                            mutableStateOf(sharedPref.getInt("last_version_seen", 0) < currentVersion)
                        }

                        // Efeito Blur Premium mais suave para Dialogs e Menu
                        val isOverlayActive = showTour || showWhatsNew
                        val backgroundBlur by animateDpAsState(
                            targetValue = if (isOverlayActive) 12.dp else 0.dp,
                            animationSpec = tween(600),
                            label = "dialogBlur"
                        )

                        Box(modifier = Modifier.fillMaxSize()) {
                            // Conteúdo Principal com Blur Aplicado
                            Box(modifier = Modifier
                                .fillMaxSize()
                                .blur(backgroundBlur)
                            ) {
                                MainApp(
                                    startScreen = initialScreen,
                                    isDarkMode = isDarkMode,
                                    themeMode = themeMode,
                                    onThemeChange = { newMode ->
                                        themeMode = newMode
                                        sharedPref.edit { putString("theme_mode", newMode) }
                                    }
                                )
                            }

                            // Camada de Transparência Extra
                            if (isOverlayActive) {
                                Box(modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.3f))
                                )
                            }

                            // Dialogs em cima do blur
                            if (showTour) {
                                AppTourDialog(
                                    onDismiss = { hideForever ->
                                        if (hideForever) {
                                            sharedPref.edit { putBoolean("hide_tour", true) }
                                        }
                                        showTour = false
                                    }
                                )
                            } else if (showWhatsNew) {
                                WhatsNewDialog(
                                    onDismiss = {
                                        sharedPref.edit { putInt("last_version_seen", currentVersion) }
                                        showWhatsNew = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isAppInForeground = true
    }

    override fun onStop() {
        super.onStop()
        isAppInForeground = false
    }

    private fun isDeviceRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        for (path in paths) {
            if (File(path).exists()) return true
        }
        
        val buildTags = Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) return true
        
        // Verificação extra via execução de comando
        return try {
            val process = Runtime.getRuntime().exec("which su")
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readLine() != null
            }
        } catch (ignored: Throwable) {
            false
        }
    }
}

@Composable
fun MainApp(startScreen: Screen = Screen.Principal, isDarkMode: Boolean, themeMode: String, onThemeChange: (String) -> Unit) {
    var currentScreen by remember { mutableStateOf(startScreen) }
    val context = LocalContext.current
    val settingsPref = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    
    // Lógica do botão Voltar do Android
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    val activity = remember(context) { 
        var c = context
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return@remember c
            c = c.baseContext
        }
        null
    }

    // Lógica para detectar operadora a partir dos dados móveis em tempo real
    var detectedOperator by remember { mutableStateOf("Buscando...") }
    
    DisposableEffect(context) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        fun updateOperator() {
            detectedOperator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
                if (dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    tm.createForSubscriptionId(dataSubId).networkOperatorName.ifBlank { "Desconhecida" }
                } else {
                    tm.networkOperatorName.ifBlank { "Desconhecida" }
                }
            } else {
                tm.networkOperatorName.ifBlank { "Desconhecida" }
            }
        }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { updateOperator() }
            override fun onLost(network: android.net.Network) { updateOperator() }
            override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: android.net.NetworkCapabilities) { updateOperator() }
        }

        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        updateOperator()

        onDispose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }

    // Estados de Teste (Movidos para cá para serem globais)
    var isTesting by remember { mutableStateOf(TestService.isRunning) }
    val scope = rememberCoroutineScope()

    // Manter tela ligada durante o teste (Global)
    DisposableEffect(isTesting) {
        if (isTesting) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Resultados (Sessões) persistidos
    val sessions = remember { 
        mutableStateListOf<TestSession>().apply {
            addAll(loadSessions(context))
        }
    }

    // Persistência automática ao mudar a lista
    LaunchedEffect(sessions.size, sessions.sumOf { it.results.size }) {
        saveSessions(context, sessions)
    }

    BackHandler {
        if (currentScreen != Screen.Principal) {
            currentScreen = Screen.Principal
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) {
                activity?.finish()
            } else {
                lastBackPressTime = currentTime
                Toast.makeText(context, "Aperte outra vez para fechar o app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Configurações do Teste persistidas
    var sniList by remember { 
        mutableStateOf(
            settingsPref.getString("sni_list", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        ) 
    }
    var selectedPorts by remember { 
        mutableStateOf(
            settingsPref.getString("ports", "443, 80")?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: listOf(443, 80)
        ) 
    }
    var isDeepScanEnabled by remember {
        mutableStateOf(settingsPref.getBoolean("deep_scan", false))
    }

    // Salva configurações automaticamente ao mudar
    LaunchedEffect(sniList, selectedPorts, isDeepScanEnabled) {
        settingsPref.edit {
            putString("sni_list", sniList.joinToString(","))
            putString("ports", selectedPorts.joinToString(","))
            putBoolean("deep_scan", isDeepScanEnabled)
        }
    }

    // Monitoramento Global do Service e Limpeza Automática
    DisposableEffect(Unit) {
        val listener = {
            val wasTesting = isTesting
            val nowTesting = TestService.isRunning
            isTesting = nowTesting
            
            // Se o teste acabou agora, cria a sessão e agenda a limpeza de 6s
            if (wasTesting && !nowTesting && TestService.results.isNotEmpty()) {
                val newSession = TestSession(
                    operator = detectedOperator,
                    results = ArrayList(TestService.results)
                )
                sessions.add(0, newSession)

                // Limpeza Global após 5 segundos
                scope.launch {
                    delay(5000)
                    if (!TestService.isRunning) {
                        TestService.currentSni = "aguardando..."
                        TestService.currentPort = 0
                        TestService.progress = 0f
                        TestService.testedCount = 0
                        TestService.results.clear()
                        TestService.notifyUpdate()
                        android.widget.Toast.makeText(context, "Teste salvo em Resultados", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                
                // Salva os dados imediatamente após o fim do teste
                saveSessions(context, sessions)
            }
        }
        TestService.addUpdateListener(listener)
        onDispose {
            TestService.removeUpdateListener(listener)
        }
    }

    Crossfade(targetState = currentScreen, label = "screenTransition") { screen ->
        when (screen) {
            Screen.Principal -> {
                MainScreen(
                    isDarkMode = isDarkMode,
                    themeMode = themeMode,
                    onThemeChange = onThemeChange,
                    onNavigate = { currentScreen = it },
                    operator = detectedOperator,
                    sniList = sniList,
                    selectedPorts = selectedPorts,
                    isDeepScanEnabled = isDeepScanEnabled,
                    onDeepScanToggle = { isDeepScanEnabled = it }
                )
            }
            Screen.Settings -> {
                SettingsScreen(
                    sniList = sniList,
                    onSniListChange = { sniList = it },
                    selectedPorts = selectedPorts,
                    onPortsChange = { selectedPorts = it },
                    isDeepScanEnabled = isDeepScanEnabled,
                    onDeepScanToggle = { isDeepScanEnabled = it },
                    onBack = { currentScreen = Screen.Principal }
                )
            }
            Screen.Results -> {
                ResultsScreen(sessions = sessions, onBack = { currentScreen = Screen.Principal })
            }
            Screen.About -> {
                AboutScreen(onBack = { currentScreen = Screen.Principal })
            }
            Screen.Credits -> {
                CreditsScreen(onBack = { currentScreen = Screen.Principal })
            }
            Screen.Logs -> {
                LogsScreen(onBack = { currentScreen = Screen.Principal })
            }
        }
    }
}

@Composable
fun ThemeToggle(
    themeMode: String,
    onThemeChange: (String) -> Unit
) {
    val modes = listOf("SYSTEM", "LIGHT", "DARK")
    val nextMode = when (themeMode) {
        "SYSTEM" -> "LIGHT"
        "LIGHT" -> "DARK"
        else -> "SYSTEM"
    }

    Surface(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable { onThemeChange(nextMode) },
        color = Color.Transparent,
        shape = CircleShape
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when (themeMode) {
                    "LIGHT" -> Icons.Default.LightMode
                    "DARK" -> Icons.Default.DarkMode
                    else -> Icons.Default.Brightness6
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
fun MainScreen(
    isDarkMode: Boolean, 
    themeMode: String,
    onThemeChange: (String) -> Unit,
    onNavigate: (Screen) -> Unit,
    operator: String,
    sniList: List<String>,
    selectedPorts: List<Int>,
    isDeepScanEnabled: Boolean,
    onDeepScanToggle: (Boolean) -> Unit
) {
    var isMenuOpen by remember { mutableStateOf(false) }
    
    // Estados sincronizados com o Service
    var currentSni by remember { mutableStateOf(TestService.currentSni) }
    var currentPort by remember { mutableIntStateOf(TestService.currentPort) }
    var progress by remember { mutableFloatStateOf(TestService.progress) }
    var testedCount by remember { mutableIntStateOf(TestService.testedCount) }
    var isTesting by remember { mutableStateOf(TestService.isRunning) }
    var isDeepScanning by remember { mutableStateOf(TestService.isDeepScanning) }
    var elapsedTime by remember { mutableStateOf("00:00") }

    val context = LocalContext.current
    val totalToTest = remember(sniList, selectedPorts) { sniList.size * selectedPorts.size }

    // Efeito para sincronizar a UI local com o Service
    DisposableEffect(Unit) {
        val listener = {
            currentSni = TestService.currentSni
            currentPort = TestService.currentPort
            progress = TestService.progress
            testedCount = TestService.testedCount
            isTesting = TestService.isRunning
            isDeepScanning = TestService.isDeepScanning
        }
        TestService.addUpdateListener(listener)
        onDispose {
            TestService.removeUpdateListener(listener)
        }
    }

    // Cronômetro Global
    LaunchedEffect(isTesting) {
        if (isTesting) {
            while (isTesting) {
                val diff = System.currentTimeMillis() - TestService.startTimeMillis
                val mins = (diff / 1000) / 60
                val secs = (diff / 1000) % 60
                elapsedTime = String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
                delay(1000)
                isTesting = TestService.isRunning
            }
        }
    }

    val scanColor = when {
        isDeepScanning -> Color(0xFFDAA520) 
        else -> Color(0xFF38BDF8) // Tahoe Blue
    }

    val infiniteTransition = rememberInfiniteTransition(label = "tahoe")
    
    val tips = listOf(
        "Deslize para os lados para navegar entre as abas.",
        "Importe arquivos CSV ou XLSX com listas de SNIs.",
        "O Deep Scan valida contra falsos positivos de portais.",
        "Os resultados são salvos automaticamente no histórico.",
        "Use o ícone de lupa nos resultados para busca rápida.",
        "Pressione e segure um SNI para copiá-lo.",
        "Ative o modo escuro para realçar o efeito Tahoe Glass."
    )
    var currentTipIndex by remember { mutableIntStateOf(Random.nextInt(tips.size)) }
    LaunchedEffect(Unit) {
        while(true) {
            delay(8000)
            currentTipIndex = (currentTipIndex + 1) % tips.size
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            var totalDrag = 0f
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (totalDrag < -150) onNavigate(Screen.Settings)
                    else if (totalDrag > 150) onNavigate(Screen.Results)
                    totalDrag = 0f
                },
                onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(130.dp)) // Aumentado para desgrudar do header
            
            // 1. Operadora Glass Badge
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = (if (isDarkMode) Color.White else Color.Black).copy(alpha = 0.05f),
                    shape = CircleShape,
                    border = BorderStroke(0.5.dp, (if (isDarkMode) Color.White else Color.Black).copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val glowAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
                            label = "glow"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .graphicsLayer { alpha = if (isTesting) glowAlpha else 0.5f }
                                .background(if (isTesting) Color(0xFF4ADE80) else Color.Gray, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = operator.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // 2. Botão START "Radar Glass Sphere"
            Box(
                modifier = Modifier.fillMaxWidth().weight(1.5f),
                contentAlignment = Alignment.Center
            ) {
                RadarButton(
                    isTesting = isTesting,
                    scanColor = scanColor,
                    onClick = {
                        if (isTesting) {
                            context.startService(Intent(context, TestService::class.java).apply { action = "STOP" })
                        } else {
                            val intent = Intent(context, TestService::class.java).apply {
                                putStringArrayListExtra("sniList", ArrayList(sniList))
                                putIntegerArrayListExtra("ports", ArrayList(selectedPorts))
                                putExtra("operator", operator)
                                putExtra("deepScan", isDeepScanEnabled)
                            }
                            context.startForegroundService(intent)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            // 3. Progresso Glass Panel
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("VARREDURA ATIVA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                        Text(currentSni.uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Black, maxLines = 1)
                    }
                    Surface(color = scanColor.copy(alpha = 0.15f), shape = CircleShape) {
                        Text("PORTA $currentPort", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = scanColor)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                    color = scanColor,
                    trackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
                    strokeCap = StrokeCap.Round
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "${(progress * 100).toInt()}%", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Black)
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Row {
                            MetricBadge(count = TestService.results.count { it.status == "200 OK" }, icon = Icons.Default.Check, color = Color(0xFF4ADE80))
                            Spacer(modifier = Modifier.width(10.dp))
                            MetricBadge(count = TestService.results.count { it.isDeepVerified }, icon = Icons.Default.Stars, color = Color(0xFFDAA520))
                        }
                        Text(text = "$testedCount / $totalToTest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                Row(modifier = Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(elapsedTime, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            // 4. Dicas Glass Section
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                color = (if (isDarkMode) Color.White else Color.Black).copy(alpha = 0.03f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.5.dp, (if (isDarkMode) Color.White else Color.Black).copy(alpha = 0.1f))
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lightbulb, contentDescription = null, tint = Color(0xFFFDE047).copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    AnimatedContent(targetState = tips[currentTipIndex], label = "tip") { tip ->
                        Text(tip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), lineHeight = 16.sp)
                    }
                }
            }
        }

        // --- TOP BAR TAHOE ---
        Surface(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
            color = (if (isDarkMode) Color.White else Color.Black).copy(alpha = 0.08f),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(0.5.dp, (if (isDarkMode) Color.White else Color.Black).copy(alpha = 0.15f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ThemeToggle(themeMode = themeMode, onThemeChange = onThemeChange)
                Text("SNI TESTER PRO", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                IconButton(onClick = { isMenuOpen = !isMenuOpen }) {
                    Icon(if (isMenuOpen) Icons.Default.Close else Icons.Default.Menu, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
                }
            }
        }

        // MENU OVERLAY
        if (isMenuOpen) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { isMenuOpen = false }) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 90.dp, end = 20.dp)
                        .width(220.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.95f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Configurações" to Icons.Default.Settings,
                            "Resultados" to Icons.AutoMirrored.Filled.Assignment,
                            "Logs" to Icons.AutoMirrored.Filled.ReceiptLong,
                            "Sobre" to Icons.Default.Info,
                            "Créditos" to Icons.Default.Star
                        ).forEach { (label, icon) ->
                            MenuItem(
                                text = label,
                                icon = icon,
                                onClick = { 
                                    onNavigate(when(label) {
                                        "Configurações" -> Screen.Settings
                                        "Resultados" -> Screen.Results
                                        "Logs" -> Screen.Logs
                                        "Sobre" -> Screen.About
                                        else -> Screen.Credits
                                    })
                                    isMenuOpen = false 
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricBadge(count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = "$count", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.1f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            content = content
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    sniList: List<String>,
    onSniListChange: (List<String>) -> Unit,
    selectedPorts: List<Int>,
    onPortsChange: (List<Int>) -> Unit,
    isDeepScanEnabled: Boolean,
    onDeepScanToggle: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var manualSni by remember { mutableStateOf("") }
    var portsText by remember { mutableStateOf(selectedPorts.joinToString(", ")) }

    var showFullListDialog by remember { mutableStateOf(false) }
    
    val blurRadius by animateDpAsState(
        targetValue = if (showFullListDialog) 12.dp else 0.dp,
        animationSpec = tween(400),
        label = "blur"
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val allExtracted = mutableListOf<String>()
                uris.forEach { uri ->
                    val content = withContext(Dispatchers.IO) { extractTextFromUri(context, uri) }
                    allExtracted.addAll(parseSnis(content))
                }
                val combined = (sniList + allExtracted)
                    .map { it.lowercase().trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                onSniListChange(combined)
                Toast.makeText(context, "Importados ${allExtracted.distinct().size} novos SNIs", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .blur(blurRadius)
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Voltar", tint = Color.White)
            }
            Text(
                "Configurações", 
                style = MaterialTheme.typography.titleLarge, 
                fontWeight = FontWeight.Black,
                color = Color.White
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                SettingsSection(title = "Modo de Varredura") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeepScanToggle(!isDeepScanEnabled) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Deep Scan", style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Validação anti-spoofing e túnel.", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                        }
                        Switch(
                            checked = isDeepScanEnabled,
                            onCheckedChange = onDeepScanToggle,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFDAA520),
                                checkedTrackColor = Color(0xFFDAA520).copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "Lista de SNIs (${sniList.size})") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = manualSni,
                            onValueChange = { manualSni = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Adicionar Manual", color = Color.White.copy(alpha = 0.6f)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    if (manualSni.isNotBlank()) {
                                        val newSnis = manualSni.split("\n", ",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                                        onSniListChange((sniList + newSnis).distinct())
                                        manualSni = ""
                                    }
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF38BDF8))
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.White.copy(alpha = 0.4f),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                            )
                        )
                        
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f))
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Importar Arquivo", color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        if (sniList.isNotEmpty()) {
                            TextButton(
                                onClick = { showFullListDialog = true },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("GERENCIAR LISTA COMPLETA", color = Color(0xFF38BDF8), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "Portas") {
                    OutlinedTextField(
                        value = portsText,
                        onValueChange = { 
                            portsText = it
                            onPortsChange(it.split(",").mapNotNull { p -> p.trim().toIntOrNull() })
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White.copy(alpha = 0.4f),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.5f),
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.5.sp
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.05f),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}


@Composable
fun ResultsScreen(
    sessions: MutableList<TestSession>, 
    onBack: () -> Unit
) {
    var selectedSession by remember { mutableStateOf<TestSession?>(null) }
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var detailSearchQuery by remember { mutableStateOf("") }
    var selectedStatusFilter by remember { mutableStateOf<String?>(null) }
    var isSearchExpanded by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedSession != null) {
        selectedSession = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (selectedSession != null) selectedSession = null else onBack() }) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Voltar", tint = Color.White)
            }
            Text(
                text = if (selectedSession == null) "Resultados" else "Detalhes", 
                style = MaterialTheme.typography.headlineSmall, 
                color = Color.White,
                fontWeight = FontWeight.Black
            )
        }

        if (selectedSession == null) {
            if (sessions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhum histórico.", color = Color.White.copy(alpha = 0.5f))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(sessions) { session ->
                        SessionItem(session, dateFormat.format(Date(session.date)), { selectedSession = session }, { sessions.remove(session) })
                    }
                }
            }
        } else {
            // Detalhes Tahoe
            val session = selectedSession!!
            val filteredResults = session.results.filter { res ->
                val match = if (selectedStatusFilter == null) true else res.status == selectedStatusFilter
                match && (detailSearchQuery.isBlank() || res.sni.contains(detailSearchQuery, ignoreCase = true))
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("OPERADORA: ${session.operator}", color = Color.White, fontWeight = FontWeight.Bold)
                                Text(dateFormat.format(Date(session.date)), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
                            }
                            Row {
                                IconButton(onClick = {
                                    // Logic to export text/json/pdf would go here, linking to the generate functions
                                }) {
                                    Icon(Icons.Default.Share, contentDescription = "Exportar", tint = Color.White)
                                }
                            }
                        }
                    }
                }
                items(filteredResults) { result ->
                    ResultItem(result, onDelete = {
                        val updatedResults = session.results.toMutableList()
                        updatedResults.remove(result)
                        val idx = sessions.indexOf(session)
                        if (idx != -1) {
                            sessions[idx] = session.copy(results = updatedResults)
                            selectedSession = sessions[idx]
                        }
                    })
                }
            }
        }
    }
}

fun generateExportText(session: TestSession, filteredResults: List<TestResult>): String {
    val content = StringBuilder()
    content.append("SNI TESTER PRO - RESULTADOS\n")
    content.append("Operadora: ${session.operator}\n")
    content.append("Data: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(session.date))}\n")
    content.append("----------------------------\n\n")
    filteredResults.forEach {
        content.append("SNI: ${it.sni}\n")
        if (it.resolvedIp != null) content.append("IP: ${it.resolvedIp}\n")
        content.append("Porta: ${it.port}\n")
        content.append("Latência: ${it.latency}ms\n")
        content.append("Status: ${it.status}\n")
        if (it.isDeepVerified) content.append("VERIFICAÇÃO: ZERO RATING ENCONTRADO\n")
        content.append("----------------------------\n")
    }
    return content.toString()
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Voltar", tint = Color.White)
            }
            Text("Sobre", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Black)
        }

        Spacer(modifier = Modifier.height(24.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                buildAnnotatedString {
                    append("O ")
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("SNI Tester PRO")
                    }
                    append(" é uma ferramenta avançada para análise de vulnerabilidades de rede e testes de SNI.")
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Ideal para profissionais que buscam identificar hosts com Zero Rating ou brechas em filtros DPI.",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Versão: 4.0.4 PRO\nEstética Tahoe Glassmorphism Ativa.",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
fun CreditsScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Voltar", tint = Color.White)
            }
            Text("Créditos", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Black)
        }

        Spacer(modifier = Modifier.height(40.dp))

        Text("Desenvolvido por:", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelLarge)
        Text(
            text = "devisaacson",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            color = Color(0xFF38BDF8),
            modifier = Modifier.clickable { uriHandler.openUri("https://devisaacson.site") }
        )

        Spacer(modifier = Modifier.height(48.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White.copy(alpha = 0.05f),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Text(
                "Um agradecimento especial às IAs Gemini e Claude pela ajuda fundamental na evolução deste projeto.",
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}


@Composable
fun SessionItem(session: TestSession, dateStr: String, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sessão $dateStr", color = Color.White, fontWeight = FontWeight.ExtraBold)
                Text("Operadora: ${session.operator}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF38BDF8))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    MetricBadge(count = session.results.count { it.status == "200 OK" }, icon = Icons.Default.Check, color = Color(0xFF4ADE80))
                    Spacer(modifier = Modifier.width(10.dp))
                    MetricBadge(count = session.results.count { it.isDeepVerified }, icon = Icons.Default.Stars, color = Color(0xFFDAA520))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = Color.White.copy(alpha = 0.3f))
            }
        }
    }
}

@Composable
fun ResultItem(result: TestResult, onDelete: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val isSuccess = result.status == "200 OK"
    val isTimeout = result.status == "TIMEOUT"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isSuccess) {
                    val clip = ClipData.newPlainText("SNI", result.sni)
                    clipboardManager.setPrimaryClip(clip)
                    Toast.makeText(context, "SNI copiado!", Toast.LENGTH_SHORT).show()
                }
            },
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.sni, 
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (result.resolvedIp != null) {
                    val annotatedIp = buildAnnotatedString {
                        val lines = result.resolvedIp.split("\n")
                        lines.forEachIndexed { index, line ->
                            if (line.contains(":")) {
                                val label = line.substringBefore(":") + ": "
                                val value = line.substringAfter(":").trim()
                                withStyle(style = SpanStyle(color = Color(0xFF38BDF8))) {
                                    append(label)
                                }
                                withStyle(style = SpanStyle(color = Color.White.copy(alpha = 0.8f))) {
                                    append(value)
                                }
                            } else {
                                append(line)
                            }
                            if (index < lines.size - 1) append("\n")
                        }
                    }
                    Text(
                        text = annotatedIp,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                if (result.isDeepVerified) {
                    Surface(
                        color = Color(0xFFDAA520).copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Color(0xFFDAA520), modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "ZERO RATING ENCONTRADO",
                                color = Color(0xFFDAA520),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
                Text(
                    text = when {
                        isSuccess -> "Porta: ${result.port} | Latência: ${result.latency}ms"
                        isTimeout -> "Timeout: Sem resposta (Porta ${result.port})"
                        else -> "Falha na conexão (Porta ${result.port})"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = result.status, 
                    color = when {
                        isSuccess -> Color(0xFF4ADE80)
                        isTimeout -> Color(0xFFFF9800)
                        else -> Color(0xFFF87171)
                    },
                    fontWeight = FontWeight.Black, 
                    modifier = Modifier.padding(end = 8.dp)
                )
                IconButton(onClick = { onDelete() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Deletar", tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun LogsScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Voltar", tint = Color.White)
            }
            Text("Logs do Sistema", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Black)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.05f),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
        ) {
            if (TestService.logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhum log registrado.", color = Color.White.copy(alpha = 0.3f))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(TestService.logs) { log ->
                        val color = when {
                            log.contains("Sucesso") -> Color(0xFF4ADE80)
                            log.contains("Falha") -> Color(0xFFF87171)
                            else -> Color.White.copy(alpha = 0.6f)
                        }
                        Text(log, style = MaterialTheme.typography.labelSmall, color = color, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
fun MenuItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, hasActivity: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        color = Color.White.copy(alpha = 0.05f),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            if (hasActivity) {
                Spacer(modifier = Modifier.weight(1f))
                Box(modifier = Modifier.size(8.dp).background(Color(0xFF38BDF8), CircleShape))
            }
        }
    }
}

// --- UTILS ---
fun loadSessions(context: Context): List<TestSession> {
    val sessions = mutableListOf<TestSession>()
    try {
        val sharedPref = context.getSharedPreferences("data", Context.MODE_PRIVATE)
        val jsonStr = sharedPref.getString("sessions", null) ?: return emptyList()
        val jsonArray = JSONArray(jsonStr)
        for (i in 0 until jsonArray.length()) {
            val sessionJson = jsonArray.getJSONObject(i)
            val resultsArray = sessionJson.getJSONArray("results")
            val results = mutableListOf<TestResult>()
            for (j in 0 until resultsArray.length()) {
                val resultJson = resultsArray.getJSONObject(j)
                results.add(TestResult(
                    id = resultJson.optString("id", UUID.randomUUID().toString()),
                    sni = resultJson.getString("sni"),
                    resolvedIp = resultJson.optString("resolvedIp", null),
                    port = resultJson.getInt("port"),
                    status = resultJson.getString("status"),
                    latency = resultJson.getLong("latency"),
                    date = resultJson.optLong("date", System.currentTimeMillis()),
                    operator = resultJson.getString("operator"),
                    isDeepVerified = resultJson.optBoolean("isDeepVerified", false)
                ))
            }
            sessions.add(TestSession(
                id = sessionJson.optString("id", UUID.randomUUID().toString()),
                date = sessionJson.getLong("date"),
                operator = sessionJson.getString("operator"),
                results = results
            ))
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return sessions
}

fun saveSessions(context: Context, sessions: List<TestSession>) {
    try {
        val sharedPref = context.getSharedPreferences("data", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (session in sessions) {
            val sessionJson = JSONObject()
            sessionJson.put("id", session.id)
            sessionJson.put("date", session.date)
            sessionJson.put("operator", session.operator)
            
            val resultsArray = JSONArray()
            for (res in session.results) {
                val resJson = JSONObject()
                resJson.put("id", res.id)
                resJson.put("sni", res.sni)
                resJson.put("resolvedIp", res.resolvedIp)
                resJson.put("port", res.port)
                resJson.put("status", res.status)
                resJson.put("latency", res.latency)
                resJson.put("date", res.date)
                resJson.put("operator", res.operator)
                resJson.put("isDeepVerified", res.isDeepVerified)
                resultsArray.put(resJson)
            }
            sessionJson.put("results", resultsArray)
            jsonArray.put(sessionJson)
        }
        sharedPref.edit { putString("sessions", jsonArray.toString()) }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun generateExportJson(session: TestSession, filteredResults: List<TestResult>): String {
    try {
        val root = JSONObject()
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        root.put("operator", session.operator)
        root.put("timestamp", session.date)
        root.put("date_formatted", dateFormat.format(Date(session.date)))
        val results = JSONArray()
        filteredResults.forEach { res ->
            val item = JSONObject()
            item.put("sni", res.sni)
            item.put("resolvedIp", res.resolvedIp)
            item.put("port", res.port)
            item.put("status", res.status)
            item.put("latency", res.latency)
            item.put("isDeepVerified", res.isDeepVerified)
            results.put(item)
        }
        root.put("results", results)
        return root.toString(4)
    } catch (e: Exception) {
        return "{}"
    }
}

fun extractTextFromUri(context: Context, uri: Uri): String {
    val contentResolver = context.contentResolver
    val mimeType = contentResolver.getType(uri) ?: ""
    val fileName = getFileName(context, uri).lowercase()
    
    return try {
        when {
            mimeType == "application/pdf" || fileName.endsWith(".pdf") -> {
                // Extrator de baixo nível aprimorado para PDFs (suporta streams comprimidos)
                val stringBuilder = StringBuilder()
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    // Tenta ISO_8859_1 para capturar bytes de texto puro
                    val content = String(bytes, Charsets.ISO_8859_1)
                    
                    // Estratégia 1: Tenta inflar streams comprimidos (FlateDecode)
                    // PDFs modernos (incluindo os exportados pelo app) comprimem o texto
                    val streamRegex = Regex("""(?s)stream\r?\n(.*?)\r?\nendstream""")
                    streamRegex.findAll(content).forEach { match ->
                        try {
                            val streamData = match.groupValues[1].toByteArray(Charsets.ISO_8859_1)
                            val inflater = java.util.zip.Inflater()
                            inflater.setInput(streamData)
                            val buffer = ByteArray(4096)
                            while (!inflater.finished()) {
                                val count = inflater.inflate(buffer)
                                if (count > 0) {
                                    stringBuilder.append(String(buffer, 0, count, Charsets.ISO_8859_1))
                                } else break
                            }
                            inflater.end()
                        } catch (ignored: Exception) {}
                    }

                    // Estratégia 2: Blocos de texto PDF clássicos não comprimidos (BT...ET)
                    val textBlocks = Regex("""BT(.*?)ET""", RegexOption.DOT_MATCHES_ALL).findAll(content)
                    for (block in textBlocks) {
                        // Extrai conteúdo dentro de parênteses (strings PDF)
                        Regex("""\((.*?)\)""").findAll(block.value).forEach { s ->
                            stringBuilder.append(s.groupValues[1]).append(" ")
                        }
                        // Extrai conteúdo de strings hexadecimais <...>
                        Regex("""<([0-9A-Fa-f]+)>""").findAll(block.value).forEach { h ->
                            try {
                                val hex = h.groupValues[1]
                                val decoded = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
                                stringBuilder.append(decoded).append(" ")
                            } catch (ignored: Exception) {}
                        }
                    }
                    
                    // Estratégia 3: Se o texto extraído for muito curto, varre os bytes por domínios diretamente
                    if (stringBuilder.length < 5) {
                        val domainPattern = """(?i)\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}\b""".toRegex()
                        domainPattern.findAll(content).forEach { match ->
                            stringBuilder.append(match.value).append(" ")
                        }
                    }
                }
                stringBuilder.toString()
            }
            mimeType.contains("word") || fileName.endsWith(".docx") -> {
                val stringBuilder = StringBuilder()
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val zipInputStream = ZipInputStream(inputStream)
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        if (entry.name == "word/document.xml") {
                            val content = BufferedReader(InputStreamReader(zipInputStream)).readText()
                            // Extrai texto de dentro das tags <w:t> do XML do Word
                            val textPattern = Regex("<w:t[^>]*>(.*?)</w:t>")
                            textPattern.findAll(content).forEach { match ->
                                stringBuilder.append(match.groupValues[1]).append(" ")
                            }
                        }
                        entry = zipInputStream.nextEntry
                    }
                }
                stringBuilder.toString()
            }
            mimeType.contains("spreadsheet") || fileName.endsWith(".xlsx") -> {
                val stringBuilder = StringBuilder()
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val zipInputStream = ZipInputStream(inputStream)
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        // XLSX armazena textos repetidos em sharedStrings.xml e valores diretos nos sheets
                        if (entry.name == "xl/sharedStrings.xml" || entry.name.startsWith("xl/worksheets/sheet")) {
                            val content = BufferedReader(InputStreamReader(zipInputStream)).readText()
                            // Extrai texto de tags <t> (shared strings) ou <v> (valores diretos)
                            val textPattern = Regex("<[tv][^>]*>(.*?)</[tv]>")
                            textPattern.findAll(content).forEach { match ->
                                stringBuilder.append(match.groupValues[1]).append(" ")
                            }
                        }
                        entry = zipInputStream.nextEntry
                    }
                }
                stringBuilder.toString()
            }
            mimeType == "text/csv" || fileName.endsWith(".csv") -> {
                val stringBuilder = StringBuilder()
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        reader.forEachLine { line ->
                            stringBuilder.append(line.replace(",", " ")).append("\n")
                        }
                    }
                }
                stringBuilder.toString()
            }
            else -> {
                val stringBuilder = StringBuilder()
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            stringBuilder.append(line).append("\n")
                            line = reader.readLine()
                        }
                    }
                }
                stringBuilder.toString()
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        ""
    }
}

private fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) result = result?.substring(cut + 1)
    }
    return result ?: ""
}

fun parseSnis(content: String): List<String> {
    val cleanContent = content
        .replace("\\n", " ")
        .replace("\n", " ")
        .replace("\r", " ")
        .replace("\t", " ")

    val domainRegex = """(?i)\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}\b""".toRegex()
    return domainRegex.findAll(cleanContent)
        .map { it.value.lowercase().trim() }
        .filter { domain ->
            val parts = domain.split(".")
            parts.last().any { it.isLetter() } && parts.size >= 2
        }
        .distinct()
        .toList()
}

fun generatePdfFile(session: TestSession, filteredResults: List<TestResult>, outputStream: OutputStream) {
    val pdfDocument = PdfDocument()
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    
    val textPaint = TextPaint().apply {
        textSize = 12f
        color = android.graphics.Color.BLACK
    }
    
    val titlePaint = TextPaint().apply {
        textSize = 18f
        isFakeBoldText = true
        color = android.graphics.Color.BLACK
    }

    val margin = 50
    val pageWidth = 595 // A4 roughly
    val pageHeight = 842
    var y = 60f
    var pageNumber = 1

    var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
    var page = pdfDocument.startPage(pageInfo)
    var canvas = page.canvas

    // Cabeçalho
    canvas.drawText("SNI TESTER PRO - RELATORIO", margin.toFloat(), y, titlePaint)
    y += 40f
    
    canvas.drawText("Operadora: ${session.operator}", margin.toFloat(), y, textPaint)
    y += 20f
    canvas.drawText("Data: ${dateFormat.format(Date(session.date))}", margin.toFloat(), y, textPaint)
    y += 20f
    canvas.drawText("Total de Itens no Relatório: ${filteredResults.size}", margin.toFloat(), y, textPaint)
    y += 20f
    canvas.drawLine(margin.toFloat(), y, (pageWidth - margin).toFloat(), y, Paint().apply { strokeWidth = 1f })
    y += 30f

    filteredResults.forEach { result ->
        if (y > pageHeight - margin) {
            pdfDocument.finishPage(page)
            pageNumber++
            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            page = pdfDocument.startPage(pageInfo)
            canvas = page.canvas
            y = 60f
        }
        
        val statusText = if (result.isDeepVerified) "ZERO RATING" else result.status
        val line = "SNI: ${result.sni} | Porta: ${result.port} | Lat: ${result.latency}ms | Status: $statusText"
        canvas.drawText(line, margin.toFloat(), y, textPaint)
        y += 20f
    }

    pdfDocument.finishPage(page)
    try {
        pdfDocument.writeTo(outputStream)
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        pdfDocument.close()
    }
}

@Composable
@Preview(showBackground = true, name = "Dark Mode")
fun MainScreenDarkPreview() {
    SNITesterPROTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            MainScreen(
                isDarkMode = true, 
                themeMode = "DARK",
                onThemeChange = {},
                onNavigate = {}, 
                operator = "UNITEL", 
                sniList = emptyList(), 
                selectedPorts = listOf(443),
                isDeepScanEnabled = false,
                onDeepScanToggle = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "Light Mode")
@Composable
fun MainScreenLightPreview() {
    SNITesterPROTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            MainScreen(
                isDarkMode = false, 
                themeMode = "LIGHT",
                onThemeChange = {},
                onNavigate = {}, 
                operator = "UNITEL", 
                sniList = emptyList(), 
                selectedPorts = listOf(443),
                isDeepScanEnabled = false,
                onDeepScanToggle = {}
            )
        }
    }
}

@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A).copy(alpha = 0.9f),
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp)),
        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFFDAA520), modifier = Modifier.size(40.dp)) },
        title = { Text(text = "SNI Tester PRO", color = Color.White, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                WhatsNewItem("💎", "Design Tahoe Glass", "Interface premium inspirada em vidro com profundidade e orbs dinâmicos.")
                WhatsNewItem("🧬", "IA Extração 2.0", "Extração inteligente de qualquer texto, suportando PDF, CSV e Excel.")
                WhatsNewItem("🚀", "Performance Atômica", "Código otimizado via R8 Full Mode para máxima fluidez.")
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                shape = CircleShape
            ) {
                Text("EXPLORAR", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun WhatsNewItem(emoji: String, title: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(text = emoji, fontSize = 20.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = Color.Gray, lineHeight = 14.sp)
        }
    }
}

@Composable
fun AppTourDialog(onDismiss: (Boolean) -> Unit) {
    var currentStep by remember { mutableIntStateOf(0) }
    var hideForever by remember { mutableStateOf(false) }

    val tourSteps = listOf(
        Triple("Bem-vindo!", "SNI Tester PRO evoluiu para o design Tahoe Glassmorphism.", Icons.Default.AutoAwesome),
        Triple("Importação", "Arraste e solte arquivos PDF, CSV ou XLSX para extrair hosts.", Icons.Default.FileUpload),
        Triple("Controle", "Toque na esfera de vidro para iniciar ou parar a varredura.", Icons.Default.RocketLaunch),
        Triple("Resultados", "Analise hosts ativos com IPs IPv4 e IPv6 detalhados.", Icons.Default.CheckCircle)
    )

    AlertDialog(
        onDismissRequest = { onDismiss(hideForever) },
        containerColor = Color(0xFF0F172A).copy(alpha = 0.9f),
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp)),
        icon = { Icon(tourSteps[currentStep].third, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(40.dp)) },
        title = { Text(text = tourSteps[currentStep].first, color = Color.White, fontWeight = FontWeight.Black) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = tourSteps[currentStep].second,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = hideForever, 
                        onCheckedChange = { hideForever = it },
                        colors = CheckboxDefaults.colors(checkmarkColor = Color.White, checkedColor = Color(0xFF38BDF8))
                    )
                    Text(text = "Não exibir novamente", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (currentStep < tourSteps.size - 1) currentStep++
                    else onDismiss(hideForever)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                shape = CircleShape
            ) {
                Text(if (currentStep < tourSteps.size - 1) "PRÓXIMO" else "FINALIZAR", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun LightOrbs() {
    val infiniteTransition = rememberInfiniteTransition(label = "orbs")
    
    val orb1X by infiniteTransition.animateFloat(
        initialValue = -50f, targetValue = 50f,
        animationSpec = infiniteRepeatable(tween(10000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "orb1X"
    )
    val orb1Y by infiniteTransition.animateFloat(
        initialValue = -50f, targetValue = 150f,
        animationSpec = infiniteRepeatable(tween(12000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "orb1Y"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset(x = orb1X.dp, y = orb1Y.dp)
                .size(450.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF312E81).copy(alpha = 0.4f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 100.dp, y = 50.dp)
                .size(500.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF1E1B4B).copy(alpha = 0.3f), Color.Transparent)
                    )
                )
        )
    }
}

@Composable
fun RadarButton(
    isTesting: Boolean,
    scanColor: Color,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Alvos aleatórios (pontos de SNI detectados no radar)
    val randomTargets = remember(isTesting) {
        if (!isTesting) emptyList<Pair<Float, Float>>() else List(6) {
            val angle = Random.nextFloat() * 360f
            val dist = 0.2f + Random.nextFloat() * 0.7f
            angle to dist
        }
    }

    Box(
        modifier = Modifier
            .size(200.dp)
            .padding(10.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.width / 2

            // 1. Fundo e Círculos de Grade
            drawCircle(
                color = Color.White.copy(alpha = 0.05f),
                radius = radius,
                center = center
            )
            
            for (i in 1..6) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.1f),
                    radius = radius * (i / 6f),
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
                )
            }

            // 2. Linhas Cruzadas e Marcas de Grau
            for (angle in 0 until 360 step 30) {
                val angleRad = Math.toRadians(angle.toDouble())
                val startX = center.x + (radius * 0.9f) * Math.cos(angleRad).toFloat()
                val startY = center.y + (radius * 0.9f) * Math.sin(angleRad).toFloat()
                val endX = center.x + radius * Math.cos(angleRad).toFloat()
                val endY = center.y + radius * Math.sin(angleRad).toFloat()
                
                drawLine(
                    color = Color.White.copy(alpha = 0.2f),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 1f
                )
            }

            // 3. Desenhar Alvos Aleatórios
            if (isTesting) {
                randomTargets.forEach { (angle, dist) ->
                    val targetAngleRad = Math.toRadians(angle.toDouble())
                    val tx = center.x + (radius * dist * Math.cos(targetAngleRad)).toFloat()
                    val ty = center.y + (radius * dist * Math.sin(targetAngleRad)).toFloat()
                    
                    // Brilho do alvo conforme o sweep passa (efeito premium)
                    val angleDiff = (rotation - angle + 360f) % 360f
                    val targetAlpha = if (angleDiff < 45f) (1f - angleDiff / 45f) * 0.8f else 0.1f
                    
                    if (targetAlpha > 0.1f) {
                        drawCircle(
                            color = scanColor.copy(alpha = targetAlpha),
                            radius = 4f,
                            center = Offset(tx, ty)
                        )
                        drawCircle(
                            color = scanColor.copy(alpha = targetAlpha * 0.3f),
                            radius = 12f,
                            center = Offset(tx, ty)
                        )
                    }
                }
            }

            // 4. O Sweep do Radar (Animação)
            if (isTesting) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.2f to scanColor.copy(alpha = 0.5f),
                        0.4f to Color.Transparent,
                        center = center
                    ),
                    startAngle = rotation - 40f,
                    sweepAngle = 40f,
                    useCenter = true,
                    size = size,
                    topLeft = Offset.Zero
                )
                
                // Ponta brilhante do sweep
                val angleRad = Math.toRadians(rotation.toDouble())
                val sweepEndX = center.x + radius * Math.cos(angleRad).toFloat()
                val sweepEndY = center.y + radius * Math.sin(angleRad).toFloat()
                
                drawLine(
                    color = scanColor,
                    start = center,
                    end = Offset(sweepEndX, sweepEndY),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }
            
            // 5. Borda Exterior
            drawCircle(
                color = Color.White.copy(alpha = 0.2f),
                radius = radius,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
            )
        }

        // 6. Texto Central Minimalista
        Text(
            text = if (isTesting) "STOP" else "START",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
    }
}

