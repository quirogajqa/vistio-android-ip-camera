package com.quirogaj.vistio.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.quirogaj.vistio.BuildConfig
import com.quirogaj.vistio.R
import kotlinx.coroutines.delay

@Composable
internal fun VistioSplash(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(900)
        onFinished()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.vistio_splash_background))
            .safeDrawingPadding(),
    ) {
        // Photo Saver TV uses a 224 px logo and 24 px captions on a 720 px canvas.
        val designHeight = minOf(maxHeight, maxWidth * 1.7f)
        val logoSize = designHeight * (224f / 720f)
        val captionSize = (designHeight.value * (24f / 720f)).sp
        Image(
            painter = painterResource(R.drawable.vistio_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = maxHeight * (168f / 720f))
                .size(logoSize)
                .clip(RoundedCornerShape(logoSize * 0.07f)),
        )
        Text(
            text = stringResource(R.string.splash_version, BuildConfig.VERSION_NAME),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = maxHeight * (462f / 720f)),
            color = Color(0xFFB8C5D8),
            style = MaterialTheme.typography.bodyLarge,
            fontSize = captionSize,
            lineHeight = captionSize * 1.2f,
        )
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * (60f / 720f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(designHeight * (5f / 720f)),
        ) {
            Image(
                painter = painterResource(R.drawable.quirogaj_cat),
                contentDescription = null,
                modifier = Modifier.size(designHeight * (48f / 720f)),
            )
            Text(
                text = stringResource(R.string.splash_author),
                color = Color(0xFFB8C5D8),
                style = MaterialTheme.typography.bodyLarge,
                fontSize = captionSize,
                lineHeight = captionSize * 1.2f,
            )
        }
    }
}
