/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Shared circular avatar for Telegram chats (channels + bots). Shows the inline minithumbnail
 * immediately (it's embedded in the chat object, so zero network latency), then upgrades to the
 * full-resolution small photo once TDLib finishes downloading it. Falls back to a generic chat
 * icon when the chat has no photo at all.
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.telegram.TelegramClient
import java.io.File

@Composable
fun TelegramChatAvatar(
    photoMinithumbnail: ByteArray?,
    photoChatId: Long,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    var fullPhotoPath by remember(photoChatId) { mutableStateOf<String?>(null) }

    LaunchedEffect(photoChatId) {
        if (photoChatId != 0L) {
            val path = runCatching { TelegramClient.downloadChatPhotoFile(photoChatId) }.getOrNull()
            if (path != null && File(path).exists()) {
                fullPhotoPath = path
            }
        }
    }

    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val fullPhoto = fullPhotoPath
        when {

            fullPhoto != null -> {
                AsyncImage(
                    model = File(fullPhoto),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            photoMinithumbnail != null -> {
                AsyncImage(
                    model = photoMinithumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                Icon(
                    painter = painterResource(R.drawable.solar_chat_round_linear),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
