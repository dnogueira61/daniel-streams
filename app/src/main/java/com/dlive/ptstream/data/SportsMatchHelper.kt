package com.dlive.ptstream.data

object SportsMatchHelper {

    /**
     * Categoriza qualquer evento desportivo na respetiva liga ou competição oficial.
     */
    /**
     * Verifica se o evento é uma partida ou transmissão de futebol.
     */
    fun isSoccerEvent(event: LiveEvent): Boolean {
        if (event.genre in listOf(2, 3, 6, 7, 8, 9, 10, 11)) return false
        val n = event.name.lowercase()
        if (containsAny(n, "formula 1", "formula 2", "motogp", "moto2", "moto3", "nascar", "indycar", "rally", "wrc", "f1",
                "ufc", "bellator", "mma", "wwe", "boxing", "boxe", "boxen",
                "nba", "euroleague", "basketball", "basquete",
                "atp", "wta", "tennis", "ténis", "wimbledon", "roland garros", "us open", "australian open",
                "nfl", "nhl", "mlb", "baseball", "basebol", "golf", "golfe", "darts", "snooker")) {
            return false
        }
        return event.isSoccer || event.genre == 1 ||
                event.genreName.contains("futebol", ignoreCase = true) ||
                event.genreName.contains("soccer", ignoreCase = true) ||
                event.genreName.contains("football", ignoreCase = true) ||
                containsAny(n, " vs ", " v ", " - ", " fc", "fc ", " cf", "cf ", "sporting", "benfica", "porto", "braga",
                    "real madrid", "barcelona", "liverpool", "arsenal", "man city", "chelsea", "juventus", "milan", "inter",
                    "bayern", "dortmund", "psg", "united", "city", "athletic", "atletico", "cup", "liga", "league")
    }

    /**
     * Categoria macro desportiva (Futebol, Motores, Basquetebol, Ténis, Combate, etc.)
     */
    fun getSportCategory(event: LiveEvent): String {
        return try {
            val n = event.name.lowercase()
            when {
                isSoccerEvent(event) -> "⚽ Futebol"
                event.genre == 2 || containsAny(n, "f1", "formula 1", "formula 2", "motogp", "moto2", "moto3", "nascar", "indycar", "rally", "wrc") -> "🏎️ Motores (F1 & MotoGP)"
                event.genre == 7 || containsAny(n, "nba", "euroleague", "basketball", "basquete", "lakers", "warriors", "celtics", "bulls") -> "🏀 Basquetebol (NBA)"
                event.genre == 10 || containsAny(n, "atp", "wta", "tennis", "ténis", "wimbledon", "roland garros", "us open", "australian open") -> "🎾 Ténis (ATP/WTA)"
                event.genre in listOf(3, 6) || containsAny(n, "ufc", "mma", "boxing", "boxe", "wwe", "bellator") -> "🥊 Desportos de Combate"
                else -> "🏆 Outros Desportos"
            }
        } catch (_: Throwable) {
            "🏆 Outros Desportos"
        }
    }

    /**
     * Detecta a liga ou competição específica para um jogo de futebol.
     */
    fun getSoccerLeague(event: LiveEvent): String {
        return try {
            val n = event.name.lowercase()
            when {
                // 1. Liga Portugal & Competições Nacionais Portuguesas
                containsAny(n, "benfica", "porto", "sporting", "braga", "vitória", "vitoria", "guimarães", "guimaraes",
                    "liga portugal", "taça de portugal", "taça da liga", "taca de portugal", "estoril", "famalicão", "famalicao",
                    "gil vicente", "boavista", "rio ave", "moreirense", "farense", "santa clara", "nacional", "estrela amadora",
                    "casa pia", "arouca", "portimonense", "chaves", "vizela", "leixões", "marítimo", "penafiel", "alverca",
                    "feirense", "tondela", "academico viseu", "felgueiras") -> "🇵🇹 Liga Portugal"

                // 2. Seleções & Liga das Nações / Amigáveis Internacionais
                containsAny(n, "nations league", "liga das nações", "qualificação euro", "copa america", "world cup",
                    "euro 20", "amigável", "amigavel", "friendly", "france vs", "italy vs", "spain vs", "germany vs",
                    "england vs", "portugal vs", "brazil vs", "argentina vs", "belgium vs", "netherlands vs",
                    "croatia vs", "poland vs", "sweden vs", "denmark vs", "switzerland vs", "austria vs", "ukraine vs",
                    "norway vs", "serbia vs", "turkey vs", "türkiye", "cyprus vs", "latvia", "bosnia", "liechtenstein",
                    "montenegro", "armenia", "georgia", "romania", "hungary", "kazakhstan", "faroe islands") -> "🌍 Seleções & Liga das Nações"

                // 3. UEFA Champions League, Europa League & Conference League
                containsAny(n, "champions league", "europa league", "conference league", "uefa super cup", "super cup") -> "🇪🇺 UEFA Champions & Europa"

                // 4. Premier League Inglesa
                containsAny(n, "premier league", "arsenal", "liverpool", "manchester city", "man city", "manchester united",
                    "man utd", "chelsea", "tottenham", "newcastle", "aston villa", "everton", "west ham", "brighton",
                    "wolves", "brentford", "crystal palace", "fulham", "bournemouth", "nottingham forest", "ipswich",
                    "southampton", "leicester", "fa cup", "carabao cup", "efl cup") -> "🏴󠁧󠁢󠁥󠁮󠁧󠁿 Premier League"

                // 5. La Liga Espanhola
                containsAny(n, "la liga", "laliga", "real madrid", "barcelona", "atlético madrid", "atletico madrid",
                    "sevilla", "valencia", "athletic bilbao", "athletic club", "betis", "villarreal", "copa del rey",
                    "real sociedade", "girona", "rayo vallecano", "celta", "mallorca", "osasuna", "las palmas",
                    "alaves", "alavés", "leganes", "leganés", "espanyol", "valladolid", "getafe") -> "🇪🇸 La Liga"

                // 6. Serie A Italiana
                containsAny(n, "serie a", "juventus", "inter", "milan", "napoli", "roma", "lazio", "atalanta", "fiorentina",
                    "torino", "udinese", "bologna", "monza", "genoa", "verona", "parma", "como", "empoli", "cagliari",
                    "venezia", "lecce", "coppa italia") -> "🇮🇹 Serie A"

                // 7. Bundesliga Alemã
                containsAny(n, "bundesliga", "bayern", "dortmund", "leverkusen", "leipzig", "frankfurt", "stuttgart",
                    "hoffenheim", "wolfsburg", "freiburg", "augsburg", "bremen", "monchengladbach", "mönchengladbach",
                    "mainz", "st. pauli", "heidenheim", "bochum", "holstein kiel", "dfb pokal") -> "🇩🇪 Bundesliga"

                // 8. Ligue 1 Francesa
                containsAny(n, "ligue 1", "psg", "paris saint", "marseille", "monaco", "lyon", "lille", "lens", "rennes",
                    "nice", "strasbourg", "reims", "nantes", "auxerre", "brest", "le havre", "montpellier", "toulouse",
                    "saint-etienne", "angers") -> "🇫🇷 Ligue 1"

                // 9. Eredivisie (Países Baixos)
                containsAny(n, "eredivisie", "psv", "feyenoord", "ajax", "alkmaar", "az alkmaar", "twente", "utrecht",
                    "heerenveen", "sparta rotterdam", "go ahead eagles", "nec nijmegen", "groningen", "willem", "heracles",
                    "fortuna sittard", "pec zwolle", "nac breda", "almere", "rkc waalwijk") -> "🇳🇱 Eredivisie (Países Baixos)"

                // 10. Liga Belga (Jupiler Pro League)
                containsAny(n, "belgian", "jupiler", "anderlecht", "club brugge", "cercle brugge", "gent", "genk",
                    "union sg", "saint-gilloise", "antwerp", "standard liège", "charleroi", "lommel", "waasland",
                    "kortrijk", "westerlo", "mechelen", "dender", "beerschot", "sint-truiden", "oud-heverlee", "louvière") -> "🇧🇪 Liga Belga (Jupiler Pro)"

                // 11. Liga MX (México)
                containsAny(n, "liga mx", "puebla", "león", "leon", "tigres", "toluca", "cruz azul", "pumas", "américa",
                    "america", "chivas", "guadalajara", "monterrey", "pachuca", "santos laguna", "atlas", "necaxa",
                    "querétaro", "mazatlán", "mazatlan", "tijuana", "juárez", "juarez", "san luis") -> "🇲🇽 Liga MX (México)"

                // 12. MLS (Estados Unidos)
                containsAny(n, "mls", "major league soccer", "chicago fire", "vancouver whitecaps", "inter miami",
                    "la galaxy", "lafc", "sounders", "red bulls", "nycfc", "atlanta united", "orlando city", "columbus crew",
                    "portland timbers", "austin fc", "charlotte fc", "fc cincinnati", "philadelphia union") -> "🇺🇸 MLS (Estados Unidos)"

                // 13. Allsvenskan (Suécia)
                containsAny(n, "allsvenskan", "malmö", "malmo", "aik", "djurgården", "djurgarden", "hammarby", "elfsborg",
                    "häcken", "hacken", "göteborg", "goteborg", "sirius", "kalmar", "halmstad", "brommapojkarna",
                    "gais", "mjällby", "mjallby", "degerfors", "västerås", "vasteras") -> "🇸🇪 Allsvenskan (Suécia)"

                // 14. EFL Championship & Taças de Inglaterra
                containsAny(n, "championship", "west brom", "birmingham", "derby county", "wrexham", "coventry", "leeds",
                    "watford", "norwich", "sunderland", "middlesbrough", "sheffield united", "sheffield wednesday", "burnley",
                    "luton", "stoke", "blackburn", "hull city", "bristol city", "qpr", "millwall", "swansea", "cardiff",
                    "preston", "oxford united", "plymouth", "portsmouth") -> "🏴󠁧󠁢󠁥󠁮󠁧󠁿 Championship (Inglaterra 2ª)"

                // 15. Brasileirão & Competições Sul-Americanas
                containsAny(n, "brasileirão", "brasileirao", "flamengo", "palmeiras", "corinthians", "são paulo", "sao paulo",
                    "santos fc", "grêmio", "gremio", "internacional", "fluminense", "botafogo", "vasco da gama", "vasco",
                    "cruzeiro", "bahia", "fortaleza", "athletico paranaense", "libertadores", "sudamericana") -> "🇧🇷 Brasileirão & América do Sul"

                // 16. Saudi Pro League
                containsAny(n, "saudi", "al hilal", "al nassr", "al ittihad", "al ahli", "al shabab", "al ettifaq") -> "🇸🇦 Liga Saudita"

                // 17. Outras Ligas & Jogos
                else -> "⚽ Outras Ligas & Amigáveis"
            }
        } catch (_: Throwable) {
            "⚽ Outras Ligas & Amigáveis"
        }
    }

    /**
     * Categoriza qualquer evento desportivo na respetiva liga ou modalidade oficial.
     */
    fun getCompetitionCategory(event: LiveEvent): String {
        return try {
            if (isSoccerEvent(event)) {
                getSoccerLeague(event)
            } else {
                getSportCategory(event)
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
