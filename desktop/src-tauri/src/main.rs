// Previne janela de console extra no Windows em modo release
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    sni_tester_pro_lib::run()
}
