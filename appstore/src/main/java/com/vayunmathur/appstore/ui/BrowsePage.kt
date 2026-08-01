package com.vayunmathur.appstore.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.appstore.R
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.security.SecurityTier
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun AppRow(
    app: UnifiedApp,
    isInstalled: Boolean,
    progress: Float?,
    installedIcon: Drawable? = null,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Use installed drawable if available, else remote icon via coil
                if (installedIcon != null) {
                    val bitmap = remember(installedIcon) {
                        try { installedIcon.toBitmap(width = 96, height = 96).asImageBitmap() } catch (_: Exception) { null }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        AsyncImage(
                            model = app.iconUrl,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    AsyncImage(
                        model = app.iconUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            app.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))
                        SecurityTierBadge(SecurityTier.of(app.source))
                        Spacer(Modifier.width(4.dp))
                        SourceBadge(app.source)
                    }
                    if (app.summary.isNotBlank()) {
                        Text(
                            app.summary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (app.author != null) {
                            Text(app.author, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        if (isInstalled) {
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.installed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun SourceBadge(source: AppSource) {
    val label = when (source) {
        AppSource.MODERN_APPS -> "Modern Apps"
        AppSource.FDROID -> "F-Droid"
        AppSource.PLAYSTORE -> "Play Store"
    }
    val color = when (source) {
        AppSource.MODERN_APPS -> MaterialTheme.colorScheme.tertiaryContainer
        AppSource.FDROID -> MaterialTheme.colorScheme.primaryContainer
        AppSource.PLAYSTORE -> MaterialTheme.colorScheme.secondaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/**
 * "Tier 1" / "Tier 2" / "Tier 3" next to the source badge. Tapping opens
 * [SecurityTiersPage], which explains what each tier is actually claiming.
 */
@Composable
fun SecurityTierBadge(tier: SecurityTier, onClick: (() -> Unit)? = null) {
    // Tier 3 is neutral, not red: a Play install still passes every check the platform
    // makes possible, so an alarm colour would overstate the difference.
    val container = when (tier) {
        SecurityTier.FIRST_PARTY -> MaterialTheme.colorScheme.tertiary
        SecurityTier.REPRODUCIBLE -> MaterialTheme.colorScheme.primary
        SecurityTier.GOOGLE_PLAY -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (tier) {
        SecurityTier.FIRST_PARTY -> MaterialTheme.colorScheme.onTertiary
        SecurityTier.REPRODUCIBLE -> MaterialTheme.colorScheme.onPrimary
        SecurityTier.GOOGLE_PLAY -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    if (onClick != null) {
        Card(onClick = onClick, colors = colors) { TierLabel(tier) }
    } else {
        Card(colors = colors) { TierLabel(tier) }
    }
}

@Composable
private fun TierLabel(tier: SecurityTier) {
    Text(
        stringResource(R.string.tier_badge_label, tier.rank),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
