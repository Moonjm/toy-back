package com.toy.backend.study

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Service
@Transactional(readOnly = true)
class StudySessionService(
    private val repository: StudySessionRepository,
    private val subjectRepository: StudySubjectRepository,
    private val userRepository: UserRepository,
) {
    // --- Subject CRUD ---

    fun listSubjects(): List<StudySubjectResponse> =
        subjectRepository.findAllByOrderBySortOrderAscIdAsc().map { it.toResponse() }

    @Transactional
    fun createSubject(request: StudySubjectRequest): Long {
        val maxOrder = subjectRepository.findAllByOrderBySortOrderAscIdAsc()
            .maxOfOrNull { it.sortOrder } ?: -1
        val subject = StudySubject(name = request.name, sortOrder = maxOrder + 1)
        return subjectRepository.save(subject).requiredId
    }

    @Transactional
    fun updateSubject(id: Long, request: StudySubjectRequest) {
        val subject = subjectRepository.findByIdOrNull(id)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        subject.updateDetails(name = request.name)
    }

    @Transactional
    fun deleteSubject(id: Long) {
        val subject = subjectRepository.findByIdOrNull(id)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        subjectRepository.delete(subject)
    }

    // --- Session CRUD ---

    fun list(
        username: String,
        date: LocalDate?,
        from: LocalDate?,
        to: LocalDate?,
    ): List<StudySessionResponse> {
        val user = findUser(username)
        val start = (from ?: date ?: LocalDate.now()).atStartOfDay()
        val end = (to ?: date ?: LocalDate.now()).atTime(LocalTime.MAX)
        return repository
            .findAllByUserAndStartedAtBetweenOrderByStartedAtDesc(user, start, end)
            .map { it.toResponse() }
    }

    @Transactional
    fun start(username: String, request: StudySessionStartRequest): Long {
        val subject = subjectRepository.findByIdOrNull(request.subjectId)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, request.subjectId)
        val session = StudySession(
            user = findUser(username),
            subject = subject,
            startedAt = LocalDateTime.now(),
        )
        return repository.save(session).requiredId
    }

    @Transactional
    fun pause(username: String, id: Long) {
        val session = findSession(username, id)
        session.pause(at = LocalDateTime.now())
    }

    @Transactional
    fun resume(username: String, id: Long) {
        val session = findSession(username, id)
        session.resume(at = LocalDateTime.now())
    }

    @Transactional
    fun end(username: String, id: Long): StudySessionResponse {
        val session = findSession(username, id)
        session.end(at = LocalDateTime.now())
        return session.toResponse()
    }

    private fun findSession(username: String, id: Long): StudySession {
        val user = findUser(username)
        return repository.findByIdAndUser(id, user)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
    }

    private fun findUser(username: String) =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
