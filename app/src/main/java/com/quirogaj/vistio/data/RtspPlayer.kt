package com.quirogaj.vistio.data

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

@OptIn(UnstableApi::class)
internal class RtspPlayer(private val context: Context) {
    fun start(
        camera: CameraEndpoint,
        port: Int,
        surface: Surface,
        onFrame: (AvcSize) -> Unit,
        onError: (Exception) -> Unit,
    ): Player {
        require(camera.origin.protocol == "https")
        val host = camera.origin.host
        val userInfo = if (camera.user.isEmpty()) "" else Uri.encode(camera.user) + ":" + Uri.encode(camera.password) + "@"
        val uri = Uri.Builder().scheme("rtsps").encodedAuthority("$userInfo$host:$port").path("/live").build()
        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setSocketFactory(RtspTlsSocketFactory(host, port, camera.certificatePin))
            .setTimeoutMs(6000)
            .createMediaSource(MediaItem.fromUri(uri))
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(100, 300, 0, 75)
            .build()
        val player = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        try {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            player.setVideoSurface(surface)
            player.addListener(object : Player.Listener {
                private var rendered = false

                override fun onRenderedFirstFrame() {
                    rendered = true
                    reportSize(player.videoSize)
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (rendered) reportSize(videoSize)
                }

                override fun onPlayerError(error: PlaybackException) = onError(error)

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) onError(IOException("RTSPS cerró el flujo"))
                }

                private fun reportSize(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) {
                        onFrame(AvcSize((size.width * size.pixelWidthHeightRatio).toInt().coerceAtLeast(1), size.height))
                    }
                }
            })
            player.setMediaSource(mediaSource)
            player.prepare()
            player.play()
            return player
        } catch (error: Exception) {
            player.release()
            throw error
        }
    }
}

private class RtspTlsSocketFactory(
    host: String,
    private val port: Int,
    private val pin: CameraCertificatePin?,
) : SocketFactory() {
    private val expectedHost = host.removeSurrounding("[", "]")

    override fun createSocket(host: String, port: Int): Socket = connect(host, port, null)

    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        connect(host, port, InetSocketAddress(local, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostAddress.orEmpty(), port)

    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        createSocket(host.hostAddress.orEmpty(), port, local, localPort)

    private fun connect(host: String, port: Int, local: InetSocketAddress?): Socket {
        if (!host.removeSurrounding("[", "]").equals(expectedHost, ignoreCase = true) || port != this.port) {
            throw IOException("RTSPS intentó conectar a otra dirección")
        }
        val plain = Socket()
        try {
            if (local != null) plain.bind(local)
            plain.connect(InetSocketAddress(expectedHost, port), 5000)
            val factory = pin?.socketFactory ?: SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(plain, expectedHost, port, true) as SSLSocket
            ssl.useClientMode = true
            ssl.soTimeout = 6000
            if (pin == null) {
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            }
            ssl.startHandshake()
            ssl.soTimeout = 0
            return ssl
        } catch (error: Exception) {
            plain.close()
            throw error
        }
    }
}
