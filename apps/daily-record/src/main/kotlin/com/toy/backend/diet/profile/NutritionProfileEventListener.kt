package com.toy.backend.diet.profile

import com.toy.backend.user.UserProfileChangedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 인적사항 변경을 듣고 영양 목표치를 다시 계산한다. **`common-auth`가 `daily-record`를
 * 부르지 않게 하려고** 이벤트를 쓴다 — 앱 모듈이 `common-auth`를 의존하지 그 반대가 아니다.
 *
 * `@EventListener`는 **발행 트랜잭션 안에서 동기로** 돈다. 그래서 인적사항과 목표치가 한
 * 트랜잭션에서 함께 커밋된다. `runAfterCommit`(커밋 뒤 `@Async` 시작)은 여기 필요 없다 —
 * 그건 비동기 스레드가 아직 커밋되지 않은 행을 조회하는 문제를 푸는 도구이고, 여기서는
 * 같은 트랜잭션의 엔티티를 변경 감지로 갱신할 뿐이다. 커밋 뒤로 미루면 오히려 별도
 * 트랜잭션이 필요해지고 실패 시 인적사항만 바뀐 채로 남는다.
 */
@Component
class NutritionProfileEventListener(
    private val profileService: NutritionProfileService,
) {
    @EventListener
    fun onUserProfileChanged(event: UserProfileChangedEvent) {
        profileService.recalculateTargets(event.user)
    }
}
