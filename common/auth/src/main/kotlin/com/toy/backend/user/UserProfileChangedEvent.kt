package com.toy.backend.user

/**
 * 인적사항(`gender`·`birthDate` 등)이 바뀌었다. **`common-auth`는 누가 듣는지 모른다** —
 * 앱 모듈이 `common-auth`를 의존하지 그 반대가 아니므로, 여기서 `daily-record`의
 * 영양 목표 재계산을 직접 부르면 의존 방향이 뒤집힌다(`AGENTS.md`의 모듈 구조).
 *
 * 듣는 쪽은 **같은 트랜잭션에서 동기로** 처리한다. 재계산은 DB 갱신 한 번뿐이라
 * 커밋 이후로 미룰 이유가 없고, 같은 트랜잭션이면 인적사항과 목표치가 함께 커밋된다.
 */
data class UserProfileChangedEvent(
    val user: User,
)
