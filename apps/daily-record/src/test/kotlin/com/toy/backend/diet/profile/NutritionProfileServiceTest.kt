package com.toy.backend.diet.profile

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyProfile
import com.toy.backend.user.UserRepository
import com.toy.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class NutritionProfileServiceTest :
    BehaviorSpec({
        val repository = mockk<NutritionProfileRepository>()
        val userRepository = mockk<UserRepository>()
        val service = NutritionProfileService(repository, userRepository)

        val user = dietUser()

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("프로필 저장") {
            When("처음 저장하면") {
                every { repository.findByUser(user) } returns null
                every { repository.save(any()) } answers { (firstArg() as NutritionProfile).withId(1L) }

                service.save(
                    "testuser",
                    NutritionProfileRequest(
                        heightCm = 175.0,
                        weightKg = 70.0,
                        activityLevel = ActivityLevel.MODERATE,
                        goal = DietGoal.MAINTAIN,
                    ),
                )

                Then("목표 4개가 계산되어 함께 저장된다") {
                    verify {
                        repository.save(
                            match {
                                it.targetKcal > 0 &&
                                    it.targetCarbsG > 0 &&
                                    it.targetProteinG > 0 &&
                                    it.targetFatG > 0
                            },
                        )
                    }
                }
            }

            When("생년월일이 없는 사용자면") {
                val noBirth = dummyUser(username = "nobirth", id = 2L)
                every { userRepository.findByUsername("nobirth") } returns noBirth
                every { repository.findByUser(noBirth) } returns null

                Then("INVALID_REQUEST — BMR을 계산할 수 없다") {
                    val e =
                        shouldThrow<CustomException> {
                            service.save(
                                "nobirth",
                                NutritionProfileRequest(175.0, 70.0, ActivityLevel.MODERATE, DietGoal.MAINTAIN),
                            )
                        }
                    e.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }
        }

        Given("몸무게만 갱신") {
            When("매일 잰 몸무게를 올리면") {
                val profile = dummyProfile(user = user, weightKg = 70.0)
                every { repository.findByUser(user) } returns profile

                service.updateWeight("testuser", WeightUpdateRequest(weightKg = 68.0))

                Then("몸무게가 바뀌고 목표가 재계산된다") {
                    profile.weightKg shouldBe 68.0
                    // 나이가 LocalDate.now()에 달려 있어 정확한 값을 못 박는다(그 검증은
                    // NutritionTargetCalculatorTest가 한다). 몸무게가 줄면 목표도 줄어든다는
                    // 관계는 나이와 무관하게 성립한다.
                    profile.targetKcal shouldBeLessThan 2509
                    profile.targetCarbsG shouldBeLessThan 345
                }

                Then("키·활동량·목표는 보존된다") {
                    profile.heightCm shouldBe 175.0
                    profile.activityLevel shouldBe ActivityLevel.MODERATE
                    profile.goal shouldBe DietGoal.MAINTAIN
                }
            }

            When("프로필이 없으면") {
                every { repository.findByUser(user) } returns null

                Then("PROFILE_NOT_FOUND — 키·활동량을 모르면 목표를 못 만든다") {
                    val e = shouldThrow<CustomException> { service.updateWeight("testuser", WeightUpdateRequest(68.0)) }
                    e.errorCode shouldBe DietErrorCode.PROFILE_NOT_FOUND
                }
            }
        }

        Given("프로필 조회") {
            When("저장된 프로필이 있으면") {
                every { repository.findByUser(user) } returns dummyProfile(user = user)

                val response = service.get("testuser")

                Then("계산된 목표를 함께 반환") {
                    response.targetKcal shouldBe 2509
                    response.targetCarbsG shouldBe 345
                }
            }
        }
    })
