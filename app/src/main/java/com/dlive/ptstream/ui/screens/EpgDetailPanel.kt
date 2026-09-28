package com.dlive.ptstream.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dlive.ptstream.data.Channel
import com.dlive.ptstream.data.ChannelLogoHelper
import com.dlive.ptstream.data.EpgRepository
import com.dlive.ptstream.ui.theme.LocalCustomColors

@Composable
fun EpgDetailPanel(
    channel: Channel,
    epgRepository: EpgRepository,
    onPlayClick: (serverUrl: String?) -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalCustomColors.current
    val currentProgram = epgRepository.getCurrentProgram(channel.name)
    val schedule = epgRepository.getChannelSchedule(channel.name)
    val upcoming = schedule.filter {
        if (currentProgram != null) it.startEpoch >= currentProgram.stopEpoch
        else it.startEpoch > System.currentTimeMillis()
    }.take(3)

    val localLogo = ChannelLogoHelper.getLocalLogoRes(channel.name)
    val onlineLogo = channel.logoUrl ?: ChannelLogoHelper.getLogoUrl(channel.name)

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(theme.surface)
            .border(1.dp, theme.border, RoundedCornerShape(16.dp))
            .padding(18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Cabeçalho do Canal (Logo, Nome, Categoria e Favorito)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Logótipo em destaque
                Box(
                    modifier = Modifier
                        .size(width = 76.dp, height = 54.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(theme.surfaceVariant)
                        .border(1.dp, theme.border, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (onlineLogo != null) {
                        AsyncImage(
                            model = onlineLogo,
                            contentDescription = channel.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else if (localLogo != null) {
                        Image(
                            painter = painterResource(id = localLogo),
                            contentDescription = channel.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = channel.name.take(3).uppercase(),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = channel.name,
                        color = theme.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Tag Categoria
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(theme.primary.copy(alpha = 0.2f))
                                .border(1.dp, theme.primary.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = channel.category,
                                color = theme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (channel.isPortuguese) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("🇵🇹 Portugal", fontSize = 11.sp, color = theme.textSecondary)
                        }
                    }
                }
            }

            // Botão de Favorito
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (channel.isFavorite) Color(0x33F59E0B) else theme.surfaceVariant)
            ) {
                Icon(
                    imageVector = if (channel.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                    contentDescription = "Favorito",
                    tint = if (channel.isFavorite) Color(0xFFF59E0B) else theme.textSecondary
                )
            }
        }

        HorizontalDivider(color = theme.border, thickness = 1.dp)

        // 2. Emissão Atual (EPG)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(theme.surfaceVariant)
                .border(1.dp, theme.border, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF22C55E))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "A EMITIR AGORA",
                        color = Color(0xFF22C55E),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                if (currentProgram != null) {
                    val progressPercent = (currentProgram.getProgress() * 100).toInt()
                    Text(
                        text = "$progressPercent% decorrido",
                        color = theme.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            val programTitle = currentProgram?.title ?: "Emissão em Direto 24/7"
            Text(
                text = programTitle,
                color = theme.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (currentProgram != null) {
                Text(
                    text = "⏰ ${currentProgram.timeRange}",
                    color = theme.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                // Barra de progresso da emissão
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF2C3246))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(currentProgram.getProgress())
                            .fillMaxHeight()
                            .background(theme.primary)
                    )
                }

                if (!currentProgram.episode.isNullOrBlank()) {
                    Text(
                        text = currentProgram.episode,
                        color = theme.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = "Acompanha a transmissão em tempo real deste canal.",
                    color = theme.textSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // 3. Próximos Programas ("A Seguir")
        if (upcoming.isNotEmpty()) {
            Text(
                text = "📺 A SEGUIR",
                color = theme.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(theme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                upcoming.forEach { prog ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = prog.timeRange.substringBefore(" -"),
                            color = theme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(48.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = prog.title,
                            color = theme.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 4. Ações de Reprodução e Seleção de Servidor
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Botão Principal "Assistir Canal"
            Button(
                onClick = { onPlayClick(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Assistir em Ecrã Inteiro",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Seleção Rápida de Servidor se existirem backups
            val hasBackup1 = !channel.backupStreamUrl.isNullOrBlank()
            val hasBackup2 = !channel.backupStreamUrl2.isNullOrBlank()

            if (hasBackup1 || hasBackup2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { onPlayClick(null) },
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, theme.border)
                    ) {
                        Text("Servidor 1", fontSize = 11.sp, color = theme.textPrimary, fontWeight = FontWeight.SemiBold)
                    }

                    if (hasBackup1) {
                        OutlinedButton(
                            onClick = { onPlayClick(channel.backupStreamUrl) },
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, theme.border)
                        ) {
                            Text("Servidor 2", fontSize = 11.sp, color = theme.textPrimary, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (hasBackup2) {
                        OutlinedButton(
                            onClick = { onPlayClick(channel.backupStreamUrl2) },
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, theme.border)
                        ) {
                            Text("Servidor 3", fontSize = 11.sp, color = theme.textPrimary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
