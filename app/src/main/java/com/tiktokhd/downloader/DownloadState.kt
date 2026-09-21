package com.tiktokhd.downloader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class Status {
    object Idle : Status()
    object ReceivingUrl : Status()
    object GettingLink : Status()
    data class Downloading(val downloaded: Long, val total: Long) : Status()
    data class Completed(val path: String) : Status()
    data class AlreadyExists(val path: String) : Status()
    data class Error(val reason: String) : Status()
}

object DownloadState {
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status
    fun set(s: Status) { _status.value = s }
}
