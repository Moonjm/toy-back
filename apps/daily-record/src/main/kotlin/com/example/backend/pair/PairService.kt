package com.example.backend.pair

import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.dailyrecords.DailyRecordResponse
import com.example.backend.dailyrecords.DailyRecordService
import com.example.backend.user.User
import com.example.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class PairService(
    private val pairRepository: PairRepository,
    private val userRepository: UserRepository,
    private val dailyRecordService: DailyRecordService,
) {
    @Transactional
    fun createInvite(username: String): PairInviteResponse {
        val user = findUser(username)

        if (isConnected(user)) {
            throw CustomException(ErrorCode.ALREADY_PAIRED, user.name)
        }

        val existing = pairRepository.findByInviterAndStatus(user, PairStatus.PENDING)
        if (existing != null) {
            return PairInviteResponse(inviteCode = existing.inviteCode)
        }

        val code = generateUniqueCode()
        val pair = PairConnection(inviter = user, inviteCode = code)
        pairRepository.save(pair)
        return PairInviteResponse(inviteCode = code)
    }

    @Transactional
    fun acceptInvite(
        username: String,
        request: PairAcceptRequest,
    ): PairResponse {
        val user = findUser(username)

        if (isConnected(user)) {
            throw CustomException(ErrorCode.ALREADY_PAIRED, user.name)
        }

        val pair =
            pairRepository.findByInviteCode(request.inviteCode)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, "inviteCode")

        if (pair.inviter.requiredId == user.requiredId) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "자기 자신의 초대는 수락할 수 없습니다")
        }

        if (pair.status != PairStatus.PENDING) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "이미 수락된 초대입니다")
        }

        if (isConnected(pair.inviter)) {
            throw CustomException(ErrorCode.ALREADY_PAIRED, pair.inviter.name)
        }

        pair.accept(user)
        return pair.toResponse(user)
    }

    fun getStatus(username: String): PairResponse? {
        val user = findUser(username)
        val pair = findActivePair(user) ?: return null
        return pair.toResponse(user)
    }

    @Transactional
    fun unpair(username: String) {
        val user = findUser(username)
        val pair =
            findActivePair(user)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, "pair")
        pairRepository.delete(pair)
    }

    fun getPartnerDailyRecords(
        username: String,
        date: LocalDate?,
        from: LocalDate?,
        to: LocalDate?,
    ): List<DailyRecordResponse> {
        val user = findUser(username)
        val pair =
            findConnectedPair(user)
                ?: throw CustomException(ErrorCode.PAIR_NOT_CONNECTED)

        val partner =
            if (pair.inviter.requiredId == user.requiredId) {
                pair.partner ?: throw CustomException(ErrorCode.PAIR_NOT_CONNECTED)
            } else {
                pair.inviter
            }

        return dailyRecordService.list(partner.username, date, from, to)
    }

    fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    private fun isConnected(user: User): Boolean =
        pairRepository.findByInviterAndStatus(user, PairStatus.CONNECTED) != null ||
            pairRepository.findByPartnerAndStatus(user, PairStatus.CONNECTED) != null

    private fun findActivePair(user: User): PairConnection? =
        pairRepository.findByInviterAndStatusIn(user, listOf(PairStatus.PENDING, PairStatus.CONNECTED))
            ?: pairRepository.findByPartnerAndStatus(user, PairStatus.CONNECTED)

    fun findConnectedPair(user: User): PairConnection? =
        pairRepository.findByInviterAndStatus(user, PairStatus.CONNECTED)
            ?: pairRepository.findByPartnerAndStatus(user, PairStatus.CONNECTED)

    private fun generateUniqueCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        repeat(10) {
            val code = (1..6).map { chars.random() }.joinToString("")
            if (pairRepository.findByInviteCode(code) == null) {
                return code
            }
        }
        throw CustomException(ErrorCode.INVALID_REQUEST, "초대 코드 생성에 실패했습니다")
    }
}
