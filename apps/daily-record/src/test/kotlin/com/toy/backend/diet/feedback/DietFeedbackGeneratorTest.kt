package com.toy.backend.diet.feedback

import com.toy.backend.common.entity.withAudit
import com.toy.backend.common.entity.withId
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.chat.DietChatCardWriter
import com.toy.backend.diet.daily.DailyActivityRepository
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.llm.OpenRouterClient
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealItem
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.meal.MealType
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.user.UserRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

private val MARKER_AT: LocalDateTime = LocalDateTime.of(2026, 7, 29, 20, 0)

class DietFeedbackGeneratorTest :
    BehaviorSpec({
        val mealRepository = mockk<MealRepository>()
        val activityRepository = mockk<DailyActivityRepository>()
        val feedbackRepository = mockk<DailyDietFeedbackRepository>()
        val userRepository = mockk<UserRepository>()
        val client = mockk<OpenRouterClient>()
        val chatCards = mockk<DietChatCardWriter>()
        // 얇은 트랜잭션 경계 래퍼라 실물을 쓴다 — 목으로 바꾸면 이 테스트들이 아무것도 확인하지 않게 된다.
        val store = MealFeedbackStore(mealRepository)
        // 진짜 스토어를 쓴다 — 트랜잭션 경계만 다른 얇은 위임이라 목으로 바꾸면 확인할 게 없어진다.
        val dayStore = DayFeedbackStore(userRepository, mealRepository, activityRepository, feedbackRepository, chatCards)

        val user = dietUser()
        val date = LocalDate.of(2026, 7, 29)

        fun meal(
            id: Long,
            mealType: MealType,
            kcal: Double,
            proteinG: Double,
            createdAt: LocalDateTime,
        ): Meal {
            val meal =
                Meal(
                    user = user,
                    date = date,
                    mealType = mealType,
                    weightKg = 70.0,
                    targetKcal = 2509,
                    targetCarbsG = 345,
                    targetProteinG = 94,
                    targetFatG = 84,
                    targetSugarG = 125,
                    targetSodiumMg = 2300,
                    targetFiberG = 30,
                ).withId(id).withAudit(createdAt = createdAt)
            meal.replaceItems(
                listOf(
                    MealItem(
                        meal = meal,
                        foodName = "제육볶음",
                        foodCode = "D1",
                        quantityG = 200.0,
                        kcal = kcal,
                        carbsG = 40.0,
                        proteinG = proteinG,
                        fatG = 15.0,
                        source = NutritionSource.DB_MATCHED,
                    ),
                ),
            )
            return meal
        }

        Given("끼니 피드백 생성") {
            val lunch = meal(2L, MealType.LUNCH, 600.0, 18.0, LocalDateTime.of(2026, 7, 29, 12, 30))

            When("호출이 성공하면") {
                val generator = DietFeedbackGenerator(store, dayStore, client)
                every { mealRepository.findByIdOrNull(2L) } returns lunch
                val prompt = slot<String>()
                every { client.generateText(any(), capture(prompt)) } returns "잘 드셨어요. 단백질이 부족합니다. 저녁에 닭가슴살을 곁들여 보세요."

                generator.generateForMeal(2L)

                Then("피드백이 채워지고 상태가 COMPLETED가 된다") {
                    lunch.feedback shouldContain "닭가슴살"
                    lunch.status shouldBe AnalysisStatus.COMPLETED
                }

                Then("다른 끼니와 활동 에너지는 아예 조회하지 않는다 — 이 끼니만 보고 쓴다") {
                    verify(exactly = 0) { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(any(), any()) }
                    verify(exactly = 0) { activityRepository.findByUserAndDate(any(), any()) }
                }

                Then("이 끼니의 음식명·수치·점수 근거가 프롬프트에 담긴다") {
                    prompt.captured shouldContain "제육볶음"
                    prompt.captured shouldContain "600kcal"
                    val basis = DietScoreCalculator.scoreMeal(lunch.carbsG, lunch.proteinG, lunch.fatG).basis.shouldNotBeNull()
                    prompt.captured shouldContain basis.standard
                    basis.macros.forEach { macro -> prompt.captured shouldContain "${macro.name} ${macro.percent}%" }
                }
            }

            When("호출이 실패하면") {
                val generator = DietFeedbackGenerator(store, dayStore, client)
                val dinner = meal(3L, MealType.DINNER, 700.0, 30.0, LocalDateTime.of(2026, 7, 29, 19, 0))
                every { mealRepository.findByIdOrNull(3L) } returns dinner
                every { client.generateText(any(), any()) } returns null

                generator.generateForMeal(3L)

                Then("점수는 살리고 상태만 FAILED — 점수가 피드백보다 중요하다") {
                    dinner.feedback shouldBe null
                    dinner.status shouldBe AnalysisStatus.FAILED
                    dinner.totalKcal shouldBe 700.0
                }
            }

            When("API 키가 없어 클라이언트 빈이 없으면") {
                val generator = DietFeedbackGenerator(store, dayStore, null)
                val snack = meal(4L, MealType.SNACK, 200.0, 5.0, LocalDateTime.of(2026, 7, 29, 15, 0))
                every { mealRepository.findByIdOrNull(4L) } returns snack

                generator.generateForMeal(4L)

                Then("호출을 건너뛰고 FAILED로 둔다 — 로컬에서도 확정 자체는 성공해야 한다") {
                    snack.feedback shouldBe null
                    snack.status shouldBe AnalysisStatus.FAILED
                }
            }

            // LLM 호출을 트랜잭션 안에서 하면 Meal이 호출 내내 영속성 컨텍스트에 남고, 그 사이
            // 항목이 수정되면 커밋 때 dirty check가 합계 컬럼을 옛 값으로 되돌린다. 단위 테스트에
            // Hibernate가 없어 그 되돌림 자체는 재현되지 않으므로, **그것을 불가능하게 만드는
            // 구조**를 확인한다 — 호출 뒤에 다시 조회하는지.
            When("피드백을 실을 때") {
                val generator = DietFeedbackGenerator(store, dayStore, client)
                val brunch = meal(5L, MealType.BREAKFAST, 500.0, 20.0, LocalDateTime.of(2026, 7, 29, 10, 0))
                every { mealRepository.findByIdOrNull(5L) } returns brunch
                every { client.generateText(any(), any()) } returns "좋은 한 끼였어요. 채소를 곁들여 보세요."

                generator.generateForMeal(5L)

                Then("LLM 호출 뒤에 끼니를 다시 조회한다 — 엔티티를 호출 내내 붙들지 않는다") {
                    verifyOrder {
                        mealRepository.findByIdOrNull(5L)
                        client.generateText(any(), any())
                        mealRepository.findByIdOrNull(5L)
                    }
                }
            }

            // 확정하고 바로 항목을 고치면 비동기 작업이 둘 겹친다(`MealService`가 확정·수정·재시도
            // 세 곳에서 건다). 늦게 끝나는 쪽이 무조건 이기면 **먼저 끝난 새 문장을 낡은 문장이
            // 덮어쓰고**, 그 상태는 다음 수정 전까지 안 풀린다.
            When("생성 중에 끼니가 바뀌어 새 작업이 먼저 끝났으면") {
                val generator = DietFeedbackGenerator(store, dayStore, client)
                val edited =
                    meal(6L, MealType.DINNER, 800.0, 25.0, LocalDateTime.of(2026, 7, 29, 18, 0))
                        .withAudit(createdAt = LocalDateTime.of(2026, 7, 29, 18, 0))
                every { mealRepository.findByIdOrNull(6L) } returns edited
                every { client.generateText(any(), any()) } answers {
                    // LLM을 부르는 사이에 사용자가 항목을 고쳤고(`updatedAt`이 오른다),
                    // 그때 걸린 새 작업이 먼저 끝나 제 문장을 실어 둔 상태다.
                    edited.withAudit(
                        createdAt = LocalDateTime.of(2026, 7, 29, 18, 0),
                        updatedAt = LocalDateTime.of(2026, 7, 29, 18, 5),
                    )
                    edited.markFeedback("새 항목 기준으로 다시 쓴 문장입니다.")
                    "고치기 전 항목을 기준으로 쓴 낡은 문장입니다."
                }

                generator.generateForMeal(6L)

                Then("낡은 문장으로 덮어쓰지 않는다") {
                    edited.feedback shouldContain "다시 쓴"
                    edited.status shouldBe AnalysisStatus.COMPLETED
                }
            }

            // 위 가드가 「항상 버린다」로 굳으면 피드백이 영영 안 실린다. 반대 방향도 함께 건다.
            When("생성 중에 끼니가 그대로면") {
                val generator = DietFeedbackGenerator(store, dayStore, client)
                val steady =
                    meal(7L, MealType.LUNCH, 550.0, 22.0, LocalDateTime.of(2026, 7, 29, 12, 0))
                        .withAudit(createdAt = LocalDateTime.of(2026, 7, 29, 12, 0))
                every { mealRepository.findByIdOrNull(7L) } returns steady
                every { client.generateText(any(), any()) } returns "그대로면 실려야 합니다."

                generator.generateForMeal(7L)

                Then("정상적으로 싣는다") {
                    steady.feedback shouldContain "실려야"
                    steady.status shouldBe AnalysisStatus.COMPLETED
                }
            }
        }

        Given("하루 마감 피드백 생성") {
            val breakfast = meal(1L, MealType.BREAKFAST, 400.0, 10.0, LocalDateTime.of(2026, 7, 29, 8, 0))
            val lunch = meal(2L, MealType.LUNCH, 600.0, 18.0, LocalDateTime.of(2026, 7, 29, 12, 30))

            When("호출이 성공하면") {
                val generator = DietFeedbackGenerator(store, dayStore, client)
                // `DailyDietService`가 생성을 시작하기 전에 먼저 써 둔 마커 행 — feedback은 아직 null이다.
                val marker =
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 0,
                        feedback = null,
                        generatedAt = MARKER_AT,
                    ).withId(9L)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(breakfast, lunch)
                every { activityRepository.findByUserAndDate(user, date) } returns null
                every { feedbackRepository.findByUserAndDate(user, date) } returns marker
                val prompt = slot<String>()
                every { client.generateText(any(), capture(prompt)) } returns
                    "오늘 잘 드셨어요. 아침에 단백질이 부족했으니 간식으로 그릭요거트를 곁들이세요."
                justRun { chatCards.writeDaySummary(any(), any()) }

                generator.generateForDay(user.requiredId, date, MARKER_AT)

                Then("호출자가 먼저 써 둔 마커 행에 결과를 채운다") {
                    marker.feedback shouldContain "그릭요거트"
                }

                // 끝난 시각을 찍으면 생성 중에 있었던 수정보다 나중이 되어, 낡은 문장이 유효한
                // 것으로 굳는다. 수정은 캐시 행을 지우지 않아 `cached == null` 검사로는 안 잡힌다.
                Then("generatedAt은 마커 시각 그대로 둔다 — 생성 중 수정이 무효화 판정에 걸려야 한다") {
                    marker.generatedAt shouldBe MARKER_AT
                }

                Then("그날 끼니 전체와 하루 점수가 프롬프트에 담긴다") {
                    prompt.captured shouldContain "제육볶음"
                    prompt.captured shouldContain "하루 점수"
                }

                // 예전에는 `generateForDay` 전체가 하나의 `@Transactional`이라 LLM 호출 내내 DB
                // 커넥션을 붙들었다. 단위 테스트에 트랜잭션이 없어 그 자체는 재현되지 않으므로,
                // **그것을 불가능하게 만드는 구조**를 확인한다 — 읽기와 쓰기가 호출을 사이에 두고
                // 갈라져 있는지. 끼니 쪽에 이미 같은 테스트가 있다.
                Then("읽기와 쓰기가 LLM 호출을 사이에 두고 갈라진다 — 호출 내내 커넥션을 붙들지 않는다") {
                    verifyOrder {
                        mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date)
                        client.generateText(any(), any())
                        feedbackRepository.findByUserAndDate(user, date)
                    }
                }
            }

            // 위 순서 검사는 **트랜잭션 경계를 못 본다** — `@Transactional`을 다시 붙여도 호출 순서는
            // 그대로라 통과한다. 스프링을 띄우지 않는 단위 테스트로는 경계를 관찰할 방법이 없으므로,
            // 애노테이션 자체를 고정한다. 동작 검증이 아니라 「되돌아가는 것」을 막는 장치다.
            When("트랜잭션 경계는") {
                Then("generateForDay에 @Transactional이 없다 — 있으면 LLM 호출이 다시 트랜잭션 안으로 들어온다") {
                    DietFeedbackGenerator::class.java
                        .getMethod("generateForDay", Long::class.java, LocalDate::class.java, LocalDateTime::class.java)
                        .isAnnotationPresent(Transactional::class.java) shouldBe false
                }

                Then("읽기는 readOnly, 쓰기는 아니다") {
                    DayFeedbackStore::class.java
                        .getMethod("loadPrompt", Long::class.java, LocalDate::class.java)
                        .getAnnotation(Transactional::class.java)
                        .readOnly shouldBe true
                    DayFeedbackStore::class.java
                        .getMethod(
                            "publish",
                            Long::class.java,
                            LocalDate::class.java,
                            LocalDateTime::class.java,
                            Int::class.java,
                            String::class.java,
                        ).getAnnotation(Transactional::class.java)
                        .readOnly shouldBe false
                }
            }

            When("호출이 성공했는데 그 사이 마커가 지워졌으면") {
                // 마커는 트리거 직전에 항상 저장된다 — 여기서 없다는 것은 끼니 삭제·활동 에너지 갱신으로
                // 캐시가 이미 무효화됐다는 뜻이다. 방금 만든 문장은 낡은 구성 기준이라 되살리면 안 된다.
                val generator = DietFeedbackGenerator(store, dayStore, client)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(breakfast, lunch)
                every { activityRepository.findByUserAndDate(user, date) } returns null
                every { feedbackRepository.findByUserAndDate(user, date) } returns null
                every { client.generateText(any(), any()) } returns "이제는 낡은 문장입니다."

                generator.generateForDay(user.requiredId, date, MARKER_AT)

                Then("새 행으로 되살리지 않는다") {
                    verify(exactly = 0) { feedbackRepository.save(any()) }
                }
            }

            When("호출이 실패하면") {
                val generator = DietFeedbackGenerator(store, dayStore, client)
                val marker =
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 0,
                        feedback = null,
                        generatedAt = MARKER_AT,
                    ).withId(11L)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(breakfast, lunch)
                every { activityRepository.findByUserAndDate(user, date) } returns null
                every { client.generateText(any(), any()) } returns null

                generator.generateForDay(user.requiredId, date, MARKER_AT)

                Then("마커 행을 그대로 둔다 — 자동 재시도를 넣지 않는다는 설계와 맞물린다") {
                    marker.feedback.shouldBeNull()
                    verify(exactly = 0) { feedbackRepository.findByUserAndDate(any(), any()) }
                    verify(exactly = 0) { feedbackRepository.save(any()) }
                }
            }

            When("API 키가 없어 클라이언트 빈이 없으면") {
                val generator = DietFeedbackGenerator(store, dayStore, null)

                generator.generateForDay(user.requiredId, date, MARKER_AT)

                Then("아무것도 조회하지 않고 즉시 끝난다") {
                    verify(exactly = 0) { userRepository.findByIdOrNull(any()) }
                }
            }

            // 생성 중에 끼니가 수정되면 다음 하루 조회가 무효화 판정에 걸려 **새 마커를 찍고**
            // 새 작업을 건다. `generatedAt`이 그 수정보다 뒤로 점프하므로, 이 낡은 작업이 문장을
            // 실으면 **낡은 문장이 새 마커를 뒤집어쓰고 유효한 캐시로 굳는다** — `publish`가
            // `generatedAt`을 안 건드리는 것만으로는 못 막는 경로다.
            When("생성 중에 새 마커가 찍혔으면") {
                val generator = DietFeedbackGenerator(store, dayStore, client)
                val newerMarker =
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 0,
                        feedback = null,
                        // 내가 찍은 MARKER_AT이 아니라, 그 뒤 조회가 새로 찍은 시각이다.
                        generatedAt = MARKER_AT.plusMinutes(3),
                    ).withId(12L)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(breakfast, lunch)
                every { activityRepository.findByUserAndDate(user, date) } returns null
                every { feedbackRepository.findByUserAndDate(user, date) } returns newerMarker
                every { client.generateText(any(), any()) } returns "이미 낡은 구성 기준의 문장입니다."

                generator.generateForDay(user.requiredId, date, MARKER_AT)

                Then("싣지 않는다 — 새 마커 아래에서 유효한 것처럼 보이면 안 된다") {
                    newerMarker.feedback.shouldBeNull()
                }

                Then("새 작업이 이미 실어 둔 문장도 덮어쓰지 않는다") {
                    newerMarker.publish(80, "새 구성 기준으로 다시 쓴 문장입니다.")
                    generator.generateForDay(user.requiredId, date, MARKER_AT)
                    newerMarker.feedback shouldContain "다시 쓴"
                }
            }

            // **리눅스에서 `LocalDateTime.now()`는 나노초까지 준다**(`clock_gettime`). 그 값이
            // 마커로 저장되면서 `timestamp(6)`에 잘리고, 비동기 작업에는 안 잘린 값이 그대로
            // 넘어간다. 자릿수를 안 맞추면 같은 마커인데도 달라 보여 **생성된 문장이 매번
            // 버려지고**, 남은 null 마커가 유효한 캐시로 판정돼 다시 만들지도 않는다.
            //
            // macOS는 마이크로초까지만 줘서 실제 시계로는 재현되지 않는다 — 그래서 DB가 돌려줄
            // 값(잘린 것)과 작업이 들고 있는 값(안 잘린 것)을 손으로 만들어 넣는다.
            When("리눅스처럼 마커에 나노초 자릿수가 붙어 있으면") {
                val generator = DietFeedbackGenerator(store, dayStore, client)
                val fromJvm = MARKER_AT.plusNanos(123_456)
                val fromDb = fromJvm.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                val marker =
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 0,
                        feedback = null,
                        generatedAt = fromDb,
                    ).withId(13L)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(breakfast, lunch)
                every { activityRepository.findByUserAndDate(user, date) } returns null
                every { feedbackRepository.findByUserAndDate(user, date) } returns marker
                every { client.generateText(any(), any()) } returns "제대로 실려야 하는 문장입니다."
                justRun { chatCards.writeDaySummary(any(), any()) }

                generator.generateForDay(user.requiredId, date, fromJvm)

                Then("같은 마커로 보고 싣는다 — 안 그러면 하루 피드백이 영영 안 나온다") {
                    marker.feedback shouldContain "제대로 실려야"
                }
            }

            When("마커를 쓴 뒤 끼니가 모두 삭제됐으면") {
                val generator = DietFeedbackGenerator(store, dayStore, client)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns emptyList()

                generator.generateForDay(user.requiredId, date, MARKER_AT)

                Then("LLM을 부르지 않는다 — 캐시 행은 끼니 삭제 경로에서 이미 지워졌다") {
                    verify(exactly = 0) { client.generateText(any(), any()) }
                }
            }
        }

        Given("총평을 실을 때") {
            val summaryDate = LocalDate.of(2026, 8, 1)
            val markerAt = LocalDateTime.of(2026, 8, 1, 22, 0)

            When("마커가 내 것과 같으면") {
                val cached =
                    DailyDietFeedback(
                        user = user,
                        date = summaryDate,
                        dayScore = 0,
                        feedback = null,
                        generatedAt = markerAt,
                    ).withId(20L)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { feedbackRepository.findByUserAndDate(user, summaryDate) } returns cached
                justRun { chatCards.writeDaySummary(any(), any()) }

                dayStore.publish(user.requiredId, summaryDate, markerAt, dayScore = 61, feedback = "총평")

                Then("문장이 실리고 그 날짜에 총평 카드가 놓인다") {
                    cached.feedback shouldBe "총평"
                    verify { chatCards.writeDaySummary(user, summaryDate) }
                }
            }

            // 마커가 낡았다는 것은 그 사이 끼니가 바뀌어 이 문장이 이미 낡았다는 뜻이다.
            // 문장을 버리면서 카드만 만들면 있지도 않은 총평을 가리킨다.
            When("그 사이 새 마커가 찍혔으면") {
                val newer =
                    DailyDietFeedback(
                        user = user,
                        date = summaryDate,
                        dayScore = 0,
                        feedback = null,
                        generatedAt = markerAt.plusMinutes(3),
                    ).withId(21L)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { feedbackRepository.findByUserAndDate(user, summaryDate) } returns newer

                dayStore.publish(user.requiredId, summaryDate, markerAt, dayScore = 61, feedback = "총평")

                Then("문장을 버리고 카드도 만들지 않는다") {
                    newer.feedback shouldBe null
                    verify(exactly = 0) { chatCards.writeDaySummary(any(), any()) }
                }
            }
        }
    })
