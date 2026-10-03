package com.dlive.ptstream.data

object SportsMatchHelper {

    /**
     * Categoriza qualquer evento desportivo na respetiva liga ou competição oficial.
     */
    fun getCompetitionCategory(event: LiveEvent): String {
        return try {
            val n = event.name.lowercase()
            when {
                // 1. Liga Portugal & Competições Nacionais Portuguesas
                containsAny(n, "benfica", "porto", "sporting", "braga", "vitória", "vitoria", "guimarães", "guimaraes",
                    "liga portugal", "taça de portugal", "taça da liga", "taca de portugal", "estoril", "famalicão", "famalicao",
                    "gil vicente", "boavista", "rio ave", "moreirense", "farense", "santa clara", "nacional", "estrela amadora",
                    "casa pia", "arouca", "portimonense", "chaves", "vizela", "leixões", "marítimo", "penafiel") -> "🇵🇹 Liga Portugal"

                // 2. UEFA Champions League, Europa League & Conference League
                containsAny(n, "champions league", "europa league", "conference league", "uefa", "super cup", "nations league") -> "🇪🇺 UEFA Champions & Europa"

                // 3. Premier League Inglesa
                containsAny(n, "premier league", "arsenal", "liverpool", "manchester city", "man city", "manchester united",
                    "man utd", "chelsea", "tottenham", "newcastle", "aston villa", "everton", "west ham", "brighton",
                    "fa cup", "carabao cup", "efl cup", "wolves", "brentford") -> "🏴󠁧󠁢󠁥󠁮󠁧󠁿 Premier League"

                // 4. La Liga Espanhola
                containsAny(n, "la liga", "laliga", "real madrid", "barcelona", "atlético madrid", "atletico madrid",
                    "sevilla", "valencia", "athletic bilbao", "betis", "villarreal", "copa del rey", "real sociedade", "girona") -> "🇪🇸 La Liga"

                // 5. Serie A Italiana
                containsAny(n, "serie a", "juventus", "inter", "milan", "napoli", "roma", "lazio", "atalanta", "fiorentina", "coppa italia") -> "🇮🇹 Serie A"

                // 6. Bundesliga Alemã
                containsAny(n, "bundesliga", "bayern", "dortmund", "leverkusen", "leipzig", "frankfurt", "stuttgart", "dfb pokal") -> "🇩🇪 Bundesliga"

                // 7. Ligue 1 Francesa
                containsAny(n, "ligue 1", "psg", "paris saint", "marseille", "monaco", "lyon", "lille") -> "🇫🇷 Ligue 1"

                // 8. Motores (F1, MotoGP, etc.)
                event.genre == 2 || containsAny(n, "f1", "formula 1", "formula 2", "motogp", "moto2", "moto3", "nascar", "indycar", "rally", "wrc") -> "🏎️ Motores (F1 & MotoGP)"

                // 9. Basquetebol / NBA
                event.genre == 7 || containsAny(n, "nba", "euroleague", "basketball", "basquete", "lakers", "warriors", "celtics", "bulls") -> "🏀 Basquetebol (NBA)"

                // 10. Ténis (ATP, WTA, Grand Slams)
                event.genre == 10 || containsAny(n, "atp", "wta", "tennis", "ténis", "wimbledon", "roland garros", "us open", "australian open") -> "🎾 Ténis (ATP/WTA)"

                // 11. Desportos de Combate / Artes Marciais
                event.genre in listOf(3, 6) || containsAny(n, "ufc", "mma", "boxing", "boxe", "wwe", "bellator") -> "🥊 Desportos de Combate"

                // 12. Outros jogos de futebol
                event.isSoccer || event.genre == 1 || n.contains(" vs ") || n.contains(" fc") || n.contains("fc ") -> "⚽ Outros Jogos de Futebol"

                // 13. Outros Desportos
                else -> "🏆 Outros Desportos"
            }
        } catch (_: Throwable) {
            "🏆 Outros Desportos"
        }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { text.contains(it) }
    }

    /**
     * Mapeia um evento desportivo para os canais de TV oficiais da aplicação
     * que transmitem o jogo (baseado em EPG, nomes das streams e direitos desportivos).
     */
    fun findBroadcastingChannels(
        event: LiveEvent,
        allChannels: List<Channel>,
        epgRepository: EpgRepository?
    ): List<Channel> {
        return try {
            val matches = mutableListOf<Channel>()
            val nameLower = event.name.lowercase()

            // 1. Verificar streams do evento: se a stream indicar o nome do canal (ex: "Sport TV 1", "DAZN 1", "TNT Sports 1")
            for (st in event.streams) {
                val sName = st.name.lowercase()
                for (ch in allChannels) {
                    if (matches.any { it.id == ch.id }) continue
                    val chNameLower = ch.name.lowercase()
                    val prefix = chNameLower.take(7)
                    if (sName.contains(chNameLower) || (prefix.length >= 5 && sName.contains(prefix))) {
                        if (ch.category.contains("Desporto", ignoreCase = true) || ch.isPortuguese) {
                            matches.add(ch)
                        }
                    }
                }
            }

            // 2. Verificação no EPG para os canais de desporto portugueses
            val teams = extractKeywords(event.name)
            if (epgRepository != null && teams.isNotEmpty()) {
                val ptSportsChannels = allChannels.filter {
                    it.isPortuguese && (it.category.contains("Desporto", ignoreCase = true) ||
                            it.name.contains("Sport TV", ignoreCase = true) ||
                            it.name.contains("DAZN", ignoreCase = true) ||
                            it.name.contains("Benfica", ignoreCase = true) ||
                            it.name.contains("Sporting", ignoreCase = true) ||
                            it.name.contains("Porto", ignoreCase = true) ||
                            it.name.contains("Eurosport", ignoreCase = true))
                }
                for (ch in ptSportsChannels) {
                    if (matches.any { it.id == ch.id }) continue
                    val currentProg = epgRepository.getCurrentProgram(ch.name)
                    if (currentProg != null) {
                        val progTitle = currentProg.title.lowercase()
                        val matchedTeams = teams.filter { progTitle.contains(it) }
                        if (matchedTeams.size >= 2 || (matchedTeams.isNotEmpty() && (progTitle.contains("direto") || progTitle.contains("live") || progTitle.contains("liga") || progTitle.contains("vs") || progTitle.contains(" x ")))) {
                            matches.add(ch)
                        }
                    }
                }
            }

            // 3. Regras inteligentes por detentor oficial de direitos desportivos
            if (matches.isEmpty()) {
                val category = getCompetitionCategory(event)
                when (category) {
                    "🇵🇹 Liga Portugal" -> {
                        if (nameLower.contains("benfica") && !nameLower.startsWith("vs benfica")) {
                            findChannelByName(allChannels, "Benfica TV")?.let { matches.add(it) }
                            findChannelByName(allChannels, "Sport TV 1 HD")?.let { matches.add(it) }
                        } else if (nameLower.contains("sporting") || nameLower.contains("porto")) {
                            findChannelByName(allChannels, "Sport TV 1 HD")?.let { matches.add(it) }
                            findChannelByName(allChannels, "Sport TV 2 HD")?.let { matches.add(it) }
                        } else {
                            findChannelByName(allChannels, "Sport TV 1 HD")?.let { matches.add(it) }
                            findChannelByName(allChannels, "Sport TV 2 HD")?.let { matches.add(it) }
                        }
                    }
                    "🏴󠁧󠁢󠁥󠁮󠁧󠁿 Premier League" -> {
                        findChannelByName(allChannels, "DAZN 1 Portugal")?.let { matches.add(it) }
                        findChannelByName(allChannels, "DAZN 2 Portugal")?.let { matches.add(it) }
                        findChannelByName(allChannels, "Sky Sports Premier League")?.let { matches.add(it) }
                    }
                    "🇪🇺 UEFA Champions & Europa" -> {
                        findChannelByName(allChannels, "DAZN 1 Portugal")?.let { matches.add(it) }
                        findChannelByName(allChannels, "DAZN 2 Portugal")?.let { matches.add(it) }
                        findChannelByName(allChannels, "Sport TV 1 HD")?.let { matches.add(it) }
                    }
                    "🇪🇸 La Liga" -> {
                        findChannelByName(allChannels, "DAZN 1 Portugal")?.let { matches.add(it) }
                        findChannelByName(allChannels, "DAZN 2 Portugal")?.let { matches.add(it) }
                        findChannelByName(allChannels, "Movistar LaLiga")?.let { matches.add(it) }
                    }
                    "🇮🇹 Serie A" -> {
                        findChannelByName(allChannels, "Sport TV 2 HD")?.let { matches.add(it) }
                        findChannelByName(allChannels, "Sport TV 3 HD")?.let { matches.add(it) }
                    }
                    "🇩🇪 Bundesliga" -> {
                        findChannelByName(allChannels, "DAZN 3 Portugal")?.let { matches.add(it) }
                        findChannelByName(allChannels, "DAZN 4 Portugal")?.let { matches.add(it) }
                    }
                    "🏎️ Motores (F1 & MotoGP)" -> {
                        if (nameLower.contains("motogp")) {
                            findChannelByName(allChannels, "Sport TV 4 HD")?.let { matches.add(it) }
                        } else {
                            findChannelByName(allChannels, "DAZN 4 Portugal")?.let { matches.add(it) }
                            findChannelByName(allChannels, "Sport TV 4 HD")?.let { matches.add(it) }
                            findChannelByName(allChannels, "Sky Sports F1")?.let { matches.add(it) }
                        }
                    }
                    "🏀 Basquetebol (NBA)" -> {
                        findChannelByName(allChannels, "Sport TV 5 HD")?.let { matches.add(it) }
                        findChannelByName(allChannels, "TNT Sports 2")?.let { matches.add(it) }
                    }
                    "🎾 Ténis (ATP/WTA)" -> {
                        findChannelByName(allChannels, "Sport TV 3 HD")?.let { matches.add(it) }
                        findChannelByName(allChannels, "Sport TV 6 HD")?.let { matches.add(it) }
                        findChannelByName(allChannels, "Eurosport 1 PT")?.let { matches.add(it) }
                    }
                }
            }

            // Prioridade: canais portugueses primeiro, únicos, máximo 3 canais
            matches.distinctBy { it.id }
                .sortedWith(compareBy({ !it.isPortuguese }, { it.name }))
                .take(3)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun findChannelByName(channels: List<Channel>, name: String): Channel? {
        return channels.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: channels.firstOrNull { it.name.contains(name, ignoreCase = true) }
    }

    private fun extractKeywords(eventName: String): List<String> {
        return try {
            val clean = eventName
                .replace("vs", " ", ignoreCase = true)
                .replace(" v ", " ", ignoreCase = true)
                .replace(" @ ", " ", ignoreCase = true)
                .replace("-", " ")
                .replace(":", " ")
                .replace(".", " ")
                .lowercase()

            val stopwords = setOf("futebol", "soccer", "live", "direto", "hd", "fc", "cf", "sc", "cp", "the", "de", "da", "do", "club", "clube")
            clean.split("\\s+".toRegex())
                .map { it.trim() }
                .filter { it.length >= 3 && it !in stopwords }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
