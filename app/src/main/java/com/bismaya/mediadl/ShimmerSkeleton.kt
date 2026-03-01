package com.bismaya.mediadl

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bismaya.mediadl.ui.theme.Ink2
import com.bismaya.mediadl.ui.theme.SurfaceBorder
import com.bismaya.mediadl.ui.theme.SurfaceCard
import com.bismaya.mediadl.ui.theme.Violet

// ── Shimmer brush ───────────────────────────────────────────────────────────────
@Composable
fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )
    return Brush.linearGradient(
        colors = listOf(
            SurfaceCard.copy(alpha = 0.3f),
            Violet.copy(alpha = 0.12f),
            SurfaceCard.copy(alpha = 0.3f),
        ),
        start = Offset(translateAnim - 400f, translateAnim - 400f),
        end = Offset(translateAnim, translateAnim)
    )
}

// ── Skeleton layout that mimics the ResultCard ──────────────────────────────────
@Composable
fun SkeletonResultCard() {
    val brush = shimmerBrush()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Ink2.copy(alpha = 0.85f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder.copy(alpha = 0.12f))
    ) {
        // Animated top gradient bar (same as ResultCard)
        val tr = rememberInfiniteTransition(label = "skelBorder")
        val phase by tr.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(4000, easing = LinearEasing),
                RepeatMode.Restart
            ),
            label = "skelGradSlide"
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Violet, Color(0xFF22D3EE), Color(0xFFEC4899), Violet),
                        startX = -(phase * 4000f),
                        endX = 3f * 1000f - (phase * 4000f)
                    )
                )
        )

        Column(modifier = Modifier.padding(20.dp)) {
            // Title skeleton — two lines
            ShimmerBox(brush, Modifier.fillMaxWidth(0.85f).height(18.dp))
            Spacer(modifier = Modifier.height(8.dp))
            ShimmerBox(brush, Modifier.fillMaxWidth(0.55f).height(18.dp))

            Spacer(modifier = Modifier.height(14.dp))

            // Uploader + platform chip
            Row {
                ShimmerBox(brush, Modifier.width(60.dp).height(20.dp), shape = RoundedCornerShape(6.dp))
                Spacer(modifier = Modifier.width(10.dp))
                ShimmerBox(brush, Modifier.width(100.dp).height(20.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stat chips
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShimmerBox(brush, Modifier.width(64.dp).height(24.dp), shape = RoundedCornerShape(8.dp))
                ShimmerBox(brush, Modifier.width(80.dp).height(24.dp), shape = RoundedCornerShape(8.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(SurfaceBorder)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // "Quality & Format" label skeleton
            ShimmerBox(brush, Modifier.width(100.dp).height(12.dp))

            Spacer(modifier = Modifier.height(14.dp))

            // Tab row skeleton
            ShimmerBox(brush, Modifier.fillMaxWidth().height(40.dp), shape = RoundedCornerShape(10.dp))

            Spacer(modifier = Modifier.height(14.dp))

            // Format item skeletons (3 rows)
            repeat(3) {
                ShimmerBox(
                    brush,
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(vertical = 3.dp),
                    shape = RoundedCornerShape(10.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Download button skeleton
            ShimmerBox(brush, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp))
        }
    }
}

@Composable
private fun ShimmerBox(
    brush: Brush,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp)
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(brush)
            .border(1.dp, SurfaceBorder.copy(alpha = 0.06f), shape)
    )
}
