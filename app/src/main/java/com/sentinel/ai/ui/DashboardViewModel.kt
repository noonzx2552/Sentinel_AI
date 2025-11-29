package com.sentinel.ai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.service.SentinelGuardianService

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    val events: LiveData<List<GuardianEvent>> = GuardianEventStore.observeEvents()
    val status: LiveData<RiskLevel> = GuardianEventStore.observeStatus()

    private val _guardianEnabled = MutableLiveData(true)
    val guardianEnabled: LiveData<Boolean> = _guardianEnabled

    fun setGuardianEnabled(enabled: Boolean) {
        _guardianEnabled.value = enabled
        val context = getApplication<Application>()
        if (enabled) {
            SentinelGuardianService.start(context)
        } else {
            SentinelGuardianService.stop(context)
        }
    }
}
