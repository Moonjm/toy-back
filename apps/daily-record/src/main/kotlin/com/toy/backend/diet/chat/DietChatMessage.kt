package com.toy.backend.diet.chat

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDate

enum class ChatRole { USER, ASSISTANT }

/**
 * 하루 평가에 대해 주고받은 말. **저장되는 것은 사용자가 쓴 질문과 모델의 답뿐이다** —
 * 프롬프트의 데이터 블록은 저장하지 않는다. 대화 도중 끼니를 고치면 그 블록이 낡은 수치를
 * 가리키는데, 하루 피드백과 달리 **대화는 무효화할 수 없다**(이미 한 말을 취소할 수 없다).
 * 매 요청 현재 DB에서 새로 만들면 지난 대화가 옛 숫자를 언급하더라도 다음 답변은 최신을 본다.
 *
 * **스레드 테이블을 두지 않는다.** 대화는 사용자당 하나의 이어지는 스트림이라 별도 스레드
 * 식별자가 필요 없다 — `user`만으로 그 사람의 전체 대화가 정해진다. `date`는 스레드가 아니라
 * **그 질문이 어느 날 식단에 대한 것인가**로만 남는다(대화 자체는 날짜로 자르지 않는다). 질문
 * 횟수 상한이 없어 턴 수를 세는 코드도 없다.
 */
@Entity
@Table(
    name = "diet_chat_message",
    // 남은 쿼리 둘을 받친다 — 히스토리(user_id, created_at>? ORDER BY id DESC)와
    // 커서 페이징(user_id, id<? ORDER BY id DESC). 둘 다 date를 안 쓴다.
    indexes = [Index(name = "idx_diet_chat_user_id", columnList = "user_id, id")],
)
class DietChatMessage(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false)
    var date: LocalDate,
    // `columnDefinition`이 필수다 — ddl-auto가 CHECK 제약을 갱신하지 못해, 나중에 값을 늘리면
    // 기존 DB에서 INSERT가 깨진다(`AGENTS.md`).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    var role: ChatRole,
    @Column(nullable = false, columnDefinition = "text")
    var content: String,
) : BaseEntity()
