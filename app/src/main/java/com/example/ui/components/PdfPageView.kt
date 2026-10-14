package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.pdf.PdfRendererEngine
import com.example.data.settings.PageColorTone
import com.example.data.settings.RenderSharpness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PdfPageView(
    pageIndex: Int,
    engine: PdfRendererEngine,
    modifier: Modifier = Modifier,
    sharpness: RenderSharpness = RenderSharpness.HIGH_CLARITY,
    colorTone: PageColorTone = PageColorTone.ORIGINAL,
    highQualityPrintMode: Boolean = true,
    showPageNumberBadge: Boolean = true,
    isBookmarked: Boolean = false,
    onPageNumberClick: (() -> Unit)? = null
) {
    val aspectRatio = remember(pageIndex, engine) { engine.getPageAspectRatio(pageIndex) }
    val density = LocalDensity.current

    // Compute target dimensions for crisp supersampled rendering
    val (targetWidthPx, targetHeightPx) = remember(density, aspectRatio, sharpness) {
        val multiplier = when (sharpness) {
            RenderSharpness.ULTRA_HD -> 1.5f
            RenderSharpness.HIGH_CLARITY -> 1.25f
            RenderSharpness.BALANCED -> 1.0f
        }
        val baseWidthPx = ((density.density * 480) * multiplier).toInt().coerceIn(1200, 2400)
        val baseHeightPx = (baseWidthPx * aspectRatio).toInt()
        Pair(baseWidthPx, baseHeightPx)
    }

    // Try synchronous cache check first to avoid loading flash on fast scrolls
    var pageBitmap by remember(pageIndex, engine, sharpness, colorTone) {
        mutableStateOf(
            engine.getCachedPage(
                pageIndex = pageIndex,
                targetWidth = targetWidthPx,
                targetHeight = targetHeightPx,
                sharpness = sharpness,
                colorTone = colorTone
            )
        )
    }

    var isLoading by remember(pageIndex, engine, sharpness, colorTone) {
        mutableStateOf(pageBitmap == null)
    }

    LaunchedEffect(pageIndex, engine, sharpness, colorTone, highQualityPrintMode, targetWidthPx, targetHeightPx) {
        if (pageBitmap == null) {
            isLoading = true
            withContext(Dispatchers.IO) {
                val bmp = engine.renderPage(
                    pageIndex = pageIndex,
                    targetWidth = targetWidthPx,
                    targetHeight = targetHeightPx,
                    sharpness = sharpness,
                    colorTone = colorTone,
                    highQualityPrintMode = highQualityPrintMode
                )
                withContext(Dispatchers.Main) {
                    pageBitmap = bmp
                    isLoading = false
                }
            }
        }
    }

    val paperBackgroundColor = when (colorTone) {
        PageColorTone.ORIGINAL -> Color.White
        PageColorTone.SEPIA -> Color(0xFFFBF0D9)
        PageColorTone.INVERTED_DARK -> Color(0xFF141414)
        PageColorTone.HIGH_CONTRAST -> Color.White
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f / aspectRatio)
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp)),
        color = paperBackgroundColor
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = pageBitmap
            if (bitmap != null && !bitmap.isRecycled) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "PDF Page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High
                )
            } else if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(paperBackgroundColor),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            if (showPageNumberBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(
                            color = when {
                                isBookmarked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.90f)
                                colorTone == PageColorTone.INVERTED_DARK -> Color.White.copy(alpha = 0.25f)
                                else -> Color.Black.copy(alpha = 0.55f)
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = onPageNumberClick != null) {
                            onPageNumberClick?.invoke()
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("page_number_badge_${pageIndex + 1}")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isBookmarked) {
                            Icon(
                                imageVector = Icons.Default.Bookmark,
                                contentDescription = "Bookmarked",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        Text(
                            text = "${pageIndex + 1}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
