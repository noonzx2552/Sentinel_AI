package com.sentinel.ai.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object GuardianEventStore {
    private val events = mutableListOf<GuardianEvent>()
    private val eventsLiveData = MutableLiveData<List<GuardianEvent>>(emptyList())
    private val statusLiveData = MutableLiveData(RiskLevel.SAFE)

    fun observeEvents(): LiveData<List<GuardianEvent>> = eventsLiveData
    fun observeStatus(): LiveData<RiskLevel> = statusLiveData

    fun addEvent(event: GuardianEvent) {
        synchronized(events) {
            events.add(0, event)
            eventsLiveData.postValue(events.toList())
        }
        updateStatus(event.riskLevel)
    }

    private fun updateStatus(level: RiskLevel) {
        val current = statusLiveData.value ?: RiskLevel.SAFE
        val next = when {
            level == RiskLevel.CRITICAL -> RiskLevel.CRITICAL
            level == RiskLevel.WARNING && current == RiskLevel.SAFE -> RiskLevel.WARNING
            else -> current
        }
        if (next != current) {
            statusLiveData.postValue(next)
        }
    }

    fun clear() {
        synchronized(events) {
            events.clear()
            eventsLiveData.postValue(emptyList())
        }
        statusLiveData.postValue(RiskLevel.SAFE)
    }
}
