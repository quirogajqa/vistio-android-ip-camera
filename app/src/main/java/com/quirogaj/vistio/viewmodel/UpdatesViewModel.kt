package com.quirogaj.vistio.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quirogaj.vistio.data.update.UpdateCheck
import com.quirogaj.vistio.data.update.UpdateException
import com.quirogaj.vistio.data.update.UpdateFailure
import com.quirogaj.vistio.data.update.UpdateRelease
import com.quirogaj.vistio.data.update.UpdateRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class UpdateStatus { NOT_CONFIGURED, IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }

internal data class UpdatesState(
    val status: UpdateStatus,
    val release: UpdateRelease? = null,
    val progress: Int = 0,
    val apk: File? = null,
    val message: String? = null,
) {
    val busy get() = status == UpdateStatus.CHECKING || status == UpdateStatus.DOWNLOADING
}

internal class UpdatesViewModel(private val repository: UpdateRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(
        UpdatesState(if (repository.configured) UpdateStatus.IDLE else UpdateStatus.NOT_CONFIGURED),
    )
    val state = mutableState.asStateFlow()
    private var automaticStarted = false
    private var work: Job? = null

    fun checkAutomatically() {
        if (automaticStarted) return
        automaticStarted = true
        check(automatic = true)
    }

    fun check(automatic: Boolean = false) {
        if (!repository.configured || work?.isActive == true) return
        work = viewModelScope.launch {
            if (!automatic) mutableState.value = UpdatesState(UpdateStatus.CHECKING)
            try {
                when (val result = repository.check(automatic)) {
                    is UpdateCheck.Completed -> {
                        val release = result.release
                        mutableState.value = UpdatesState(
                            if (release == null) UpdateStatus.CURRENT else UpdateStatus.AVAILABLE, release,
                        )
                    }
                    UpdateCheck.Skipped -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!automatic) reportError(error)
            }
        }
    }

    fun download() {
        val release = state.value.release ?: return
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            mutableState.value = UpdatesState(UpdateStatus.DOWNLOADING, release)
            try {
                val apk = repository.download(release) { progress -> mutableState.update { it.copy(progress = progress) } }
                mutableState.value = UpdatesState(UpdateStatus.READY, release, progress = 100, apk = apk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(error)
            }
        }
    }

    fun cancelDownload() {
        val release = state.value.release ?: return
        if (state.value.status != UpdateStatus.DOWNLOADING) return
        work?.cancel()
        mutableState.value = UpdatesState(UpdateStatus.AVAILABLE, release)
    }

    fun downloadMissing() {
        mutableState.update {
            it.copy(
                status = if (it.release != null) UpdateStatus.AVAILABLE else UpdateStatus.IDLE,
                apk = null, message = "El APK ya no está disponible. Vuelve a descargarlo.",
            )
        }
    }

    fun installationMessage(text: String) {
        mutableState.update { it.copy(message = text) }
    }

    private fun reportError(error: Exception) {
        val message = (error as? UpdateException)?.reason?.description ?: UpdateFailure.NETWORK.description
        mutableState.update { it.copy(status = UpdateStatus.ERROR, message = message) }
    }
}
