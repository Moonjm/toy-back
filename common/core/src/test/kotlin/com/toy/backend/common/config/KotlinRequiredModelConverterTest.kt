package com.toy.backend.common.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.swagger.v3.core.converter.ModelConverters
import jakarta.validation.constraints.NotEmpty
import java.math.BigDecimal

private data class SampleRequest(
    val yearMonth: String,
    @field:NotEmpty val items: List<String>,
    val chargedAmount: BigDecimal,
    val dong: String? = null,
    val discountTotal: BigDecimal = BigDecimal.ZERO,
)

private data class SampleNested(
    val request: SampleRequest,
)

private class SampleJava(
    val nothing: String?,
)

private open class SampleEnvelopeBase(
    val status: Int,
    val message: String?,
) {
    val timestamp: String = "2026-08-23"
}

private class SampleEnvelope(
    val data: String?,
) : SampleEnvelopeBase(200, null)

class KotlinRequiredModelConverterTest :
    BehaviorSpec({
        val converters = ModelConverters()
        converters.addConverter(KotlinRequiredModelConverter())

        fun requiredOf(
            type: Class<*>,
            name: String = type.simpleName,
        ) = converters.readAll(type)[name]?.required

        Given("널 불가·기본값 없는 필드가 섞인 요청") {
            val required = requiredOf(SampleRequest::class.java)

            Then("널 불가에 기본값이 없는 것만 필수로 나온다") {
                required shouldContainExactlyInAnyOrder listOf("yearMonth", "items", "chargedAmount")
            }
        }

        Given("검증 애너테이션과 널 불가가 겹친 필드") {
            val required = requiredOf(SampleRequest::class.java)

            Then("required에 중복으로 쌓이지 않는다") {
                required?.count { it == "items" } shouldBe 1
            }
        }

        Given("다른 스키마를 참조하는 타입") {
            val required = requiredOf(SampleNested::class.java)

            Then("\$ref로 감싸여 들어와도 필수가 붙는다") {
                required shouldContainExactlyInAnyOrder listOf("request")
            }
        }

        Given("상위 생성자와 읽기 전용 값을 물려받은 응답 봉투") {
            val required = requiredOf(SampleEnvelope::class.java)

            Then("상속한 생성자의 널 불가와 본문 val까지 필수로 나온다") {
                required shouldContainExactlyInAnyOrder listOf("status", "timestamp")
            }
        }

        Given("널 허용만 있는 타입") {
            Then("required 자체가 생기지 않는다") {
                requiredOf(SampleJava::class.java).shouldBeNull()
            }
        }
    })
