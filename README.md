# DLive PT Streams - Aplicação Android

Aplicação Android nativa desenvolvida em **Kotlin** e **Jetpack Compose** para assistir a transmissões ao vivo da rede DaddyLive (`dlive.sx`), com foco nos canais de **Portugal (PT)** e personalização total da lista de canais favoritos.

---

## 🌟 Principais Funcionalidades

1. **Separador "🇵🇹 Portugal"**:
   - Inclui todos os canais nacionais indexados:
     - **Desporto**: Sport TV 1, 2, 3, 4, 5 e 6, Eleven Sports (DAZN) 1 a 5, Benfica TV, Sporting TV, Porto Canal, Canal 11.
     - **Generalistas e Notícias**: RTP 1, RTP 2, RTP 3, SIC, TVI, TVI Reality, CMTV, AXN Movies.
2. **Separador "⭐ Favoritos"**:
   - Guarde qualquer canal tocando no ícone da estrela. Os favoritos são guardados localmente (`SharedPreferences`) e ficam sempre acessíveis.
3. **Separador "🌐 Todos os Canais (900+)"**:
   - Catálogo completo de mais de 900 canais de desporto, filmes e entretenimento de Espanha, Reino Unido, EUA, Brasil e internacionais.
   - Barra de pesquisa rápida por nome de canal, país ou ID.
   - Filtros por categoria: *Desporto, Filmes, Notícias, Geral, Infantil*.
4. **Reprodutor de Vídeo Otimizado (`PlayerActivity`)**:
   - **Bloqueador de Anúncios e Popups**: Interceta qualquer tentativa de abrir popunders, redirecionamentos ou publicidade abusiva.
   - **Injeção de CSS de Limpeza**: Oculta menus, chat, barras de navegação e cabeçalhos do site, focando exclusivamente no reprodutor de vídeo.
   - **Alternador de Servidores**: Se um stream falhar, permite trocar de endpoint com um toque (`/stream/`, `/cast/`, `/watch/`, `/player/`, `/plus/`).
   - **Ecrã Inteiro e Rotação**: Suporta ecrã inteiro com aceleração de hardware e alternância rápida entre modo retrato e paisagem.

---

## 📂 Estrutura do Projeto

```
DLivePTApp/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── assets/
│   │   │   └── channels.json           # Catálogo com 900+ canais (com PT catalogado)
│   │   ├── java/com/dlive/ptstream/
│   │   │   ├── MainActivity.kt         # Ponto de entrada (Jetpack Compose)
│   │   │   ├── data/
│   │   │   │   ├── Channel.kt          # Modelo de dados
│   │   │   │   └── ChannelRepository.kt# Repositório de canais e favoritos
│   │   │   └── ui/
│   │   │       ├── screens/
│   │   │       │   ├── HomeScreen.kt   # Interface moderna com Tabs e Busca
│   │   │       │   └── PlayerActivity.kt # Player WebView otimizado
│   │   │       └── theme/              # Cores escuras e tipografia
│   │   └── res/layout/activity_player.xml
│   └── build.gradle.kts
├── gradle/
│   └── libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 🚀 Como Abrir e Correr no Android Studio

1. Abra o **Android Studio**.
2. Clique em **File > Open...** (ou *Open Project*).
3. Selecione a pasta:
   ```
   C:\Users\Daniel\.gemini\antigravity\scratch\DLivePTApp
   ```
4. Aguarde a sincronização do Gradle (*Sync Project with Gradle Files*).
5. Conecte o seu smartphone Android com a *Depuração USB* ligada ou inicie um Emulador.
6. Clique no botão verde **Run 'app'** (Shift + F10).
