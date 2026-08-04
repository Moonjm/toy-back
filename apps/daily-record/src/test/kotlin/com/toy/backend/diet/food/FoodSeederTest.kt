package com.toy.backend.diet.food

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter
import kotlin.math.abs

/**
 * 공공데이터 CSV는 저장소에 없고 각자 만든다(`resources/food/README.md`). **파일이 있는지에 따라
 * 결과가 달라지면 안 되므로 실제 적재를 타지 않는 경로만 본다** — 결함이 있던 자리는 「무엇을 이미
 * 적재됐다고 볼 것인가」이지 적재 자체가 아니다.
 *
 * 수동 등록분은 예외다. 그 파일은 커밋돼 있으니 항상 존재하고, 실제로 적재되는지가 요점이다.
 */
class FoodSeederTest :
    BehaviorSpec({
        val repository = mockk<FoodRepository>()
        val jdbcTemplate = mockk<JdbcTemplate>()
        val seeder = FoodSeeder(repository, jdbcTemplate)

        Given("이미 적재된 데이터셋이 있는 상태로 기동하면") {
            val seeded = slot<Collection<Food>>()
            every { repository.existsByDatasetAndCodeNotStartingWith(any(), any()) } returns true
            every {
                jdbcTemplate.batchUpdate(any<String>(), capture(seeded), any(), any<ParameterizedPreparedStatementSetter<Food>>())
            } returns arrayOf(intArrayOf())

            seeder.run(DefaultApplicationArguments())

            Then("데이터셋마다 따로 확인한다 — 전체 행 수로 보면 음식만 적재된 상태에서 가공식품이 영영 안 들어간다") {
                verify(exactly = 1) {
                    repository.existsByDatasetAndCodeNotStartingWith(FoodDataset.DISH, FoodSeeder.MANUAL_CODE_PREFIX)
                }
                verify(exactly = 1) {
                    repository.existsByDatasetAndCodeNotStartingWith(FoodDataset.PROCESSED, FoodSeeder.MANUAL_CODE_PREFIX)
                }
            }

            Then("공공데이터 세 벌은 다시 넣지 않는다 — 기동마다 30만 행을 다시 쓰면 안 된다") {
                // 수동 등록분 한 번을 뺀 나머지가 0이어야 한다.
                verify(exactly = 1) {
                    jdbcTemplate.batchUpdate(
                        any<String>(),
                        any<Collection<Food>>(),
                        any(),
                        any<ParameterizedPreparedStatementSetter<Food>>(),
                    )
                }
            }

            Then("수동 등록분은 데이터셋이 이미 차 있어도 적재한다 — 검사를 타면 첫 기동 이후 새 행이 영영 안 들어간다") {
                seeded.captured.map { it.name } shouldContain "뿌링클 콤보"
                seeded.captured.map { it.dataset }.distinct() shouldBe listOf(FoodDataset.DISH)
            }
        }

        // 첫 기동에 `food/food-nutrition.csv`가 없으면 수동 등록분만 `DISH`로 들어간다. `.gitignore`가
        // 공공데이터 CSV를 막고 수동분만 예외로 두므로 **새로 클론한 상태가 바로 그 상태다.**
        // 그 뒤 CSV를 채우고 다시 뜨면 음식DB가 적재돼야 한다.
        Given("수동 등록분만 DISH에 들어 있는 상태로 기동하면") {
            every {
                repository.existsByDatasetAndCodeNotStartingWith(FoodDataset.DISH, FoodSeeder.MANUAL_CODE_PREFIX)
            } returns false
            every {
                repository.existsByDatasetAndCodeNotStartingWith(neq(FoodDataset.DISH), FoodSeeder.MANUAL_CODE_PREFIX)
            } returns true
            every {
                jdbcTemplate.batchUpdate(any<String>(), any<Collection<Food>>(), any(), any<ParameterizedPreparedStatementSetter<Food>>())
            } returns arrayOf(intArrayOf())

            seeder.run(DefaultApplicationArguments())

            Then("적재 여부를 수동 행을 뺀 기준으로 묻는다 — 수동 57행이 음식DB 18,000행을 영영 막으면 안 된다") {
                verify(exactly = 1) {
                    repository.existsByDatasetAndCodeNotStartingWith(FoodDataset.DISH, FoodSeeder.MANUAL_CODE_PREFIX)
                }
            }
        }

        Given("수동 등록 CSV는") {
            val rows =
                ClassPathResource("food/manual-food-nutrition.csv").inputStream.bufferedReader().use {
                    FoodCsvParser.parse(it.lineSequence(), FoodDataset.DISH).toList()
                }

            Then("저장소에 커밋돼 있다 — `.gitignore`가 `*.csv`를 막고 있어 예외가 없으면 배포본에서 사라진다") {
                rows.size shouldNotBe 0
            }

            // 적재 여부 검사가 이 접두로 수동 행을 걸러낸다. 코드 규칙이 깨지면 검사가 수동 행을
            // 못 알아보고, 수동분만 들어간 상태가 「음식DB 적재 완료」로 굳는다.
            Then("코드가 모두 `MANUAL-` 접두다 — 적재 여부 검사가 이 규칙으로 수동 행을 걸러낸다") {
                rows.filterNot { it.code.startsWith(FoodSeeder.MANUAL_CODE_PREFIX) } shouldBe emptyList()
            }

            Then("브랜드로 찾을 수 있다 — 식품명에 「bhc」가 없어 이 값이 없으면 브랜드 검색에 안 걸린다") {
                val puringkle = rows.single { it.name == "뿌링클 콤보" }
                puringkle.maker shouldBe "bhc"
                puringkle.normalizedMaker shouldBe "bhc"
            }

            Then("1인분 중량은 모르는 값으로 둔다 — 콤보 총중량은 공식 표에 없다") {
                // false여야 인식 경로가 모델이 준 중량을 쓴다. true면 근거 없는 200g이 「DB가 아는 값」이 된다.
                rows.single { it.name == "뿌링클 콤보" }.servingSizeKnown shouldBe false
            }

            Then("공식 표에 있는 값은 그대로 들어간다") {
                val puringkle = rows.single { it.name == "뿌링클 콤보" }
                puringkle.kcalPer100g shouldBe 294.0
                puringkle.proteinPer100g shouldBe 22.3
                puringkle.sugarPer100g shouldBe 2.5
                puringkle.sodiumMgPer100g shouldBe 340.7
                puringkle.saturatedFatPer100g shouldBe 3.3
            }

            Then("롯데리아 56건도 브랜드를 달고 들어간다 — 식품명에 「롯데리아」가 없어 이 값이 없으면 못 찾는다") {
                rows.count { it.maker == "롯데리아" } shouldBe 56
                rows.single { it.name == "리아 두툼새우" }.normalizedMaker shouldBe "롯데리아"
            }

            Then("공공데이터에 이미 있는 메뉴는 넣지 않는다 — 같은 버거가 두 번 보이면 안 된다") {
                // `버거_더블X2 버거`가 공공데이터에 있어 `더블엑스투버거`를 뺐다. 29건이 그렇다.
                rows.none { it.name == "더블엑스투버거" } shouldBe true
                rows.none { it.name == "데리버거" } shouldBe true
            }

            // ── 아래 셋은 **모든 행**에 건다. 탄수화물·지방이 추정값이라 손으로 넣거나 스크립트를
            //    고칠 때 조용히 틀어질 수 있는데, 세 검산 중 하나는 반드시 걸린다.
            Then("추정한 탄·지는 열량이 맞아떨어져야 한다 — 아트워터 계수로 검산한다") {
                // 브랜드가 탄수화물·총지방을 공개하지 않아 잔여 열량을 나눈 값이다(README 참조).
                // 이 검산이 깨지면 추정이 아니라 오타다.
                rows.forEach {
                    val computed = 4 * it.carbsPer100g + 4 * it.proteinPer100g + 9 * it.fatPer100g
                    withClue(it.name) { abs(computed - it.kcalPer100g) shouldBeLessThan 1.0 }
                }
            }

            Then("당류는 탄수화물을 넘지 않는다 — 당류는 탄수화물의 일부다") {
                // 이 하한이 없으면 사이다(잔여 열량의 93%가 당류)에 없는 지방이 생긴다.
                rows.forEach {
                    withClue(it.name) { it.sugarPer100g shouldBeLessThanOrEqual it.carbsPer100g + 0.01 }
                }
            }

            Then("포화지방은 총지방을 넘지 않는다") {
                rows.forEach {
                    withClue(it.name) {
                        it.saturatedFatPer100g shouldBeLessThanOrEqual it.fatPer100g + 0.01
                    }
                }
            }
        }
    })
