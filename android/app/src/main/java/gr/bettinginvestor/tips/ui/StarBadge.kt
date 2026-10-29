package gr.bettinginvestor.tips.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Crown/trophy badge for Σημείο Ημέρας — replaces the old star emoji. */
@Composable
fun StarOfDayBadge(modifier: Modifier = Modifier, compact: Boolean = false) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .background(
                Brush.horizontalGradient(listOf(BiColors.GoldDim, BiColors.Gold)),
                shape,
            )
            .border(1.dp, BiColors.Gold, shape)
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Default.EmojiEvents,
            contentDescription = "Σημείο Ημέρας",
            tint = BiColors.Bg,
            modifier = Modifier.size(if (compact) 14.dp else 16.dp),
        )
        Text(
            if (compact) "ΣΗΜΕΙΟ" else "Σημείο Ημέρας",
            color = BiColors.Bg,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 11.sp else 12.sp,
        )
    }
}
