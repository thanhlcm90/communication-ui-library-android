// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.azure.android.communication.ui.calling.presentation.fragment.calling.participant.grid

import com.azure.android.communication.ui.calling.models.DominantSpeakersInfoModel
import com.azure.android.communication.ui.calling.models.ParticipantInfoModel
import com.azure.android.communication.ui.calling.presentation.fragment.factories.ParticipantGridCellViewModelFactory
import com.azure.android.communication.ui.calling.redux.state.CallingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.Integer.min

internal class ParticipantGridViewModel(
    private val participantGridCellViewModelFactory: ParticipantGridCellViewModelFactory,
    private val maxRemoteParticipantSize: Int
) {

    private var remoteParticipantsUpdatedStateFlow: MutableStateFlow<List<ParticipantGridCellViewModel>> =
        MutableStateFlow(mutableListOf())

    private var displayedRemoteParticipantsViewModelMap =
        mutableMapOf<String, ParticipantGridCellViewModel>()

    private var updateVideoStreamsCallback: ((List<Pair<String, String>>) -> Unit)? = null
    private var remoteParticipantStateModifiedTimeStamp: Number = 0
    private var dominantSpeakersStateModifiedTimestamp: Number = 0
    private lateinit var isLobbyOverlayDisplayedFlow: MutableStateFlow<Boolean>

    fun init(
        callingStatus: CallingStatus,
    ) {
        isLobbyOverlayDisplayedFlow = MutableStateFlow(isLobbyOverlayDisplayed(callingStatus))
    }

    fun clear() {
        remoteParticipantStateModifiedTimeStamp = 0
        dominantSpeakersStateModifiedTimestamp = 0
        displayedRemoteParticipantsViewModelMap.clear()
        remoteParticipantsUpdatedStateFlow.value = mutableListOf()
    }

    fun getRemoteParticipantsUpdateStateFlow(): StateFlow<List<ParticipantGridCellViewModel>> {
        return remoteParticipantsUpdatedStateFlow
    }

    fun setUpdateVideoStreamsCallback(callback: (List<Pair<String, String>>) -> Unit) {
        this.updateVideoStreamsCallback = callback
    }

    fun getMaxRemoteParticipantsSize(): Int {
        return maxRemoteParticipantSize
    }

    fun getIsLobbyOverlayDisplayedFlow(): StateFlow<Boolean> = isLobbyOverlayDisplayedFlow

    fun updateIsLobbyOverlayDisplayed(callingStatus: CallingStatus) {
        isLobbyOverlayDisplayedFlow.value = isLobbyOverlayDisplayed(callingStatus)
    }

    fun update(
        remoteParticipantsMapUpdatedTimestamp: Number,
        remoteParticipantsMap: Map<String, ParticipantInfoModel>,
        dominantSpeakersInfoModel: DominantSpeakersInfoModel,
        dominantSpeakersModifiedTimestamp: Number,
    ) {
        if (remoteParticipantsMapUpdatedTimestamp == remoteParticipantStateModifiedTimeStamp &&
            dominantSpeakersModifiedTimestamp == dominantSpeakersStateModifiedTimestamp
        ) {
            return
        }

        remoteParticipantStateModifiedTimeStamp = remoteParticipantsMapUpdatedTimestamp
        dominantSpeakersStateModifiedTimestamp = dominantSpeakersStateModifiedTimestamp

        val participantSharingScreen = getParticipantSharingScreen(remoteParticipantsMap)

        val remoteParticipantsMapSorted = if (participantSharingScreen.isNullOrEmpty()) {
            sortRemoteParticipants(remoteParticipantsMap, dominantSpeakersInfoModel)
        } else {
            mapOf(
                    Pair(
                            participantSharingScreen,
                            remoteParticipantsMap[participantSharingScreen]!!
                    )
            )
        }

        updateRemoteParticipantsVideoStreams(remoteParticipantsMapSorted)

        updateDisplayedParticipants(remoteParticipantsMapSorted.toMutableMap())
    }

    private fun getParticipantSharingScreen(
        remoteParticipantsMap: Map<String, ParticipantInfoModel>,
    ): String? {
        remoteParticipantsMap.forEach { (id, participantInfoModel) ->
            if (participantInfoModel.screenShareVideoStreamModel != null) {
                return id
            }
        }
        return null
    }

    private fun updateDisplayedParticipants(
        remoteParticipantsMapSorted: MutableMap<String, ParticipantInfoModel>,
    ) {
        displayedRemoteParticipantsViewModelMap.clear()
        remoteParticipantsMapSorted.forEach { (id, participantInfoModel) ->
            displayedRemoteParticipantsViewModelMap[id] =
                participantGridCellViewModelFactory.ParticipantGridCellViewModel(
                    participantInfoModel,
                )
        }

        if (remoteParticipantsMapSorted.isNotEmpty() /*|| viewModelsToRemoveCount > 0*/) {
            remoteParticipantsUpdatedStateFlow.value =
                displayedRemoteParticipantsViewModelMap.values.toList()
        }
    }

    private fun sortRemoteParticipants(
        remoteParticipantsMap: Map<String, ParticipantInfoModel>,
        dominantSpeakersInfoModel: DominantSpeakersInfoModel,
    ): Map<String, ParticipantInfoModel> {

        val dominantSpeakersOrder = mutableMapOf<String, Int>()

        for (i in 0 until min(maxRemoteParticipantSize, dominantSpeakersInfoModel.speakers.count())) {
            dominantSpeakersOrder[dominantSpeakersInfoModel.speakers[i]] = i
        }

        val lengthComparator = Comparator<Pair<String, ParticipantInfoModel>> { part1, part2 ->

            if (dominantSpeakersOrder.containsKey(part1.first)
                    && dominantSpeakersOrder.containsKey(part2.first)) {
                val order1 = dominantSpeakersOrder.getValue(part1.first)
                val order2 = dominantSpeakersOrder.getValue(part2.first)
                return@Comparator if (order1 > order2)
                    1 else -1
            }

            if (dominantSpeakersOrder.containsKey(part1.first))
                return@Comparator -1

            if (dominantSpeakersOrder.containsKey(part2.first))
                return@Comparator 1

            return@Comparator if (part1.second.displayName > part2.second.displayName) 1 else -1
        }

        return remoteParticipantsMap.toList()
            .sortedWith(lengthComparator)
            .take(maxRemoteParticipantSize).toMap()
    }

    private fun updateRemoteParticipantsVideoStreams(
        participantViewModelMap: Map<String, ParticipantInfoModel>,
    ) {
        val usersVideoStream: MutableList<Pair<String, String>> = mutableListOf()
        participantViewModelMap.forEach { (participantId, participant) ->
            participant.cameraVideoStreamModel?.let {
                usersVideoStream.add(
                    Pair(
                        participantId,
                        participant.cameraVideoStreamModel!!.videoStreamID
                    )
                )
            }
            participant.screenShareVideoStreamModel?.let {
                usersVideoStream.add(
                    Pair(
                        participantId,
                        participant.screenShareVideoStreamModel!!.videoStreamID
                    )
                )
            }
        }
        updateVideoStreamsCallback?.invoke(usersVideoStream)
    }

    private fun isLobbyOverlayDisplayed(callingStatus: CallingStatus) =
        callingStatus == CallingStatus.IN_LOBBY
}
