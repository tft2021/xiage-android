package com.xiaozhi.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhi.android.SessionHolder
import com.xiaozhi.android.XiaozhiSession
import com.xiaozhi.android.service.XiaozhiService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class MainViewModel(application: Application) : AndroidViewModel(application) {

    init {
        XiaozhiService.start(application)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val phase: StateFlow<XiaozhiSession.Phase> = SessionHolder.sessionFlow
        .flatMapLatest { it?.phase ?: flowOf(XiaozhiSession.Phase.Idle) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, XiaozhiSession.Phase.Idle)

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversation: StateFlow<XiaozhiSession.ConversationState> = SessionHolder.sessionFlow
        .flatMapLatest { it?.conversation ?: flowOf(XiaozhiSession.ConversationState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, XiaozhiSession.ConversationState())

    fun startListening() = SessionHolder.session?.startListening()

    fun stopListening() = SessionHolder.session?.stopListening()

    fun abort() = SessionHolder.session?.abort()
}
