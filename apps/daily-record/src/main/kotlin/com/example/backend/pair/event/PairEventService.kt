package com.example.backend.pair.event

import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.pair.PairService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class PairEventService(
    private val pairEventRepository: PairEventRepository,
    private val pairService: PairService,
) {
    fun list(
        username: String,
        from: LocalDate?,
        to: LocalDate?,
    ): List<PairEventResponse> {
        val user = pairService.findUser(username)
        val pair =
            pairService.findConnectedPair(user)
                ?: throw CustomException(ErrorCode.PAIR_NOT_CONNECTED)

        val events =
            if (from != null && to != null) {
                val all = pairEventRepository.findByPairOrderByEventDate(pair)
                all.filter { event ->
                    if (event.recurring) {
                        val md = event.eventDate.monthValue * 100 + event.eventDate.dayOfMonth
                        val fromMd = from.monthValue * 100 + from.dayOfMonth
                        val toMd = to.monthValue * 100 + to.dayOfMonth
                        md in fromMd..toMd
                    } else {
                        !event.eventDate.isBefore(from) && !event.eventDate.isAfter(to)
                    }
                }
            } else {
                pairEventRepository.findByPairOrderByEventDate(pair)
            }

        return events.map { it.toResponse() }
    }

    @Transactional
    fun create(
        username: String,
        request: PairEventRequest,
    ): Long {
        val user = pairService.findUser(username)
        val pair =
            pairService.findConnectedPair(user)
                ?: throw CustomException(ErrorCode.PAIR_NOT_CONNECTED)

        val event =
            PairEvent(
                pair = pair,
                title = request.title,
                emoji = request.emoji,
                eventDate = request.eventDate,
                recurring = request.recurring,
            )
        return pairEventRepository.save(event).requiredId
    }

    @Transactional
    fun delete(
        username: String,
        id: Long,
    ) {
        val user = pairService.findUser(username)
        val pair =
            pairService.findConnectedPair(user)
                ?: throw CustomException(ErrorCode.PAIR_NOT_CONNECTED)

        val event =
            pairEventRepository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)

        if (event.pair.requiredId != pair.requiredId) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }

        pairEventRepository.delete(event)
    }
}
