package com.toy.backend.diet.profile

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyProfile
import com.toy.backend.user.Gender
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

        // 목표치는 gender·birthDate로 계산되는데 예전에는 save·updateWeight에서만 다시
        // 계산됐다. 성별을 잘못 넣었다가 고쳐도 목표치가 그대로였고, 그 낡은 값이 끼니 확정
        // 시 Meal에 **영구 스냅샷**돼 나중에 고쳐도 되돌아가지 않았다.
        Given("인적사항 변경에 따른 목표 재계산") {
            When("성별이 여성으로 바뀌면") {
                val female = dietUser(gender = Gender.FEMALE)
                val profile = dummyProfile(user = female)
                every { repository.findByUser(female) } returns profile

                service.recalculateTargets(female)

                Then("저장된 목표치가 따라 바뀐다") {
                    // Mifflin-St Jeor은 여성 상수가 더 작다(-161 vs +5). 같은 키·몸무게라면
                    // 남성 기준으로 계산된 기존 값보다 반드시 작아진다 — 나이와 무관한 관계다.
                    profile.targetKcal shouldBeLessThan 2509
                    profile.targetCarbsG shouldBeLessThan 345
                }
            }

            When("프로필이 아직 없는 사용자면") {
                every { repository.findByUser(user) } returns null

                Then("아무 일도 일어나지 않는다 — 인적사항 수정이 실패하면 안 된다") {
                    // 인적사항 수정은 영양 프로필과 독립적인 기능이다. 여기서 던지면
                    // 프로필이 없는 사용자의 이름 변경까지 통째로 실패한다.
                    service.recalculateTargets(user)
                }
            }

            When("성별이 비워진 사용자면") {
                // User.updateProfile은 gender·birthDate를 받은 값으로 **그대로 덮는다**
                // (null이면 지운다). 그래서 이름만 보낸 요청이 성별을 비울 수 있다.
                val noGender = dummyUser(username = "nogender", id = 9L)
                val profile = dummyProfile(user = noGender)
                every { repository.findByUser(noGender) } returns profile

                service.recalculateTargets(noGender)

                Then("예외 없이 기존 목표치를 남겨 둔다 — 계산식을 세울 수 없다") {
                    profile.targetKcal shouldBe 2509
                }
            }
        }
    })
