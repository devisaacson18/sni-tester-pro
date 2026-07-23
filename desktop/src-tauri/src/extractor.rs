//! extractor.rs — Leitor/Extrator de arquivos (tradução de extractTextFromUri + parseSnis)
//!
//! Usa apenas APIs nativas de arquivo (std::fs) — sem ContentResolver do Android.
//! Formatos: PDF (streams FlateDecode + blocos BT..ET + hex strings), DOCX, XLSX,
//! CSV e texto puro. Depois extrai domínios via regex (parseSnis).

use regex::bytes::Regex as BRegex;
use regex::Regex;
use std::io::Read;

/// Ponto de entrada: lê o arquivo do disco e devolve a lista de SNIs únicos.
pub fn extract_snis_from_path(path: &str) -> Result<Vec<String>, String> {
    let lower = path.to_lowercase();
    let bytes = std::fs::read(path).map_err(|e| format!("Erro ao ler arquivo: {e}"))?;

    let text = if lower.ends_with(".pdf") {
        extract_pdf(&bytes)
    } else if lower.ends_with(".docx") {
        extract_zip_text(&bytes, |n| n == "word/document.xml", r"<w:t[^>]*>(.*?)</w:t>")
    } else if lower.ends_with(".xlsx") {
        extract_zip_text(
            &bytes,
            |n| n == "xl/sharedStrings.xml" || n.starts_with("xl/worksheets/sheet"),
            r"<[tv][^>]*>(.*?)</[tv]>",
        )
    } else if lower.ends_with(".csv") {
        String::from_utf8_lossy(&bytes).replace(',', " ")
    } else {
        String::from_utf8_lossy(&bytes).to_string()
    };

    Ok(parse_snis(&text))
}

/// parseSnis do Kotlin — mesmo regex de domínio, lowercase, dedup, ordem estável.
fn parse_snis(content: &str) -> Vec<String> {
    let re = Regex::new(r"(?i)\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}\b").unwrap();
    let cleaned = content
        .replace("\\n", " ")
        .replace(['\n', '\r', '\t'], " ");

    let mut seen = std::collections::HashSet::new();
    re.find_iter(&cleaned)
        .map(|m| m.as_str().to_lowercase().trim().to_string())
        .filter(|d| {
            let parts: Vec<&str> = d.split('.').collect();
            parts.len() >= 2 && parts.last().unwrap().chars().any(|c| c.is_alphabetic())
        })
        .filter(|d| seen.insert(d.clone()))
        .collect()
}

/// ISO-8859-1 (latin1) byte→char, igual ao Charsets.ISO_8859_1 do original.
fn latin1(bytes: &[u8]) -> String {
    bytes.iter().map(|&b| b as char).collect()
}

/// Extrator de baixo nível para PDFs (3 estratégias do código original).
fn extract_pdf(bytes: &[u8]) -> String {
    let mut out = String::new();

    // Estratégia 1: inflar streams comprimidos (FlateDecode)
    let stream_re = BRegex::new(r"(?s)stream\r?\n(.*?)\r?\nendstream").unwrap();
    for cap in stream_re.captures_iter(bytes) {
        let data = &bytes[cap.get(1).unwrap().range()];
        let mut decoder = flate2::read::ZlibDecoder::new(data);
        let mut buf = Vec::new();
        if decoder.read_to_end(&mut buf).is_ok() {
            out.push_str(&latin1(&buf));
        }
    }

    // Estratégia 2: blocos de texto clássicos BT...ET (strings (...) e hex <...>)
    let bt_re = BRegex::new(r"(?s)BT(.*?)ET").unwrap();
    let paren_re = BRegex::new(r"\((.*?)\)").unwrap();
    let hex_re = BRegex::new(r"<([0-9A-Fa-f]+)>").unwrap();
    for block in bt_re.captures_iter(bytes) {
        let b = &block[1];
        for s in paren_re.captures_iter(b) {
            out.push_str(&latin1(&s[1]));
            out.push(' ');
        }
        for h in hex_re.captures_iter(b) {
            if let Ok(hex) = std::str::from_utf8(&h[1]) {
                let decoded: String = (0..hex.len() / 2)
                    .filter_map(|i| u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16).ok())
                    .map(|b| b as char)
                    .collect();
                out.push_str(&decoded);
                out.push(' ');
            }
        }
    }

    // Estratégia 3: se quase nada saiu, varre os bytes crus por domínios
    if out.len() < 5 {
        out.push_str(&latin1(bytes));
    }
    out
}

/// Extrator genérico para formatos Office (ZIP): percorre as entradas,
/// filtra pelo nome e aplica o regex de tag sobre o XML interno.
fn extract_zip_text(bytes: &[u8], matcher: impl Fn(&str) -> bool, tag_re: &str) -> String {
    let mut out = String::new();
    let Ok(mut archive) = zip::ZipArchive::new(std::io::Cursor::new(bytes)) else { return out };
    let re = Regex::new(tag_re).unwrap();

    let names: Vec<String> = archive.file_names().map(|s| s.to_string()).collect();
    for name in names {
        if !matcher(&name) { continue; }
        if let Ok(mut entry) = archive.by_name(&name) {
            let mut content = String::new();
            if entry.read_to_string(&mut content).is_ok() {
                for cap in re.captures_iter(&content) {
                    out.push_str(&cap[1]);
                    out.push(' ');
                }
            }
        }
    }
    out
}
