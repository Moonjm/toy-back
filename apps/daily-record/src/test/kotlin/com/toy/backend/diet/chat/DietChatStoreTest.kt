package com.toy.backend.diet.chat

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.DietErrorCode
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate

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
            every { messageRepository.findByUserAndDateOrderByIdAsc(user, date) } returns emptyList()
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
                context.dataBlock shouldContain "- 07-30 (목)"
                context.dataBlock shouldNotContain "- 08-02"
            }

            Then("기록 없는 날도 자리를 지킨다 — 7일 전부가 목록에 있다") {
                (1L..7L).forEach { back ->
                    context.dataBlock shouldContain
                        date.minusDays(back).format(
                            java.time.format.DateTimeFormatter
                                .ofPattern("MM-dd"),
                        )
                }
            }

            Then("남은 턴은 상한 그대로다") {
                context.remainingTurns shouldBe MAX_TURNS_PER_DAY
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

        Given("턴 상한에 닿았으면") {
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)
            } returns listOf(mealOn(date, MealType.LUNCH, "제육볶음", 4L))
            every { messageRepository.findByUserAndDateOrderByIdAsc(user, date) } returns
                (1..MAX_TURNS_PER_DAY).flatMap {
                    listOf(
                        DietChatMessage(user, date, ChatRole.USER, "질문$it").withId(it.toLong() * 2),
                        DietChatMessage(user, date, ChatRole.ASSISTANT, "답$it").withId(it.toLong() * 2 + 1),
                    )
                }

            Then("CHAT_TURN_LIMIT_EXCEEDED") {
                val e = shouldThrow<CustomException> { store.loadContext("testuser", date) }
                e.errorCode shouldBe DietErrorCode.CHAT_TURN_LIMIT_EXCEEDED
            }
        }

        Given("히스토리가 있으면") {
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)
            } returns listOf(mealOn(date, MealType.LUNCH, "제육볶음", 5L))
            every { messageRepository.findByUserAndDateOrderByIdAsc(user, date) } returns
                listOf(
                    DietChatMessage(user, date, ChatRole.USER, "왜 낮아?").withId(1L),
                    DietChatMessage(user, date, ChatRole.ASSISTANT, "나트륨 때문입니다").withId(2L),
                )
            every { feedbackRepository.findByUserAndDate(user, date) } returns
                DailyDietFeedback(user = user, date = date, dayScore = 61, feedback = "총평", generatedAt = java.time.LocalDateTime.now())

            val context = store.loadContext("testuser", date)

            Then("API 역할 값으로 교대해 나온다") {
                context.history.map { it.role } shouldBe listOf("user", "assistant")
                context.history.map { it.content } shouldBe listOf("왜 낮아?", "나트륨 때문입니다")
            }

            Then("하루 피드백을 함께 들고 나온다 — 대화의 출발점이다") {
                context.dayFeedback shouldBe "총평"
            }

            Then("남은 턴이 준다") {
                context.remainingTurns shouldBe MAX_TURNS_PER_DAY - 1
            }
        }

        // 유일한 쓰기 경로다 — 여기가 비면 함정 2(데이터 블록을 히스토리에 저장)가 실제로는
        // 아무 데서도 잡히지 않는다. DietChatServiceTest는 append에 무엇을 넘기는지만 보고
        // append가 실제로 무엇을 저장하는지는 못 본다.
        Given("질문과 답을 저장하면") {
            val slots = mutableListOf<DietChatMessage>()
            every { messageRepository.save(capture(slots)) } answers {
                firstArg<DietChatMessage>().withId(slots.size.toLong())
            }
            every { messageRepository.findByUserAndDateOrderByIdAsc(user, date) } returns
                listOf(
                    DietChatMessage(user, date, ChatRole.USER, "왜 낮아?").withId(1L),
                    DietChatMessage(user, date, ChatRole.ASSISTANT, "나트륨").withId(2L),
                )

            val response = store.append("testuser", date, "왜 낮아?", "나트륨")

            Then("USER, ASSISTANT 두 행만 저장된다 — 데이터 블록은 저장하지 않는다") {
                slots.map { it.role } shouldBe listOf(ChatRole.USER, ChatRole.ASSISTANT)
                slots.map { it.content } shouldBe listOf("왜 낮아?", "나트륨")
            }

            Then("남은 턴이 준다") {
                response.remainingTurns shouldBe MAX_TURNS_PER_DAY - 1
            }

            Then("응답은 방금 저장한 답 메시지다") {
                response.message.role shouldBe ChatRole.ASSISTANT
                response.message.content shouldBe "나트륨"
            }
        }

        Given("history는") {
            Then("빈 대화면 남은 턴이 상한 그대로다") {
                // beforeContainer가 messageRepository를 빈 목록으로 기본 스텁해 둔다.
                val response = store.history("testuser", date)

                response.messages shouldBe emptyList()
                response.remainingTurns shouldBe MAX_TURNS_PER_DAY
            }

            Then("저장된 메시지가 쌓인 순서대로 나온다") {
                every { messageRepository.findByUserAndDateOrderByIdAsc(user, date) } returns
                    listOf(
                        DietChatMessage(user, date, ChatRole.USER, "왜 낮아?").withId(1L),
                        DietChatMessage(user, date, ChatRole.ASSISTANT, "나트륨").withId(2L),
                    )

                val response = store.history("testuser", date)

                response.messages.map { it.role } shouldBe listOf(ChatRole.USER, ChatRole.ASSISTANT)
                response.messages.map { it.content } shouldBe listOf("왜 낮아?", "나트륨")
                response.remainingTurns shouldBe MAX_TURNS_PER_DAY - 1
            }
        }
    })
