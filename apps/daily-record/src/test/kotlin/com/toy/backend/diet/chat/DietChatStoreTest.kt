package com.toy.backend.diet.chat

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.daily.DailyActivityRepository
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyMeal
import com.toy.backend.diet.dummyMealItem
import com.toy.backend.diet.feedback.DailyDietFeedback
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.meal.MealType
import com.toy.backend.user.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.date.shouldBeAfter
import io.kotest.matchers.date.shouldBeBefore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDate
import java.time.LocalDateTime

class DietChatStoreTest :
    BehaviorSpec({
        val userRepository = mockk<UserRepository>()
        val mealRepository = mockk<MealRepository>()
        val activityRepository = mockk<DailyActivityRepository>()
        val feedbackRepository = mockk<DailyDietFeedbackRepository>()
        val messageRepository = mockk<DietChatMessageRepository>()
        val store =
            DietChatStore(userRepository, mealRepository, activityRepository, feedbackRepository, messageRepository)

        val user = dietUser()
        val date = LocalDate.of(2026, 8, 1)

        fun mealOn(
            day: LocalDate,
            type: MealType,
            food: String,
            id: Long,
        ): Meal {
            val meal = dummyMeal(user = user, date = day, mealType = type, id = id)
            meal.replaceItems(listOf(dummyMealItem(meal = meal, foodName = food, id = id * 10)))
            meal.applyScore(70)
            return meal
        }

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { activityRepository.findByUserAndDate(user, date) } returns null
            every { feedbackRepository.findByUserAndDate(user, date) } returns null
            every {
                messageRepository.findByUserAndCreatedAtAfterOrderByIdDesc(any(), any(), any())
            } returns emptyList()
        }

        Given("기준일과 직전 7일에 기록이 있으면") {
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(
                    user,
                    date.minusDays(7),
                    date,
                )
            } returns
                listOf(
                    mealOn(date.minusDays(2), MealType.DINNER, "치킨", 1L),
                    mealOn(date, MealType.LUNCH, "제육볶음", 2L),
                )

            val context = store.loadContext("testuser", date)

            Then("기준일 상세와 직전 7일이 한 블록에 담긴다") {
                context.dataBlock shouldContain "[2026-08-01 (토) 먹은 끼니]"
                context.dataBlock shouldContain "[직전 7일]"
                context.dataBlock shouldContain "저녁: 치킨"
            }

            // 창이 오늘 기준이면 지난 날짜 대화에서 엉뚱한 7일이 실린다.
            Then("창은 기준 날짜 기준이다") {
                context.dataBlock shouldContain "- 2026-07-30 (목)"
                context.dataBlock shouldNotContain "- 2026-08-02"
            }

            Then("기록 없는 날도 자리를 지킨다 — 7일 전부가 목록에 있다") {
                (1L..7L).forEach { back ->
                    context.dataBlock shouldContain
                        date.minusDays(back).format(
                            java.time.format.DateTimeFormatter
                                .ofPattern("yyyy-MM-dd"),
                        )
                }
            }
        }

        // 총평은 캐시다. 끼니를 고치면 낡는데, 하루 화면을 다시 열지 않으면 그 문장이 그대로
        // 남는다(하루 화면이 열려야 마커 upsert가 feedback을 null로 민다). 과거 날짜 채팅이
        // 정확히 그 경로다 — 오늘 8/1 아침을 고치고 8/1 채팅을 열면 8/1 하루 화면은 안 불린다.
        Given("하루 총평이 끼니 수정보다 이르면") {
            val meal = mealOn(date, MealType.LUNCH, "제육볶음", 7L)
            meal.contentUpdatedAt = LocalDateTime.of(2026, 8, 1, 12, 0)
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)
            } returns listOf(meal)
            every { feedbackRepository.findByUserAndDate(user, date) } returns
                DailyDietFeedback(
                    user = user,
                    date = date,
                    dayScore = 61,
                    feedback = "치킨을 드셨네요",
                    generatedAt = LocalDateTime.of(2026, 8, 1, 11, 0),
                )

            val context = store.loadContext("testuser", date)

            // 최신 데이터 블록과 수정 전 음식을 말하는 총평이 한 프롬프트에 섞이면, 모델이
            // 지금은 없는 음식을 근거로 답한다. 여기서 재생성하지는 않는다 — readOnly 트랜잭션이고
            // 채팅이 유료 호출을 촉발해서는 안 된다. 총평 없이 시작하는 길은 이미 있다.
            Then("낡은 총평은 빼고 나온다") {
                context.dayFeedback shouldBe null
            }
        }

        // 경계다. `isBefore`를 `!isAfter`로 바꾸면 멀쩡한 총평이 매번 버려진다.
        Given("하루 총평과 끼니 수정이 같은 시각이면") {
            val meal = mealOn(date, MealType.LUNCH, "제육볶음", 8L)
            meal.contentUpdatedAt = LocalDateTime.of(2026, 8, 1, 12, 0)
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)
            } returns listOf(meal)
            every { feedbackRepository.findByUserAndDate(user, date) } returns
                DailyDietFeedback(
                    user = user,
                    date = date,
                    dayScore = 61,
                    feedback = "총평",
                    generatedAt = LocalDateTime.of(2026, 8, 1, 12, 0),
                )

            Then("유효한 캐시다") {
                store.loadContext("testuser", date).dayFeedback shouldBe "총평"
            }
        }

        Given("기준일에 기록이 없으면") {
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)
            } returns listOf(mealOn(date.minusDays(1), MealType.DINNER, "치킨", 3L))

            // 화면이 기준일 요약 말풍선으로 시작하는 구조라 보여줄 것이 없다.
            Then("직전 7일에 기록이 있어도 거절한다") {
                val e = shouldThrow<CustomException> { store.loadContext("testuser", date) }
                e.errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }

        // 히스토리는 큐다 — 7일 이내에 물은 것 중 최근 20턴만 싣고 오래된 것은 밀려난다.
        Given("히스토리가 7일 이내에 있으면") {
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)
            } returns listOf(mealOn(date, MealType.LUNCH, "제육볶음", 5L))
            every { feedbackRepository.findByUserAndDate(user, date) } returns
                DailyDietFeedback(
                    user = user,
                    date = date,
                    dayScore = 61,
                    feedback = "총평",
                    generatedAt = LocalDateTime.now(),
                )
            // 8/6에 물었지만 7/27에 대한 질문이다. 접두는 date(07-27)이지 createdAt(08-06)도
            // 기준 날짜(08-01)도 아니다 — 세 값을 전부 다르게 둬서 구현이 셋 중 어느 것을
            // 잘못 써도 이 테스트가 빨개지게 한다.
            every {
                messageRepository.findByUserAndCreatedAtAfterOrderByIdDesc(eq(user), any(), any())
            } returns
                listOf(
                    DietChatMessage(user, date.minusDays(5), ChatRole.ASSISTANT, "나트륨 때문입니다")
                        .withId(2L)
                        .also { it.createdAt = LocalDateTime.of(2026, 8, 6, 9, 0) },
                    DietChatMessage(user, date.minusDays(5), ChatRole.USER, "왜 낮아?")
                        .withId(1L)
                        .also { it.createdAt = LocalDateTime.of(2026, 8, 6, 9, 0) },
                )

            val context = store.loadContext("testuser", date)

            Then("오래된 것부터 시간순으로 뒤집혀 실린다") {
                context.history.map { it.role } shouldBe listOf("user", "assistant")
            }

            // 히스토리가 날짜를 넘나들어서, 안 붙이면 모델이 예전 질문을 오늘 것으로 읽는다.
            // 연도까지 붙인다 — `date`는 창에 갇혀 있지 않아 작년 같은 날도 올 수 있다.
            Then("사용자 턴 앞에 그 질문의 날짜가 붙는다") {
                context.history[0].content shouldBe "[2026-07-27] 왜 낮아?"
            }

            Then("답변 턴에는 안 붙는다 — 바로 뒤에 와서 짝이 명확하다") {
                context.history[1].content shouldBe "나트륨 때문입니다"
            }

            Then("하루 피드백을 함께 들고 나온다 — 대화의 출발점이다") {
                context.dayFeedback shouldBe "총평"
            }
        }

        // 「어느 날 밥 얘기인가」가 아니라 「어느 대화를 기억하는가」라 축이 다르다.
        Given("히스토리 창은") {
            val cutoff = slot<LocalDateTime>()
            val pageable = slot<org.springframework.data.domain.Pageable>()
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)
            } returns listOf(mealOn(date, MealType.LUNCH, "제육볶음", 6L))
            every {
                messageRepository.findByUserAndCreatedAtAfterOrderByIdDesc(eq(user), capture(cutoff), capture(pageable))
            } returns emptyList()

            store.loadContext("testuser", date)

            // 폭을 좁게 조인다 — HISTORY_DAYS를 6이나 8로 바꿔도 잡히도록, `now`를 두 번 평가해
            // 생기는 시차보다 훨씬 좁은 ±1분 창을 쓴다.
            Then("createdAt 기준 7일이다 — date 기준이 아니다") {
                val now = LocalDateTime.now()
                cutoff.captured shouldBeAfter now.minusDays(7).minusMinutes(1)
                cutoff.captured shouldBeBefore now.minusDays(7).plusMinutes(1)
            }

            Then("20턴, 즉 40행을 받는다") {
                pageable.captured.pageSize shouldBe 40
            }
        }

        Given("대화를 페이징으로 읽으면") {
            val before = slot<Long>()
            val pageable = slot<org.springframework.data.domain.Pageable>()
            // size(2)보다 한 건 더 준다 — 다음 장이 있다는 뜻이다.
            every {
                messageRepository.findByUserAndIdLessThanOrderByIdDesc(eq(user), capture(before), capture(pageable))
            } returns
                listOf(
                    DietChatMessage(user, date, ChatRole.ASSISTANT, "답2").withId(4L),
                    DietChatMessage(user, date.minusDays(3), ChatRole.USER, "질문2").withId(3L),
                    DietChatMessage(user, date.minusDays(3), ChatRole.ASSISTANT, "답1").withId(2L),
                )

            val page = store.page("testuser", null, 2)

            Then("첫 장은 커서 없이 최신부터다") {
                before.captured shouldBe Long.MAX_VALUE
                page.messages.map { it.id } shouldBe listOf(4L, 3L)
            }

            Then("다음 장이 있으면 nextCursor가 마지막 id다") {
                page.nextCursor shouldBe 3L
            }

            // 날짜로 자르지 않으므로 한 페이지에 여러 날짜가 섞인다.
            Then("메시지마다 어느 날에 대한 질문인지가 실린다") {
                page.messages.map { it.date } shouldBe listOf(date, date.minusDays(3))
            }

            Then("다음 장이 있는지 보려고 한 건 더 받는다") {
                pageable.captured.pageSize shouldBe 3
            }
        }

        Given("마지막 장이면") {
            val before = slot<Long>()
            every {
                messageRepository.findByUserAndIdLessThanOrderByIdDesc(eq(user), capture(before), any())
            } returns listOf(DietChatMessage(user, date, ChatRole.USER, "질문").withId(1L))

            val page = store.page("testuser", 5L, 2)

            // before가 리포지토리로 그대로 전달되는지 — 무조건 Long.MAX_VALUE로 바꿔도
            // 이 값이 안 잡히면 무한 스크롤이 영원히 첫 장만 받는다.
            Then("넘긴 before가 그대로 전달된다") {
                before.captured shouldBe 5L
            }

            // 앱이 무한 스크롤을 멈추는 신호다.
            Then("nextCursor가 null이다") {
                page.nextCursor shouldBe null
            }
        }

        Given("정확히 꽉 찬 마지막 장이면") {
            // size(2)와 정확히 같은 개수만 돌아온다 — `rows.size > size` 경계가 여기서 갈린다.
            // `>`를 `>=`로 바꾸면 이 경우도 nextCursor가 남아 앱이 빈 요청을 한 번 더 하게 된다.
            every {
                messageRepository.findByUserAndIdLessThanOrderByIdDesc(eq(user), any(), any())
            } returns
                listOf(
                    DietChatMessage(user, date, ChatRole.ASSISTANT, "답2").withId(4L),
                    DietChatMessage(user, date, ChatRole.USER, "질문2").withId(3L),
                )

            val page = store.page("testuser", null, 2)

            Then("nextCursor가 null이다") {
                page.nextCursor shouldBe null
            }

            Then("size만큼 다 돌려준다") {
                page.messages.map { it.id } shouldBe listOf(4L, 3L)
            }
        }

        // 유일한 쓰기 경로다 — 여기가 비면 함정 2(데이터 블록을 히스토리에 저장)가 실제로는
        // 아무 데서도 잡히지 않는다. DietChatServiceTest는 append에 무엇을 넘기는지만 보고
        // append가 실제로 무엇을 저장하는지는 못 본다.
        Given("질문과 답을 저장하면") {
            val saved = mutableListOf<DietChatMessage>()
            every { messageRepository.save(capture(saved)) } answers {
                firstArg<DietChatMessage>().withId(saved.size.toLong())
            }

            val response = store.append("testuser", date, "왜 낮아?", "나트륨 때문입니다")

            // 데이터 블록이 세 번째 행으로 저장되면 여기서 잡힌다 — 함정 2가 재현되는 자리다.
            Then("USER, ASSISTANT 두 행만 저장된다") {
                saved.map { it.role } shouldBe listOf(ChatRole.USER, ChatRole.ASSISTANT)
                saved.map { it.content } shouldBe listOf("왜 낮아?", "나트륨 때문입니다")
            }

            Then("저장된 답 한 건을 돌려준다") {
                response.role shouldBe ChatRole.ASSISTANT
                response.content shouldBe "나트륨 때문입니다"
            }

            // 스트림이 물은 시각 순이라 8/1에 대한 질문이 8/6 대화 사이에 앉는다.
            // 이 값이 없으면 앱이 말풍선에 「8/1에 대해」를 못 붙인다.
            Then("어느 날에 대한 질문인지가 실린다") {
                response.date shouldBe date
            }
        }
    })
