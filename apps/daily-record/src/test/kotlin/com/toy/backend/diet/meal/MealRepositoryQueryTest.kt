package com.toy.backend.diet.meal

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldNotBe
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.parser.PartTree

/**
 * **파생 쿼리는 이름이 곧 쿼리라 오타가 컴파일에 안 걸린다.** 파싱은 기동할 때 일어나고,
 * 실패하면 리포지토리 빈이 안 만들어져 앱이 통째로 안 뜬다. 목으로 대체하는 단위 테스트는
 * 이름을 읽지도 않으므로 아무것도 못 잡는다.
 *
 * `FoodRepository`에서 실제로 그렇게 터졌다(`existsByDatasetAndCodeNotStartingWith` →
 * 「No property 'not' found for type 'String'」). 그래서 파서를 여기서 직접 돌린다.
 */
class MealRepositoryQueryTest :
    BehaviorSpec({
        Given("파생 쿼리 이름들은") {
            val derived =
                MealRepository::class.java.declaredMethods
                    .filterNot { it.isAnnotationPresent(Query::class.java) }

            Then("하나도 빠짐없이 파서를 통과한다 — 못 통과하면 기동 시점에 앱이 안 뜬다") {
                derived shouldNotBe emptyList<java.lang.reflect.Method>()
                derived.forEach {
                    withClue("${it.name} 가 파싱되지 않는다") {
                        shouldNotThrowAny { PartTree(it.name, Meal::class.java) }
                    }
                }
            }
        }
    })
