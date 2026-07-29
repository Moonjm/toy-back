package com.toy.backend.diet.analysis

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.dietUser
import com.toy.backend.file.FileService
import com.toy.backend.user.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import tools.jackson.module.kotlin.jacksonObjectMapper

class MealAnalysisServiceTest :
    BehaviorSpec({
        val repository = mockk<MealAnalysisRepository>()
        val userRepository = mockk<UserRepository>()
        val fileService = mockk<FileService>()
        val analyzer = mockk<MealAnalyzer>()
        val objectMapper = jacksonObjectMapper()
        val service = MealAnalysisService(repository, userRepository, fileService, analyzer, objectMapper)

        val user = dietUser()

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { analyzer.isAvailable } returns true
        }

        Given("분석 생성") {
            When("사진 3장을 올리면") {
                every { repository.save(any()) } answers { (firstArg() as MealAnalysis).withId(1L) }
                justRun { analyzer.analyze(1L) }

                val id = service.create("testuser", AnalysisCreateRequest(fileIds = listOf(1L, 2L, 3L)))

                Then("PENDING으로 저장하고 id를 즉시 반환한다") {
                    id shouldBe 1L
                    verify { repository.save(match { it.status == AnalysisStatus.PENDING }) }
                }
            }

            When("사진이 6장이면") {
                Then("PHOTO_LIMIT_EXCEEDED — 장수가 곧 비용·지연이다") {
                    val e =
                        shouldThrow<CustomException> {
                            service.create("testuser", AnalysisCreateRequest(fileIds = (1L..6L).toList()))
                        }
                    e.errorCode shouldBe DietErrorCode.PHOTO_LIMIT_EXCEEDED
                }
            }

            When("사진이 하나도 없으면") {
                Then("INVALID_REQUEST") {
                    val e = shouldThrow<CustomException> { service.create("testuser", AnalysisCreateRequest(emptyList())) }
                    e.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }

            When("API 키가 없어 인식기가 준비되지 않았으면") {
                every { analyzer.isAvailable } returns false

                Then("LLM_UNAVAILABLE — 진행시켜 봐야 FAILED 레코드만 쌓인다") {
                    val e = shouldThrow<CustomException> { service.create("testuser", AnalysisCreateRequest(listOf(1L))) }
                    e.errorCode shouldBe DietErrorCode.LLM_UNAVAILABLE
                }
            }
        }

        Given("분석 조회") {
            val analysis =
                MealAnalysis(
                    user = user,
                    status = AnalysisStatus.COMPLETED,
                    resultJson =
                        objectMapper.writeValueAsString(
                            AnalysisResult(
                                listOf(
                                    AnalyzedPhoto(
                                        fileId = 7L,
                                        failed = false,
                                        items =
                                            listOf(
                                                AnalyzedItem(
                                                    foodName = "제육볶음",
                                                    foodCode = "D1",
                                                    quantityG = 150.0,
                                                    kcal = 300.0,
                                                    carbsG = 15.0,
                                                    proteinG = 30.0,
                                                    fatG = 8.0,
                                                    source = NutritionSource.DB_MATCHED,
                                                ),
                                            ),
                                    ),
                                    AnalyzedPhoto(fileId = 8L, failed = true),
                                ),
                            ),
                        ),
                ).withId(5L)

            When("본인 분석이면") {
                every { repository.findByIdOrNull(5L) } returns analysis
                every { fileService.getPresignedUrls(listOf(7L, 8L)) } returns mapOf(7L to "https://u7", 8L to "https://u8")

                val response = service.get("testuser", 5L)

                Then("사진별 결과와 10분 만료 URL을 함께 준다") {
                    response.status shouldBe AnalysisStatus.COMPLETED
                    response.photos[0].url shouldBe "https://u7"
                    response.photos[0].items[0].foodName shouldBe "제육볶음"
                    response.photos[1].failed shouldBe true
                    response.photos[1].items.size shouldBe 0
                }
            }

            When("타인 분석이면") {
                val other = dietUser(username = "other", id = 2L)
                every { repository.findByIdOrNull(6L) } returns MealAnalysis(user = other, resultJson = "{\"photos\":[]}").withId(6L)

                Then("RESOURCE_NOT_FOUND — 존재 자체를 숨긴다") {
                    val e = shouldThrow<CustomException> { service.get("testuser", 6L) }
                    e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("재인식") {
            When("실패한 사진이 있으면") {
                val analysis =
                    MealAnalysis(
                        user = user,
                        status = AnalysisStatus.COMPLETED,
                        resultJson =
                            objectMapper.writeValueAsString(
                                AnalysisResult(listOf(AnalyzedPhoto(fileId = 9L, failed = true))),
                            ),
                    ).withId(7L)
                every { repository.findByIdOrNull(7L) } returns analysis
                justRun { analyzer.retryFailed(7L) }

                service.retry("testuser", 7L)

                Then("PENDING으로 되돌리고 실패한 사진만 다시 돌린다") {
                    analysis.status shouldBe AnalysisStatus.PENDING
                    verify { analyzer.retryFailed(7L) }
                }
            }

            When("실패한 사진이 없으면") {
                val analysis =
                    MealAnalysis(
                        user = user,
                        status = AnalysisStatus.COMPLETED,
                        resultJson =
                            objectMapper.writeValueAsString(
                                AnalysisResult(listOf(AnalyzedPhoto(fileId = 9L, failed = false))),
                            ),
                    ).withId(8L)
                every { repository.findByIdOrNull(8L) } returns analysis

                Then("ANALYSIS_NOT_RETRYABLE — 성공한 사진을 재호출하지 않는다") {
                    val e = shouldThrow<CustomException> { service.retry("testuser", 8L) }
                    e.errorCode shouldBe DietErrorCode.ANALYSIS_NOT_RETRYABLE
                }
            }

            // markPending()은 resultJson의 failed 표시를 지우지 않는다. 실패한 사진 유무만 보면
            // 진행 중인 재인식이 끝나기 전에 또 들어온 요청이 검사를 통과해 유료 호출이 겹친다.
            When("이미 재인식이 진행 중이면(PENDING인데 failed 표시가 남아 있음)") {
                val analysis =
                    MealAnalysis(
                        user = user,
                        status = AnalysisStatus.PENDING,
                        resultJson =
                            objectMapper.writeValueAsString(
                                AnalysisResult(listOf(AnalyzedPhoto(fileId = 9L, failed = true))),
                            ),
                    ).withId(9L)
                every { repository.findByIdOrNull(9L) } returns analysis

                Then("ANALYSIS_IN_PROGRESS — 재시도 버튼을 두 번 눌러도 호출이 겹치지 않는다") {
                    val e = shouldThrow<CustomException> { service.retry("testuser", 9L) }
                    e.errorCode shouldBe DietErrorCode.ANALYSIS_IN_PROGRESS
                    verify(exactly = 0) { analyzer.retryFailed(any()) }
                }
            }
        }

        Given("확인 취소") {
            When("본인 분석을 삭제하면") {
                val analysis = MealAnalysis(user = user, resultJson = "{\"photos\":[]}").withId(9L)
                every { repository.findByIdOrNull(9L) } returns analysis
                justRun { repository.delete(analysis) }

                service.delete("testuser", 9L)

                Then("레코드만 지운다 — 사진은 TEMP로 남아 정리 배치가 수거한다") {
                    verify { repository.delete(analysis) }
                    verify(exactly = 0) { fileService.detachFiles(any()) }
                }
            }
        }
    })
