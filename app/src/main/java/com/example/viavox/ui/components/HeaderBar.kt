package com.example.viavox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viavox.R
import com.example.viavox.ui.theme.ViaVoxBrandIconBg
import com.example.viavox.ui.theme.ViaVoxBrandIconColor
import com.example.viavox.ui.theme.ViaVoxHeader
import com.example.viavox.ui.theme.ViaVoxTeal
import com.example.viavox.ui.theme.ViaVoxTextMuted
import com.example.viavox.ui.theme.ViaVoxTextWhite

@Composable
fun HeaderBar(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(ViaVoxHeader)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("app_header"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ViaVoxBrandIconBg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "◉",
                    color = ViaVoxBrandIconColor,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    color = ViaVoxTextWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 24.sp
                )
                Text(
                    text = stringResource(R.string.brand_subtitle),
                    color = ViaVoxTextMuted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(ViaVoxBrandIconBg.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.version_badge),
                color = ViaVoxTeal,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}
