package com.toy.backend.diet.food

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.parser.PartTree

/**
 * `@Query` 문자열을 직접 본다.
 *
 * **이건 동작 검증이 아니다.** 이 저장소에는 리포지토리 통합 테스트 기반이 없어(전부 mockk
 * 단위 테스트라 DB가 안 뜬다) 정렬이 실제로 그 순서를 내는지 확인할 방법이 없다. 그래도
 * 무검증으로 두면 정렬을 지워도 아무 데서도 안 걸리므로, **문구가 사라지는 것만이라도 잡는다.**
 *
 * 정렬이 왜 있어야 하는지는 `FoodRepository`의 주석과
 * `docs/superpowers/specs/2026-08-02-food-nutrition-provenance-design.md` 함정 2·3 참고.
 */
class FoodRepositoryQueryTest :
    BehaviorSpec({
        val queries =
            FoodRepository::class.java.methods
                .filter { it.isAnnotationPresent(Query::class.java) }
                .associate { it.name to it.getAnnotation(Query::class.java).value }

        Given("Food 조회 쿼리들은") {
            Then("네 개 모두 @Query다 — 파생 쿼리로 되돌아가면 정렬이 사라진다") {
                queries.keys.sorted() shouldBe
                    listOf(
                        "findBestByDatasetAndNormalizedName",
                        "searchByDatasetAndNormalizedName",
                        "searchByDatasetAndText",
                        "searchByText",
                    )
            }

            Then("전부 추정으로 채운 행을 뒤로 민다 — 원본 값을 가진 행이 이겨야 한다") {
                queries.forEach { (name, jpql) ->
                    withClue(name) {
                        jpql shouldContain "case when f.estimatedFields is null then 0 else 1 end"
                    }
                }
            }

            Then("검색 쿼리는 추정 여부를 이름 길이보다 앞에 둔다") {
                // 짧은 이름의 추정 행보다 긴 이름의 원본 행이 낫다. 순서가 뒤집히면
                // 되살린 피자 12,000행이 「피자」 검색 상위 50건을 통째로 차지한다.
                listOf("searchByText", "searchByDatasetAndText", "searchByDatasetAndNormalizedName")
                    .forEach { name ->
                        val jpql = queries.getValue(name)
                        withClue(name) {
                            (jpql.indexOf("estimatedFields") < jpql.indexOf("length(")) shouldBe true
                        }
                    }
            }

            Then("완전일치는 단건이 아니라 목록을 돌려준다 — 같은 이름이 3,452행 중복이라 단건이면 예외가 난다") {
                FoodRepository::class.java
                    .getMethod(
                        "findBestByDatasetAndNormalizedName",
                        FoodDataset::class.java,
                        String::class.java,
                        org.springframework.data.domain.Pageable::class.java,
                    ).returnType shouldBe List::class.java
            }
        }

        // **파생 쿼리는 이름이 곧 쿼리라 오타가 컴파일에 안 걸린다.** 파싱은 기동할 때 일어나고,
        // 실패하면 리포지토리 빈이 안 만들어져 **앱이 통째로 안 뜬다.** 목으로 대체하는 단위
        // 테스트는 이름을 읽지도 않으므로 아무것도 못 잡는다.
        //
        // 실제로 그렇게 터진 적이 있다 — `existsByDatasetAndCodeNotStartingWith`를 넣었더니
        // 파서가 `code` 다음의 `Not`을 속성으로 읽고 「No property 'not' found for type 'String'」로
        // 죽었다(`NotStartingWith`는 지원 키워드가 아니다). 그래서 파서를 여기서 직접 돌린다.
        Given("파생 쿼리 이름들은") {
            val derived =
                FoodRepository::class.java.declaredMethods
                    .filterNot { it.isAnnotationPresent(Query::class.java) }

            Then("하나도 빠짐없이 파서를 통과한다 — 못 통과하면 기동 시점에 앱이 안 뜬다") {
                derived shouldNotBe emptyList<java.lang.reflect.Method>()
                derived.forEach {
                    withClue("${it.name} 가 파싱되지 않는다") {
                        shouldNotThrowAny { PartTree(it.name, Food::class.java) }
                    }
                }
            }
        }
    })
