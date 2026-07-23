Markdown
# ⚡ SNI Tester PRO

<p align="center">
  <img src="icons/logo.png" alt="Logo do SNI Tester PRO" width="140" />
</p>

<p align="center">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-green" />
  <img alt="Platforms" src="https://img.shields.io/badge/platforms-Windows%20%7C%20Linux%20%7C%20Android-blue" />
  <img alt="Stack" src="https://img.shields.io/badge/stack-Tauri%20%2B%20Rust%20%2B%20Kotlin-purple" />
</p>

<p align="center">
  <strong>Ferramenta profissional para validar e testar SNI com rapidez, precisão e foco em produtividade.</strong>
</p>

<p align="center">
  <a href="#-sobre-o-projeto">Sobre</a> •
  <a href="#-funcionalidades">Funcionalidades</a> •
  <a href="#-como-usar">Como usar</a> •
  <a href="#-instalação">Instalação</a> •
  <a href="#-desenvolvimento">Desenvolvimento</a> •
  <a href="#-estrutura-do-projeto">Estrutura</a> •
  <a href="#-licença">Licença</a>
</p>

---

## 📌 Sobre o Projeto

O SNI Tester PRO é uma solução multiplataforma criada para testar hosts, validar configurações de SNI e analisar respostas de handshake TLS/SSL em ambientes reais. A versão desktop foi desenvolvida com Tauri, usando Rust no backend e uma interface web moderna, enquanto a versão Android foi construída com Kotlin.

A proposta é oferecer uma ferramenta leve, rápida e prática para operação, suporte e segurança.

---

## 🚀 Funcionalidades

- 🔍 Teste de múltiplos hosts e SNIs em lote
- 🌐 Suporte a portas comuns para validação de serviços HTTPS
- ⚡ Execução rápida com baixo consumo de recursos
- 📄 Importação de listas para testes repetitivos
- 🧪 Varredura aprofundada com análise de certificados e detalhes do handshake
- 📊 Logs detalhados para acompanhamento e diagnóstico
- 🖥️ Compatibilidade com desktop e mobile

---

## ▶️ Como Usar

1. Abra o aplicativo e informe os hosts ou SNIs que deseja testar.
2. Selecione as portas desejadas para a validação.
3. Inicie a execução e acompanhe os resultados em tempo real.
4. Analise os logs e os detalhes da resposta para confirmar a configuração.

Essa experiência foi pensada para reduzir o tempo de investigação e facilitar a validação em cenários reais.

---

## 📱 Plataformas

| Plataforma | Formato | Observação |
| :--- | :--- | :--- |
| Windows | `.exe` | Instalador para ambientes desktop |
| Linux | `.AppImage` / `.deb` | Uso portátil ou via instalação tradicional |
| Android | `.apk` | Aplicativo para dispositivos móveis |

---

## 📦 Instalação

Você pode baixar a versão mais recente nas Releases do repositório no GitHub.

### Desktop

1. Baixe o instalador compatível com o seu sistema.
2. Execute o arquivo e siga o assistente de instalação.

### Android

1. Baixe o arquivo `.apk`.
2. Instale o aplicativo no dispositivo.
3. Autorize a instalação de apps de fontes desconhecidas, se necessário.

---

## 🛠️ Desenvolvimento

### Pré-requisitos

- Rust
- Cargo
- Tauri CLI
- Android Studio (para a versão Android)

### Executar localmente

Clone o repositório:

```bash
git clone https://github.com/devisaacson/sni-tester-pro.git
cd sni-tester-pro
```

Para a versão desktop:

```bash
cd desktop/src-tauri
cargo tauri dev
```

Para gerar o build de produção:

```bash
cd desktop/src-tauri
cargo tauri build
```

Para a versão Android, abra a pasta `android` no Android Studio e execute o projeto em um dispositivo ou emulador.

---

## 📂 Estrutura do Projeto

```text
sni-tester-pro/
├── android/              # Código-fonte da aplicação Android
├── desktop/              # Frontend web e backend Tauri
│   ├── src/              # Interface da aplicação desktop
│   └── src-tauri/        # Código Rust e configuração do Tauri
├── docs/                 # Documentação e recursos auxiliares
├── icons/                # Ícones e assets visuais
├── index.html            # Landing page do projeto
└── LICENSE               # Licença do projeto
```

---

## 📄 Licença

Este projeto está licenciado sob a licença MIT. Consulte o arquivo [LICENSE](LICENSE) para mais detalhes.
