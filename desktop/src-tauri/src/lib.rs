//! lib.rs — Tauri Commands + gerenciamento de estado da varredura
//! (substitui o Service + Intent actions do Android)

mod extractor;
mod scanner;

use scanner::{ScanConfig, ScanHandle};
use crate::scanner::emit_log;
use std::sync::Mutex;
use tauri::Manager;

pub struct AppState {
    scan: Mutex<Option<ScanHandle>>,
}

#[tauri::command]
fn app_icon_data_url() -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let bytes = include_bytes!("../icons/logo-header.png");
    let mut encoded = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let a = chunk[0];
        let b = *chunk.get(1).unwrap_or(&0);
        let c = *chunk.get(2).unwrap_or(&0);
        encoded.push(TABLE[(a >> 2) as usize] as char);
        encoded.push(TABLE[(((a & 0b0000_0011) << 4) | (b >> 4)) as usize] as char);
        encoded.push(if chunk.len() > 1 {
            TABLE[(((b & 0b0000_1111) << 2) | (c >> 6)) as usize] as char
        } else {
            '='
        });
        encoded.push(if chunk.len() > 2 { TABLE[(c & 0b0011_1111) as usize] as char } else { '=' });
    }
    format!("data:image/png;base64,{encoded}")
}

/// Abre links HTTP(S) no navegador padrão do sistema, sem depender do WebView.
#[tauri::command]
fn open_external_url(url: String) -> Result<(), String> {
    if !url.starts_with("https://") && !url.starts_with("http://") {
        return Err("Somente URLs HTTP(S) são permitidas".into());
    }
    #[cfg(target_os = "windows")]
    let mut command = {
        let mut command = std::process::Command::new("cmd");
        command.args(["/C", "start", "", &url]);
        command
    };
    #[cfg(target_os = "macos")]
    let mut command = {
        let mut command = std::process::Command::new("open");
        command.arg(&url);
        command
    };
    #[cfg(all(unix, not(target_os = "macos")))]
    let mut command = {
        let mut command = std::process::Command::new("xdg-open");
        command.arg(&url);
        command
    };
    command.spawn().map(|_| ()).map_err(|error| error.to_string())
}

/// START — equivalente ao startForegroundService(intent) com extras.
#[tauri::command]
fn start_scan(
    app: tauri::AppHandle,
    state: tauri::State<AppState>,
    snis: Vec<String>,
    ports: Vec<u16>,
    operator: String,
    deep_scan: bool,
    concurrency: Option<usize>,
) -> Result<(), String> {
    let mut guard = state.scan.lock().map_err(|e| e.to_string())?;
    if guard.is_some() {
        return Err("Varredura já em andamento".into());
    }
    if snis.is_empty() || ports.is_empty() {
        return Err("Informe ao menos um SNI e uma porta".into());
    }
    let conc = concurrency.unwrap_or(50).clamp(50, 200);
    let handle = scanner::spawn_scan(app, ScanConfig { snis, ports, operator, deep_scan, concurrency: conc });
    *guard = Some(handle);
    Ok(())
}

/// STOP — equivalente ao Intent action "STOP".
#[tauri::command]
fn stop_scan(state: tauri::State<AppState>) {
    if let Some(h) = state.scan.lock().unwrap().take() {
        h.stop();
    }
}

/// Libera a sessão finalizada para que uma nova varredura possa ser iniciada.
/// É chamado pelo frontend ao receber `scan-finished`.
#[tauri::command]
fn complete_scan(state: tauri::State<AppState>) {
    let _ = state.scan.lock().map(|mut scan| scan.take());
}

#[tauri::command]
fn import_files(app: tauri::AppHandle, paths: Vec<String>) -> Result<Vec<String>, String> {
    let mut set = std::collections::HashSet::new();
    let mut out = Vec::new();
    for p in paths {
        match extractor::extract_snis_from_path(&p) {
            Ok(list) => {
                for s in list {
                    let s2 = s.trim().to_lowercase();
                    if s2.is_empty() { continue; }
                    if set.insert(s2.clone()) {
                        out.push(s2);
                    }
                }
            }
            Err(e) => {
                emit_log(&app, &format!("Falha ao importar {p}: {e}"));
            }
        }
    }
    Ok(out)
}

/// Persiste o histórico de sessões em app_data_dir/sessions.json
/// (substitui o SharedPreferences "data"/"sessions").
#[tauri::command]
fn save_sessions(app: tauri::AppHandle, sessions: serde_json::Value) -> Result<(), String> {
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("sessions.json"), serde_json::to_string_pretty(&sessions).unwrap())
        .map_err(|e| e.to_string())
}

#[tauri::command]
fn load_sessions(app: tauri::AppHandle) -> Result<serde_json::Value, String> {
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    let file = dir.join("sessions.json");
    if !file.exists() {
        return Ok(serde_json::json!([]));
    }
    let text = std::fs::read_to_string(file).map_err(|e| e.to_string())?;
    serde_json::from_str(&text).map_err(|e| e.to_string())
}

/// Exportação de resultados com data/hora, SNI, porta e todos os IPs resolvidos.
#[tauri::command]
fn export_results(path: String, format: String, results: serde_json::Value) -> Result<(), String> {
    let exported_at = chrono::Local::now().to_rfc3339();
    let valid: Vec<serde_json::Value> = results.as_array().into_iter().flatten()
        .filter(|row| row.get("status").and_then(|v| v.as_str()) == Some("200 OK"))
        .map(|row| serde_json::json!({
            "sni": row.get("sni").and_then(|v| v.as_str()).unwrap_or(""),
            "port": row.get("port").and_then(|v| v.as_u64()),
            "ip": row.get("resolvedIp").and_then(|v| v.as_str()).unwrap_or("não resolvido"),
            "latencyMs": row.get("latency").and_then(|v| v.as_u64()),
        }))
        .collect();

    let output: Vec<u8> = match format.as_str() {
        "json" => serde_json::to_string_pretty(&serde_json::json!({
            "exportedAt": exported_at,
            "validResults": valid,
        }))
            .map_err(|e| e.to_string())?.into_bytes(),
        "pdf" => build_pdf(&exported_at, &valid),
        _ => build_text_export(&exported_at, &valid).into_bytes(),
    };
    std::fs::write(path, output).map_err(|e| e.to_string())
}

fn build_text_export(exported_at: &str, results: &[serde_json::Value]) -> String {
    let mut text = format!(
        "SNI Tester PRO - Resultados válidos\nExportado em: {exported_at}\n\nSNI | PORTA | IP\n"
    );
    for result in results {
        let sni = result["sni"].as_str().unwrap_or("");
        let port = result["port"].as_u64().map_or_else(|| "-".to_string(), |p| p.to_string());
        let ip = result["ip"].as_str().unwrap_or("não resolvido").replace('\n', "; ");
        text.push_str(&format!("{sni} | {port} | {ip}\n"));
    }
    text
}

/// Relatório PDF autocontido, com tabela paginada e sem dependências externas.
fn build_pdf(exported_at: &str, results: &[serde_json::Value]) -> Vec<u8> {
    #[derive(Clone)]
    struct ReportRow {
        sni: Vec<String>,
        port: String,
        ip: Vec<String>,
    }

    fn escape(value: &str) -> String {
        value.replace('\\', "\\\\").replace('(', "\\(").replace(')', "\\)")
    }
    fn wrap(value: &str, max_chars: usize) -> Vec<String> {
        let mut lines = Vec::new();
        let mut line = String::new();
        for word in value.split_whitespace() {
            let extra = usize::from(!line.is_empty());
            if !line.is_empty() && line.len() + extra + word.len() > max_chars {
                lines.push(line);
                line = String::new();
            }
            if !line.is_empty() {
                line.push(' ');
            }
            line.push_str(word);
        }
        if !line.is_empty() {
            lines.push(line);
        }
        if lines.is_empty() {
            lines.push("-".into());
        }
        lines
    }

    let rows: Vec<ReportRow> = results
        .iter()
        .map(|result| ReportRow {
            sni: wrap(result["sni"].as_str().unwrap_or("-"), 31),
            port: result["port"]
                .as_u64()
                .map_or_else(|| "-".to_string(), |port| port.to_string()),
            ip: wrap(
                &result["ip"]
                    .as_str()
                    .unwrap_or("nao resolvido")
                    .replace('\n', " | ")
                    .replace(',', ", "),
                48,
            ),
        })
        .collect();

    // Uma página tem espaço para aproximadamente 38 linhas de tabela.
    let mut pages: Vec<Vec<ReportRow>> = vec![Vec::new()];
    let mut used_height = 0usize;
    for row in rows {
        let height = row.sni.len().max(row.ip.len()) * 13 + 10;
        if used_height + height > 490 && !pages.last().is_some_and(Vec::is_empty) {
            pages.push(Vec::new());
            used_height = 0;
        }
        used_height += height;
        pages.last_mut().expect("page exists").push(row);
    }

    let total_pages = pages.len();
    let streams: Vec<String> = pages
        .iter()
        .enumerate()
        .map(|(page_index, page_rows)| {
            let mut stream = String::from("q\n");
            // Cabeçalho
            stream.push_str("0.04 0.12 0.20 rg 0 750 595 92 re f\n");
            stream.push_str("0.13 0.75 0.90 rg 50 750 5 92 re f\n");
            stream.push_str("BT /F2 22 Tf 70 803 Td 1 1 1 rg (SNI Tester PRO) Tj ET\n");
            stream.push_str("BT /F1 10 Tf 70 785 Td 0.70 0.82 0.90 rg (RELATORIO DE VALIDACAO SNI) Tj ET\n");
            stream.push_str(&format!(
                "BT /F1 9 Tf 50 724 Td 0.25 0.32 0.40 rg (Exportado em: {}) Tj ET\n",
                escape(exported_at)
            ));
            stream.push_str(&format!(
                "0.93 0.97 1 rg 50 680 495 30 re f\nBT /F2 10 Tf 64 691 Td 0.04 0.20 0.30 rg (RESULTADOS VALIDOS: {}) Tj ET\n",
                results.len()
            ));
            // Cabeçalho da tabela
            stream.push_str("0.08 0.25 0.38 rg 50 645 495 22 re f\n");
            stream.push_str("BT /F2 9 Tf 62 653 Td 1 1 1 rg (SNI) Tj 190 0 Td (PORTA) Tj 85 0 Td (IP RESOLVIDO) Tj ET\n");

            let mut y = 645i32;
            for (index, row) in page_rows.iter().enumerate() {
                let lines = row.sni.len().max(row.ip.len());
                let height = (lines * 13 + 10) as i32;
                y -= height;
                let shade = if index % 2 == 0 { "0.97 0.99 1" } else { "0.92 0.96 0.99" };
                stream.push_str(&format!("{shade} rg 50 {y} 495 {height} re f\n"));
                stream.push_str(&format!("0.80 0.86 0.90 RG 50 {y} 495 {height} re S\n"));
                for line_index in 0..lines {
                    let baseline = y + height - 15 - (line_index as i32 * 13);
                    if let Some(sni) = row.sni.get(line_index) {
                        stream.push_str(&format!(
                            "BT /F1 8 Tf 62 {baseline} Td 0.08 0.14 0.19 rg ({}) Tj ET\n",
                            escape(sni)
                        ));
                    }
                    if line_index == 0 {
                        stream.push_str(&format!(
                            "BT /F2 8 Tf 252 {baseline} Td 0.08 0.14 0.19 rg ({}) Tj ET\n",
                            escape(&row.port)
                        ));
                    }
                    if let Some(ip) = row.ip.get(line_index) {
                        stream.push_str(&format!(
                            "BT /F1 8 Tf 337 {baseline} Td 0.08 0.14 0.19 rg ({}) Tj ET\n",
                            escape(ip)
                        ));
                    }
                }
            }
            stream.push_str("0.80 0.86 0.90 RG 50 44 m 545 44 l S\n");
            stream.push_str(&format!(
                "BT /F1 8 Tf 50 30 Td 0.35 0.42 0.50 rg (Gerado pelo SNI Tester PRO) Tj 430 0 Td (Pagina {} de {}) Tj ET\nQ",
                page_index + 1,
                total_pages
            ));
            stream
        })
        .collect();

    let mut objects = vec![
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        format!(
            "<< /Type /Pages /Kids [{}] /Count {} >>",
            (0..streams.len())
                .map(|index| format!("{} 0 R", 5 + index * 2))
                .collect::<Vec<_>>()
                .join(" "),
            streams.len()
        ),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>".to_string(),
    ];
    for (index, stream) in streams.iter().enumerate() {
        let page_id = 5 + index * 2;
        let content_id = page_id + 1;
        objects.push(format!(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents {content_id} 0 R >>"
        ));
        objects.push(format!("<< /Length {} >>\nstream\n{}\nendstream", stream.len(), stream));
    }

    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = vec![0usize];
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", index + 1, object).as_bytes());
    }
    let xref = pdf.len();
    pdf.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes());
    for offset in offsets.iter().skip(1) { pdf.extend_from_slice(format!("{:010} 00000 n \n", offset).as_bytes()); }
    pdf.extend_from_slice(format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n", objects.len() + 1, xref).as_bytes());
    pdf
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(AppState { scan: Mutex::new(None) })
        .invoke_handler(tauri::generate_handler![
            start_scan,
            stop_scan,
            complete_scan,
            app_icon_data_url,
            open_external_url,
            import_files,
            save_sessions,
            load_sessions,
            export_results
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
