package com.toy.backend.diet.analysis

import com.toy.backend.common.entity.withId
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyFood
import com.toy.backend.diet.food.FoodDataset
import com.toy.backend.diet.food.FoodMatcher
import com.toy.backend.diet.food.FoodPolicy
import com.toy.backend.diet.llm.OpenRouterClient
import com.toy.backend.diet.llm.RecognizedFood
import com.toy.backend.file.FileContent
import com.toy.backend.file.FileService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

class MealAnalyzerTest :
    BehaviorSpec({
        val repository = mockk<MealAnalysisRepository>()
        val fileService = mockk<FileService>()
        val foodMatcher = mockk<FoodMatcher>()
        val client = mockk<OpenRouterClient>()
        val objectMapper = jacksonObjectMapper()

        val user = dietUser()
        val recognized =
            RecognizedFood(
                name = "제육볶음",
                portion = 0.5,
                // 아래 estimated* 는 전부 **1인분** 값이다. 저장되는 값은 여기에 portion을 곱한 것이다.
                servingWeightG = 300.0,
                estimatedKcal = 400.0,
                estimatedCarbsG = 30.0,
                estimatedProteinG = 25.0,
                estimatedFatG = 18.0,
                estimatedSugarG = 7.5,
                estimatedSodiumMg = 880.0,
                estimatedFiberG = 2.2,
            )

        fun pendingAnalysis(vararg fileIds: Long): MealAnalysis =
            MealAnalysis(
                user = user,
                resultJson = objectMapper.writeValueAsString(AnalysisResult(fileIds.map { AnalyzedPhoto(fileId = it) })),
            ).withId(1L)

        Given("사진 2장 인식") {
            When("한 장은 성공하고 한 장은 호출이 실패하면") {
                val analyzer = MealAnalyzer(repository, fileService, foodMatcher, objectMapper, client)
                val analysis = pendingAnalysis(10L, 11L)
                every { repository.findByIdOrNull(1L) } returns analysis
                every { fileService.download(10L) } returns FileContent(byteArrayOf(1), "image/jpeg")
                every { fileService.download(11L) } returns FileContent(byteArrayOf(2), "image/jpeg")
                every { client.recognizeFoods(any(), "image/jpeg") } returns listOf(recognized) andThen null
                every { foodMatcher.match("제육볶음") } returns
                    dummyFood(
                        servingSizeG = 300.0,
                        kcalPer100g = 200.0,
                        carbsPer100g = 10.0,
                        proteinPer100g = 20.0,
                        fatPer100g = 5.0,
                        sugarPer100g = 4.0,
                        sodiumMgPer100g = 600.0,
                        fiberPer100g = 3.0,
                    )

                analyzer.analyze(1L)

                val result = objectMapper.readValue<AnalysisResult>(analysis.resultJson)

                Then("부분 실패를 허용한다 — 나머지 결과로 확인 화면을 띄운다") {
                    analysis.status shouldBe AnalysisStatus.COMPLETED
                    result.photos[0].failed shouldBe false
                    result.photos[1].failed shouldBe true
                }

                Then("식품DB 매칭에 성공하면 1인분 배수로 환산한다 — 300g × 0.5 = 150g") {
                    val item = result.photos[0].items[0]
                    item.foodName shouldBe "제육볶음"
                    item.quantityG shouldBe 150.0
                    item.kcal shouldBe 300.0
                    item.source shouldBe NutritionSource.DB_MATCHED
                }

                Then("주의 영양소도 함께 환산돼 AnalyzedItem에 실린다 — 100g당 값 × 1.5") {
                    val item = result.photos[0].items[0]
                    item.sugarG shouldBe 6.0
                    item.sodiumMg shouldBe 900.0
                    item.fiberG shouldBe 4.5
                }
            }

            When("한 장은 성공하고 한 장은 다운로드 중 예외가 나면") {
                val analyzer = MealAnalyzer(repository, fileService, foodMatcher, objectMapper, client)
                val analysis = pendingAnalysis(12L, 13L)
                every { repository.findByIdOrNull(1L) } returns analysis
                every { fileService.download(12L) } returns FileContent(byteArrayOf(1), "image/jpeg")
                every { fileService.download(13L) } throws RuntimeException("S3 접속 실패")
                every { client.recognizeFoods(any(), "image/jpeg") } returns listOf(recognized)
                every { foodMatcher.match("제육볶음") } returns
                    dummyFood(servingSizeG = 300.0, kcalPer100g = 200.0, carbsPer100g = 10.0, proteinPer100g = 20.0, fatPer100g = 5.0)

                Then("예외가 밖으로 새지 않고, 실패한 사진만 표시되며 나머지 결과는 그대로 남는다") {
                    analyzer.analyze(1L)

                    val result = objectMapper.readValue<AnalysisResult>(analysis.resultJson)
                    analysis.status shouldBe AnalysisStatus.COMPLETED
                    result.photos[0].failed shouldBe false
                    result.photos[0].items[0].foodName shouldBe "제육볶음"
                    result.photos[1].failed shouldBe true
                    result.photos[1].items shouldBe emptyList()
                }
            }

            When("모든 사진에서 실패하면") {
                val analyzer = MealAnalyzer(repository, fileService, foodMatcher, objectMapper, client)
                val analysis = pendingAnalysis(20L)
                every { repository.findByIdOrNull(1L) } returns analysis
                every { fileService.download(20L) } returns FileContent(byteArrayOf(1), "image/jpeg")
                every { client.recognizeFoods(any(), any()) } returns null

                analyzer.analyze(1L)

                Then("status = FAILED. 자동 재시도는 하지 않는다") {
                    analysis.status shouldBe AnalysisStatus.FAILED
                }
            }

            When("식품DB에 없는 음식이면") {
                val analyzer = MealAnalyzer(repository, fileService, foodMatcher, objectMapper, client)
                val analysis = pendingAnalysis(30L)
                every { repository.findByIdOrNull(1L) } returns analysis
                every { fileService.download(30L) } returns FileContent(byteArrayOf(1), "image/jpeg")
                every { client.recognizeFoods(any(), any()) } returns listOf(recognized)
                every { foodMatcher.match("제육볶음") } returns null

                analyzer.analyze(1L)

                val item = objectMapper.readValue<AnalysisResult>(analysis.resultJson).photos[0].items[0]

                // 수량과 영양소가 같은 기준(1인분)에서 나와야 한다. 수량만 portion을 곱하고
                // 영양소는 모델이 부른 값을 그대로 쓰면 「200g 안에 매크로 440g」 같은 값이 저장된다.
                Then("수량과 영양소 모두 1인분 값에 portion을 곱한다 — 300g × 0.5 = 150g") {
                    item.quantityG shouldBe 150.0
                    item.kcal shouldBe 200.0
                    item.carbsG shouldBe 15.0
                    item.proteinG shouldBe 12.5
                    item.fatG shouldBe 9.0
                    item.foodCode shouldBe null
                    item.source shouldBe NutritionSource.LLM_ESTIMATED
                }

                Then("주의 영양소도 같은 배수를 탄다 — 0으로 두면 하루 나트륨이 늘 실제보다 낮게 잡힌다") {
                    item.sugarG shouldBe 3.75
                    item.sodiumMg shouldBe 440.0
                    item.fiberG shouldBe 1.1
                }

                Then("탄단지 합이 수량을 넘지 않는다 — 물리적으로 불가능한 값이 저장되면 안 된다") {
                    (item.carbsG + item.proteinG + item.fatG <= item.quantityG) shouldBe true
                }
            }

            // 원재료성식품 원본에는 1인분 컬럼이 아예 없어 523행 전부 기본값 200g이다.
            // 그대로 쓰면 달걀 한 개(50g)가 4배로 잡힌다.
            When("식품DB의 1인분이 기본값으로 채워진 행이면") {
                val analyzer = MealAnalyzer(repository, fileService, foodMatcher, objectMapper, client)
                val analysis = pendingAnalysis(32L)
                every { repository.findByIdOrNull(1L) } returns analysis
                every { fileService.download(32L) } returns FileContent(byteArrayOf(1), "image/jpeg")
                // 모델은 달걀 1인분을 50g으로 본다. 식품DB의 200g은 컬럼이 없어 채워진 기본값이다.
                every { client.recognizeFoods(any(), any()) } returns
                    listOf(recognized.copy(name = "달걀", servingWeightG = 50.0, portion = 2.0))
                every { foodMatcher.match("달걀") } returns
                    dummyFood(
                        name = "달걀",
                        dataset = FoodDataset.RAW,
                        servingSizeG = FoodPolicy.DEFAULT_SERVING_SIZE_G,
                        servingSizeKnown = false,
                        kcalPer100g = 156.0,
                        carbsPer100g = 1.0,
                        proteinPer100g = 12.0,
                        fatPer100g = 11.0,
                    )

                analyzer.analyze(1L)

                val item = objectMapper.readValue<AnalysisResult>(analysis.resultJson).photos[0].items[0]

                Then("양은 모델이 준 1인분 중량을 쓴다 — 50g × 2인분 = 100g") {
                    item.quantityG shouldBe 100.0
                }

                Then("밀도는 식품DB 값을 그대로 쓴다 — 156kcal/100g × 1.0 = 156kcal") {
                    item.kcal shouldBe 156.0
                    item.source shouldBe NutritionSource.DB_MATCHED
                }
            }

            When("모델이 1인분 중량을 포장 단위로 답하면") {
                val analyzer = MealAnalyzer(repository, fileService, foodMatcher, objectMapper, client)
                val analysis = pendingAnalysis(31L)
                every { repository.findByIdOrNull(1L) } returns analysis
                every { fileService.download(31L) } returns FileContent(byteArrayOf(1), "image/jpeg")
                // 치킨 한 박스 2kg — 한 사람이 한 번에 먹는 양이 아니다.
                every { client.recognizeFoods(any(), any()) } returns listOf(recognized.copy(servingWeightG = 2000.0, portion = 1.0))
                every { foodMatcher.match("제육볶음") } returns null

                analyzer.analyze(1L)

                val item = objectMapper.readValue<AnalysisResult>(analysis.resultJson).photos[0].items[0]

                Then("기본 1인분으로 되돌린다 — 식품DB의 포장 총중량을 거르는 것과 같은 기준이다") {
                    item.quantityG shouldBe 200.0
                }

                Then("영양소는 모델 값을 그대로 둔다 — 중량만 보수적으로 잡고 열량을 지우지 않는다") {
                    item.kcal shouldBe 400.0
                }
            }
        }

        Given("실패한 사진만 재인식") {
            When("2장 중 뒤엣것만 실패한 상태에서 retry 하면") {
                val analyzer = MealAnalyzer(repository, fileService, foodMatcher, objectMapper, client)
                val analysis =
                    MealAnalysis(
                        user = user,
                        resultJson =
                            objectMapper.writeValueAsString(
                                AnalysisResult(
                                    listOf(
                                        AnalyzedPhoto(
                                            fileId = 40L,
                                            failed = false,
                                            items =
                                                listOf(
                                                    AnalyzedItem(
                                                        foodName = "김치찌개",
                                                        foodCode = "D1",
                                                        quantityG = 200.0,
                                                        kcal = 300.0,
                                                        carbsG = 10.0,
                                                        proteinG = 20.0,
                                                        fatG = 15.0,
                                                        source = NutritionSource.DB_MATCHED,
                                                    ),
                                                ),
                                        ),
                                        AnalyzedPhoto(fileId = 41L, failed = true),
                                    ),
                                ),
                            ),
                    ).withId(1L)
                every { repository.findByIdOrNull(1L) } returns analysis
                every { fileService.download(41L) } returns FileContent(byteArrayOf(2), "image/jpeg")
                every { client.recognizeFoods(any(), any()) } returns listOf(recognized)
                every { foodMatcher.match("제육볶음") } returns dummyFood()

                analyzer.retryFailed(1L)

                val result = objectMapper.readValue<AnalysisResult>(analysis.resultJson)

                Then("성공한 사진은 다시 호출하지 않는다 — 비용이 이중으로 나가고 결과가 흔들린다") {
                    verify(exactly = 0) { fileService.download(40L) }
                    verify(exactly = 1) { client.recognizeFoods(any(), any()) }
                }

                Then("기존 결과는 그대로 남고 실패했던 사진만 채워진다") {
                    result.photos[0].items[0].foodName shouldBe "김치찌개"
                    result.photos[1].failed shouldBe false
                    result.photos[1].items.size shouldBe 1
                }
            }
        }

        Given("API 키가 없어 클라이언트 빈이 없으면") {
            When("인식을 돌리면") {
                val analyzer = MealAnalyzer(repository, fileService, foodMatcher, objectMapper, null)

                Then("isAvailable이 false다 — 서비스가 요청 단계에서 막는다") {
                    analyzer.isAvailable shouldBe false
                }
            }
        }
    })
