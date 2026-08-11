package com.toy.backend.dispatch

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

/**
 * 인식이 저장하는 유일한 것이 행 위치다. **인식 서비스에서 분리해 둔 이유**는 LLM 호출이
 * 최대 12분까지 걸리는데 그 전체를 트랜잭션으로 감싸면 DB 커넥션을 그만큼 붙잡기 때문이다.
 * 같은 클래스의 private 메서드로 두면 자기 호출이라 프록시를 안 타 `@Transactional`이 죽는다.
 *
 * 트랜잭션 밖에서 조회된 엔티티는 준영속이라, 여기서 **다시 조회해** 고쳐야 반영된다.
 */
class DispatchRosterUpdaterTest :
    BehaviorSpec({
        val rosterRepository = mockk<DispatchRosterRepository>()
        val updater = DispatchRosterUpdater(rosterRepository)

        Given("그 달 기준이 아직 없을 때") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            val saved = slot<DispatchRoster>()
            every { rosterRepository.save(capture(saved)) } answers { firstArg() }

            updater.upsert("2026-08", rowIndex = 2, rowCount = 13)

            Then("새로 만들어 저장한다") {
                saved.captured.yearMonth shouldBe "2026-08"
                saved.captured.rowIndex shouldBe 2
                saved.captured.rowCount shouldBe 13
            }
        }

        Given("그 달 기준이 이미 있을 때") {
            val existing = DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { rosterRepository.findByYearMonth("2026-08") } returns existing
            every { rosterRepository.save(any()) } answers { firstArg() }

            updater.upsert("2026-08", rowIndex = 5, rowCount = 14)

            Then("기존 행을 고친다 — 같은 달에 두 행이 생기지 않는다") {
                existing.rowIndex shouldBe 5
                existing.rowCount shouldBe 14
            }

            Then("save를 다시 부르지 않는다 — 영속 상태라 변경 감지로 반영된다") {
                verify(exactly = 0) { rosterRepository.save(any()) }
            }
        }
    })
