package com.toy.backend.diet.chat

import com.toy.backend.user.User
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 타임라인에 카드를 놓고 치운다.
 *
 * **`diet.meal`·`diet.feedback`이 이쪽을 부른다.** `diet.chat`은 이미 `diet.meal`을 읽고
 * 있어서(`DietChatStore`) 패키지 참조가 양방향이 된다. 그래도 여기 두는 이유는 **채팅 테이블을
 * 아는 코드를 한 패키지에 모으기 위해서다** — 반대로 두면 끼니·피드백 서비스가 `type`의
 * 기본값이며 `mealId`에 FK가 없다는 것까지 각자 알아야 한다. 빈 사이에는 순환이 없다.
 *
 * **트랜잭션을 스스로 열지 않는다.** 부르는 쪽(끼니 확정·총평 저장)의 트랜잭션에 얹혀야
 * 끼니만 커밋되고 카드는 없는 상태가 생기지 않는다.
 */
@Component
class DietChatCardWriter(
    private val repository: DietChatMessageRepository,
) {
    /**
     * 끼니 확정 시 한 행. **합쳐졌으면 부르지 않는다** — 참조 방식이라 기존 카드가 이미
     * 합쳐진 값을 보여주고, 또 만들면 같은 끼니를 가리키는 카드가 둘이 되어 같은 내용이
     * 두 번 뜬다. 그 판단은 부르는 쪽이 한다(`MealService.confirm`의 `existing`).
     */
    fun writeMealCard(
        user: User,
        date: LocalDate,
        mealId: Long,
    ) {
        repository.save(
            DietChatMessage(
                user = user,
                date = date,
                role = ChatRole.ASSISTANT,
                // 본문은 참조로만 채워진다 — 스냅샷을 담으면 끼니를 고칠 때 카드가 낡는다.
                content = "",
                type = ChatMessageType.MEAL_CARD,
                mealId = mealId,
            ),
        )
    }

    /** 총평이 처음 완성될 때 한 행. 이미 있으면 아무것도 하지 않는다. */
    fun writeDaySummary(
        user: User,
        date: LocalDate,
    ) {
        if (repository.existsByUserAndDateAndType(user, date, ChatMessageType.DAY_SUMMARY)) return
        repository.save(
            DietChatMessage(
                user = user,
                date = date,
                role = ChatRole.ASSISTANT,
                content = "",
                type = ChatMessageType.DAY_SUMMARY,
            ),
        )
    }

    /** 끼니가 사라지면 그 카드도 사라진다. */
    fun deleteMealCards(mealId: Long) {
        repository.deleteByMealId(mealId)
    }
}
