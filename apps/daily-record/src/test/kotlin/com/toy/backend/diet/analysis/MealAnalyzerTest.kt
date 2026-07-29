package com.toy.backend.diet.analysis

import com.toy.backend.common.entity.withId
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyFood
import com.toy.backend.diet.food.FoodMatcher
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
                estimatedKcal = 400.0,
                estimatedCarbsG = 30.0,
                estimatedProteinG = 25.0,
                estimatedFatG = 18.0,
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
                    dummyFood(servingSizeG = 300.0, kcalPer100g = 200.0, carbsPer100g = 10.0, proteinPer100g = 20.0, fatPer100g = 5.0)

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

                Then("LLM 추정값을 그대로 쓰고 출처를 남긴다") {
                    item.kcal shouldBe 400.0
                    item.carbsG shouldBe 30.0
                    item.foodCode shouldBe null
                    item.source shouldBe NutritionSource.LLM_ESTIMATED
                    item.quantityG shouldBe 100.0 // 기본 1인분 200g × 0.5
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
