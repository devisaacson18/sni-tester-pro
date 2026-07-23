//! scanner.rs — Motor de varredura SNI (tradução de TestService.kt)
//!
//! FASE 1: Varredura básica assíncrona (TCP connect + TLS handshake) com
//!         semáforo de 10 permissões e timeout global de 5s por alvo.
//! FASE 2: Deep Scan — validação semântica anti-hijack (A), túnel CONNECT (B)
//!         e medição de fluxo real de dados (C).

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::Serialize;
use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::sync::Semaphore;
use tokio::task::{AbortHandle, JoinSet};
use tauri::async_runtime::JoinHandle;
use tokio::time::timeout;

pub const SSL_PORTS: [u16; 6] = [443, 8443, 2096, 2087, 2053, 8883];

// ---------------------------------------------------------------------------
// Modelos (nomes camelCase no JSON para casar com o app original)
// ---------------------------------------------------------------------------

pub struct ScanConfig {
    pub snis: Vec<String>,
    pub ports: Vec<u16>,
    pub operator: String,
    pub deep_scan: bool,
    pub concurrency: usize,
}

pub struct ScanHandle {
    running: Arc<AtomicBool>,
    child_tasks: Arc<Mutex<Vec<AbortHandle>>>,
    task: JoinHandle<()>,
}

impl ScanHandle {
    pub fn stop(self) {
        self.running.store(false, Ordering::SeqCst);
        // `abort` da tarefa principal não deve ser a única linha de defesa:
        // as conexões já despachadas rodam em tarefas próprias. Abortá-las
        // explicitamente impede que resultados sejam emitidos após o usuário
        // pressionar Parar.
        for task in self.child_tasks.lock().unwrap().drain(..) {
            task.abort();
        }
        self.task.abort();
    }
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TestResult {
    pub id: String,
    pub sni: String,
    pub resolved_ip: Option<String>,
    pub port: u16,
    pub status: String, // "200 OK" | "TIMEOUT" | "FAILED"
    pub latency: u64,
    pub operator: String,
    pub is_deep_verified: bool,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct StatePayload {
    current_sni: String,
    current_port: u16,
    progress: f32,
    tested: usize,
    total: usize,
    is_running: bool,
    is_deep_scanning: bool,
    success_count: usize,
    verified_count: usize,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DeepPayload {
    id: String,
    is_valid: bool,
    reason: String,
    tunnel_working: bool,
    bytes_received: u64,
    speed_kbps: f32,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct FinishedPayload {
    success: usize,
    verified: usize,
    failed: usize,
    timeout: usize,
}

struct DeepScanResult {
    is_valid: bool,
    reason: String,
    bytes_received: u64,
    speed_kbps: f32,
    tunnel_working: bool,
}

impl DeepScanResult {
    fn invalid(reason: impl Into<String>) -> Self {
        Self { is_valid: false, reason: reason.into(), bytes_received: 0, speed_kbps: 0.0, tunnel_working: false }
    }
    fn valid() -> Self {
        Self { is_valid: true, reason: String::new(), bytes_received: 0, speed_kbps: 0.0, tunnel_working: false }
    }
}

// ---------------------------------------------------------------------------
// Helpers de log / estado
// ---------------------------------------------------------------------------

fn now_millis() -> i64 {
    chrono::Local::now().timestamp_millis()
}

pub(crate) fn emit_log(app: &AppHandle, msg: &str) {
    let ts = chrono::Local::now().format("%H:%M:%S");
    let _ = app.emit("scan-log", format!("[{ts}] {msg}"));
}

fn emit_state(app: &AppHandle, s: StatePayload) {
    let _ = app.emit("scan-state", s);
}

// ---------------------------------------------------------------------------
// Sockets assíncronos de baixo nível
// ---------------------------------------------------------------------------

/// Trait que unifica TcpStream e TlsStream para leitura/escrita genérica.
trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> AsyncIo for T {}

fn to_io<E: std::fmt::Display>(e: E) -> std::io::Error {
    std::io::Error::other(e.to_string())
}

/// Equivalente ao connectSocket() do Kotlin: TCP connect com timeout, e
/// handshake TLS quando a porta está em SSL_PORTS. Retorna também o issuer
/// do certificado (para a detecção de hijack da Fase A).
async fn connect_socket(
    host: &str,
    port: u16,
    timeout_ms: u64,
) -> std::io::Result<(Box<dyn AsyncIo>, Option<String>)> {
    let tcp = timeout(Duration::from_millis(timeout_ms), TcpStream::connect((host, port)))
        .await
        .map_err(|_| std::io::Error::new(std::io::ErrorKind::TimedOut, "connect timeout"))??;
    tcp.set_nodelay(true).ok();

    if SSL_PORTS.contains(&port) {
        let connector = native_tls::TlsConnector::builder().build().map_err(to_io)?;
        let cx = tokio_native_tls::TlsConnector::from(connector);
        let stream = timeout(Duration::from_millis(timeout_ms), cx.connect(host, tcp))
            .await
            .map_err(|_| std::io::Error::new(std::io::ErrorKind::TimedOut, "tls timeout"))?
            .map_err(to_io)?;
        let issuer = tls_issuer(&stream);
        Ok((Box::new(stream), issuer))
    } else {
        Ok((Box::new(tcp), None))
    }
}

/// Extrai o issuer X.509 do peer (equivalente a session.peerCertificates).
fn tls_issuer(stream: &tokio_native_tls::TlsStream<TcpStream>) -> Option<String> {
    let cert = stream.get_ref().peer_certificate().ok()??;
    let der = cert.to_der().ok()?;
    let (_, x509) = x509_parser::parse_x509_certificate(&der).ok()?;
    Some(x509.issuer().to_string().to_lowercase())
}

/// Resolução DNS (InetAddress.getAllByName) — mantém todos os endereços IPv4 e IPv6.
async fn resolve_ips(host: &str) -> Option<String> {
    let addrs: Vec<_> = tokio::net::lookup_host((host, 0)).await.ok()?.collect();
    let mut v4 = Vec::new();
    let mut v6 = Vec::new();
    for addr in addrs {
        let ip = addr.ip().to_string();
        if addr.is_ipv4() && !v4.contains(&ip) {
            v4.push(ip);
        } else if addr.is_ipv6() && !v6.contains(&ip) {
            v6.push(ip);
        }
    }
    let mut lines = Vec::new();
    if !v4.is_empty() { lines.push(format!("ipv4: {}", v4.join(", "))); }
    if !v6.is_empty() { lines.push(format!("ipv6: {}", v6.join(", "))); }
    (!lines.is_empty()).then(|| lines.join("\n"))
}

/// FASE 1 — teste unitário com withTimeout(5000): connect + handshake,
/// medindo a latência. Classifica TIMEOUT vs FAILED como o original.
async fn run_single(sni: &str, port: u16) -> (String, u64) {
    let start = Instant::now();
    match timeout(Duration::from_millis(5000), connect_socket(sni, port, 4000)).await {
        Err(_) => ("TIMEOUT".into(), 0),
        Ok(Err(_)) => ("FAILED".into(), 0),
        Ok(Ok((mut stream, _))) => {
            let latency = start.elapsed().as_millis() as u64;
            let _ = stream.shutdown().await; // socket.close()
            ("200 OK".into(), latency)
        }
    }
}

// ---------------------------------------------------------------------------
// Orquestração da varredura
// ---------------------------------------------------------------------------

pub fn spawn_scan(app: AppHandle, cfg: ScanConfig) -> ScanHandle {
    let running = Arc::new(AtomicBool::new(true));
    let child_tasks = Arc::new(Mutex::new(Vec::new()));
    let flag = running.clone();
    let task = tauri::async_runtime::spawn(run_scan(app, cfg, flag, child_tasks.clone()));
    ScanHandle { running, child_tasks, task }
}

async fn run_scan(
    app: AppHandle,
    cfg: ScanConfig,
    running: Arc<AtomicBool>,
    child_tasks: Arc<Mutex<Vec<AbortHandle>>>,
) {
    let total = cfg.snis.len() * cfg.ports.len();
    let tested = Arc::new(AtomicUsize::new(0));
    let success_count = Arc::new(AtomicUsize::new(0));
    let results: Arc<Mutex<Vec<TestResult>>> = Arc::new(Mutex::new(Vec::new()));

    let _ = app.emit("scan-started", ());
    emit_log(&app, "🚀 Iniciando varredura inteligente...");
    emit_log(&app, &format!("📋 Configuração: {} hosts | {} portas", cfg.snis.len(), cfg.ports.len()));

    // ---- FASE 1: Varredura Básica (Semaphore controlado pela configuração) ----
    let sem = Arc::new(Semaphore::new(cfg.concurrency));
    // JoinSet aborta todas as tarefas filhas quando a varredura é cancelada.
    // JoinHandle solto, usado antes, deixava conexões em andamento continuarem.
    let mut handles = JoinSet::new();

    for sni in &cfg.snis {
        for &port in &cfg.ports {
            if !running.load(Ordering::SeqCst) { break; }
            let Ok(permit) = sem.clone().acquire_owned().await else { break };
            if !running.load(Ordering::SeqCst) { break; }

            let app2 = app.clone();
            let sni2 = sni.clone();
            let operator = cfg.operator.clone();
            let running2 = running.clone();
            let tested2 = tested.clone();
            let succ2 = success_count.clone();
            let results2 = results.clone();

            let abort_handle = handles.spawn(async move {
                let _permit = permit; // libera ao fim da task (withPermit)
                if !running2.load(Ordering::SeqCst) { return; }

                emit_state(&app2, StatePayload {
                    current_sni: sni2.clone(), current_port: port,
                    progress: tested2.load(Ordering::SeqCst) as f32 / total.max(1) as f32,
                    tested: tested2.load(Ordering::SeqCst), total,
                    is_running: true, is_deep_scanning: false,
                    success_count: succ2.load(Ordering::SeqCst), verified_count: 0,
                });

                let resolved_ip = resolve_ips(&sni2).await;
                if !running2.load(Ordering::SeqCst) { return; }
                let (status, latency) = run_single(&sni2, port).await;
                if !running2.load(Ordering::SeqCst) { return; }

                match status.as_str() {
                    "200 OK" => {
                        succ2.fetch_add(1, Ordering::SeqCst);
                        emit_log(&app2, &format!("✅ Sucesso: {sni2}:{port} ({latency}ms)"));
                    }
                    "TIMEOUT" => emit_log(&app2, &format!("❌ Timeout: {sni2}:{port}")),
                    _ => emit_log(&app2, &format!("❌ Falha: {sni2}:{port}")),
                }

                let result = TestResult {
                    id: uuid::Uuid::new_v4().to_string(),
                    sni: sni2, resolved_ip, port, status, latency,
                    operator, is_deep_verified: false,
                };
                results2.lock().unwrap().push(result.clone());
                let _ = app2.emit("scan-result", result);

                let done = tested2.fetch_add(1, Ordering::SeqCst) + 1;
                emit_state(&app2, StatePayload {
                    current_sni: "aguardando...".into(), current_port: 0,
                    progress: done as f32 / total.max(1) as f32,
                    tested: done, total,
                    is_running: true, is_deep_scanning: false,
                    success_count: succ2.load(Ordering::SeqCst), verified_count: 0,
                });
            });
            child_tasks.lock().unwrap().push(abort_handle);
        }
    }
    while handles.join_next().await.is_some() {}
    child_tasks.lock().unwrap().clear();

    // ---- FASE 2: Deep Scan (sequencial, como no forEach original) ----
    let mut verified = 0usize;
    if cfg.deep_scan && running.load(Ordering::SeqCst) {
        let functional: Vec<TestResult> =
            results.lock().unwrap().iter().filter(|r| r.status == "200 OK").cloned().collect();

        if !functional.is_empty() {
            emit_log(&app, "⏳ Aguardando para iniciar fase profunda...");
            tokio::time::sleep(Duration::from_secs(2)).await;
            emit_log(&app, "🔍 Iniciando FASE 2: Validação Semântica Profunda...");

            let dtotal = functional.len();
            for (i, res) in functional.iter().enumerate() {
                if !running.load(Ordering::SeqCst) { break; }

                emit_state(&app, StatePayload {
                    current_sni: format!("[DEEP] {}", res.sni), current_port: res.port,
                    progress: i as f32 / dtotal as f32, tested: i, total: dtotal,
                    is_running: true, is_deep_scanning: true,
                    success_count: success_count.load(Ordering::SeqCst), verified_count: verified,
                });
                emit_log(&app, &format!("🧪 Analisando: {}...", res.sni));

                let deep = check_deep_validation(&app, &res.sni, res.port).await;

                emit_log(&app, &deep.reason);
                if deep.is_valid {
                    verified += 1;
                    let t = if deep.tunnel_working { "Tunnel: OK" } else { "Tunnel: NO" };
                    emit_log(&app, &format!(
                        "📊 {} | Data: {}KB | Speed: {:.1}KB/s",
                        t, deep.bytes_received / 1024, deep.speed_kbps
                    ));
                }

                let _ = app.emit("deep-result", DeepPayload {
                    id: res.id.clone(), is_valid: deep.is_valid, reason: deep.reason,
                    tunnel_working: deep.tunnel_working,
                    bytes_received: deep.bytes_received, speed_kbps: deep.speed_kbps,
                });

                emit_state(&app, StatePayload {
                    current_sni: "aguardando...".into(), current_port: 0,
                    progress: (i + 1) as f32 / dtotal as f32, tested: i + 1, total: dtotal,
                    is_running: true, is_deep_scanning: true,
                    success_count: success_count.load(Ordering::SeqCst), verified_count: verified,
                });
            }
        }
    }

    // ---- Finalização ----
    let list = results.lock().unwrap();
    let success = list.iter().filter(|r| r.status == "200 OK").count();
    let failed = list.iter().filter(|r| r.status == "FAILED").count();
    let timeouts = list.iter().filter(|r| r.status == "TIMEOUT").count();
    drop(list);

    emit_log(&app, &format!(
        "🏁 Varredura concluída! Ativos: {success} | Zero Rating: {verified} | Falhas: {failed} | Timeout: {timeouts}"
    ));
    emit_state(&app, StatePayload {
        current_sni: "aguardando...".into(), current_port: 0, progress: 0.0,
        tested: 0, total, is_running: false, is_deep_scanning: false,
        success_count: success, verified_count: verified,
    });
    let _ = app.emit("scan-finished", FinishedPayload { success, verified, failed, timeout: timeouts });
}

// ---------------------------------------------------------------------------
// Deep Scan — Fases A, B e C
// ---------------------------------------------------------------------------

async fn check_deep_validation(app: &AppHandle, host: &str, port: u16) -> DeepScanResult {
    // Phase A: Hijack & Semantic Detection
    emit_log(app, "  ↳ [FASE A] Verificando Sequestro & Conteúdo Semântico...");
    let phase_a = match phase_a(host, port).await {
        Ok(v) => v,
        Err(e) => DeepScanResult::invalid(format!("❌ Erro na Fase A: {e}")),
    };
    if !phase_a.is_valid { return phase_a; }

    // Phase B: HTTP CONNECT Tunnel Test
    emit_log(app, "  ↳ [FASE B] Testando Túnel HTTP CONNECT...");
    let tunnel = matches!(timeout(Duration::from_millis(6000), phase_b(host, port)).await, Ok(true));

    // Phase C: Real Data Flow Measurement
    emit_log(app, "  ↳ [FASE C] Medindo fluxo de dados real...");
    let (bytes, speed, data_ok) = match timeout(Duration::from_millis(10000), phase_c(host, port)).await {
        Ok(Some(t)) => t,
        _ => (0, 0.0, false),
    };

    match (data_ok, tunnel) {
        (true, _) => DeepScanResult {
            is_valid: true, reason: "🛡️ DEEP OK: Conexão Real Estabelecida".into(),
            bytes_received: bytes, speed_kbps: speed, tunnel_working: tunnel,
        },
        (false, true) => DeepScanResult {
            is_valid: false, reason: "⚠️ Túnel OK, mas fluxo de dados bloqueado".into(),
            bytes_received: bytes, speed_kbps: speed, tunnel_working: true,
        },
        _ => DeepScanResult::invalid(format!("⚠️ Sem fluxo de dados real: {}KB recebidos", bytes / 1024)),
    }
}

async fn phase_a(host: &str, port: u16) -> std::io::Result<DeepScanResult> {
    const BANNED_ISSUERS: [&str; 7] =
        ["fortinet", "mikrotik", "sonicwall", "checkpoint", "palo alto", "watchguard", "barracuda"];
    const BANNED_SERVERS: [&str; 6] =
        ["mikrotik", "squid", "nginx-proxy", "varnish", "bluecoat", "websense"];
    const PORTAL_KEYWORDS: [&str; 8] =
        ["recarga", "saldo", "insuficiente", "captive portal", "login", "comprar dados", "renew", "top up"];

    let (mut stream, issuer) = match connect_socket(host, port, 5000).await {
        Ok(v) => v,
        Err(_) => return Ok(DeepScanResult::invalid("❌ Conexão falhou")),
    };

    // Detecção de firewall middlebox pelo issuer do certificado
    if let Some(iss) = &issuer {
        if BANNED_ISSUERS.iter().any(|b| iss.contains(b)) {
            return Ok(DeepScanResult::invalid(format!("🚫 Hijack: Firewall detectado ({iss})")));
        }
    }

    let random_path = format!("/zr_probe_{}", now_millis());
    let request = format!(
        "GET {random_path} HTTP/1.1\r\nHost: {host}\r\nUser-Agent: SNI-Tester-PRO\r\nAccept: */*\r\nConnection: close\r\n\r\n"
    );
    stream.write_all(request.as_bytes()).await?;
    stream.flush().await?;

    let mut reader = BufReader::new(stream);
    let mut status_line = String::new();
    reader.read_line(&mut status_line).await?;

    let mut headers: HashMap<String, String> = HashMap::new();
    loop {
        let mut line = String::new();
        let n = reader.read_line(&mut line).await?;
        if n == 0 || line.trim().is_empty() { break; }
        if let Some((k, v)) = line.split_once(':') {
            headers.insert(k.trim().to_lowercase(), v.trim().to_string());
        }
    }

    // Análise semântica do corpo (máx. 4KB)
    let mut body = String::new();
    let mut buf = [0u8; 1024];
    let mut total = 0usize;
    while total < 4096 {
        match reader.read(&mut buf).await {
            Ok(0) | Err(_) => break,
            Ok(n) => { body.push_str(&String::from_utf8_lossy(&buf[..n])); total += n; }
        }
    }
    let body = body.to_lowercase();

    let status_code: u16 = status_line
        .split_whitespace().nth(1).and_then(|s| s.parse().ok()).unwrap_or(0);
    let server = headers.get("server").cloned().unwrap_or_default().to_lowercase();

    if BANNED_SERVERS.iter().any(|b| server.contains(b)) {
        return Ok(DeepScanResult::invalid(format!("🚫 Hijack: Proxy detectado ({server})")));
    }
    if PORTAL_KEYWORDS.iter().any(|k| body.contains(k)) {
        return Ok(DeepScanResult::invalid("⚠️ Hijack: Portal de Captura detectado"));
    }

    Ok(match status_code {
        // 200 OK num caminho que NÃO existe = Captive Portal (Mock Page)
        200 => DeepScanResult::invalid("⚠️ Hijack: 200 OK em caminho inexistente (Mock Page)"),
        301 | 302 | 307 | 308 => {
            let location = headers.get("location").cloned().unwrap_or_default();
            if !location.is_empty() && !location.contains(host) {
                DeepScanResult::invalid(format!("⚠️ Hijack: Redirecionamento para {location}"))
            } else {
                DeepScanResult::valid() // redirect interno legítimo
            }
        }
        400 | 403 | 404 | 405 | 410 => DeepScanResult::valid(), // erro esperado de site real
        500..=599 => DeepScanResult::valid(),                  // server error legítimo
        _ => DeepScanResult::invalid(format!("❓ Resposta desconhecida: {status_code}")),
    })
}

async fn phase_b(host: &str, port: u16) -> bool {
    let Ok((mut stream, _)) = connect_socket(host, port, 4000).await else { return false };
    let req = "CONNECT connectivitycheck.gstatic.com:443 HTTP/1.1\r\n\
               Host: connectivitycheck.gstatic.com:443\r\n\
               Proxy-Connection: keep-alive\r\n\r\n";
    if stream.write_all(req.as_bytes()).await.is_err() { return false; }
    let _ = stream.flush().await;
    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    reader.read_line(&mut line).await.is_ok() && line.contains("200")
}

async fn phase_c(host: &str, port: u16) -> Option<(u64, f32, bool)> {
    let start = Instant::now();
    let (mut stream, _) = connect_socket(host, port, 4000).await.ok()?;
    let req = format!("GET / HTTP/1.1\r\nHost: {host}\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n");
    stream.write_all(req.as_bytes()).await.ok()?;
    stream.flush().await.ok()?;

    let mut total: u64 = 0;
    let max: u64 = 256 * 1024; // 256KB max, como no original
    let mut buf = [0u8; 4096];
    while total < max {
        match stream.read(&mut buf).await {
            Ok(0) => break,
            Ok(n) => total += n as u64,
            Err(_) => break,
        }
    }
    let dur = start.elapsed().as_secs_f32();
    let speed = if dur > 0.0 { (total as f32 / 1024.0) / dur } else { 0.0 };
    Some((total, speed, total >= 8192))
}
