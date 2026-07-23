package com.toy.backend.ledger.inbound

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.LedgerErrorCode
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 파싱 실패(PARSE_FAILED) 수신 기록의 목록·삭제. 재처리는 [InboundService.retry]가 담당한다. */
@Service
@Transactional(readOnly = true)
class InboundFailureService(
    private val repository: InboundMessageRepository,
    private val userRepository: UserRepository,
) {
    fun failures(username: String): List<InboundFailureResponse> =
        repository
            .findAllByUserAndStatusOrderByIdDesc(findUser(username), InboundStatus.PARSE_FAILED)
            .map { InboundFailureResponse(id = it.requiredId, rawText = it.rawText, receivedAt = it.createdAt) }

    /**
     * 파싱에 실패한 원문을 삭제한다(재시도해도 안 되는 광고성 문자 등 정리용).
     * SAVED/CANCEL_MATCHED 기록은 처리 이력이므로 삭제를 허용하지 않는다.
     */
    @Transactional
    fun delete(
        username: String,
        id: Long,
    ) {
        val message =
            repository.findByIdAndUser(id, findUser(username))
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        if (message.status != InboundStatus.PARSE_FAILED) {
            throw CustomException(LedgerErrorCode.INBOUND_NOT_DELETABLE, id)
        }
        repository.delete(message)
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
