package com.toy.backend.common.config

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.media.Schema
import org.springframework.stereotype.Component
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.allSuperclasses
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * 코틀린 타입만으로 이미 필수인 필드를 스웨거 `required`로 올린다.
 *
 * swagger-core는 잭슨 2 매퍼로 타입을 읽는데(본체는 잭슨 3다) 그 매퍼에는 코틀린 모듈이
 * 없다. 그래서 `@NotBlank` 같은 검증 애너테이션이 붙은 필드만 필수로 나오고,
 * `val yearMonth: YearMonth`는 선택값처럼 보였다.
 */
@Component
class KotlinRequiredModelConverter : ModelConverter {
    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: MutableIterator<ModelConverter>,
    ): Schema<*>? {
        val resolved = (if (chain.hasNext()) chain.next().resolve(type, context, chain) else null) ?: return null
        val kClass = kotlinClassOf(type) ?: return resolved
        val schema = definitionOf(resolved, context) ?: return resolved
        val properties = schema.properties ?: return resolved

        requiredNamesOf(kClass, properties.keys).forEach { name ->
            // 검증 애너테이션으로 이미 올라간 이름을 다시 넣으면 required에 중복으로 쌓인다.
            if (schema.required?.contains(name) == true) return@forEach
            schema.addRequiredItem(name)
        }
        return resolved
    }

    /**
     * **스웨거가 이미 내보낸 이름만 판정한다.** `@JsonIgnore`로 빠졌거나 이름이 바뀐 것은
     * 애초에 후보가 아니다.
     */
    private fun requiredNamesOf(
        kClass: KClass<*>,
        emitted: Set<String>,
    ): List<String> {
        // 상속한 생성자까지 본다. 응답 봉투의 status는 상위 ResponseBody 생성자에 있다.
        val parameters =
            (listOf(kClass) + kClass.allSuperclasses)
                .mapNotNull { it.primaryConstructor }
                .flatMap { it.parameters }
                .mapNotNull { parameter -> parameter.name?.let { it to parameter } }
                .toMap()

        return emitted.filter { name ->
            val parameter = parameters[name]
            if (parameter != null) {
                // **기본값이 있으면 제외한다** — 빠뜨려도 역직렬화가 되니 실제로 선택값이다.
                return@filter !parameter.isOptional && !parameter.type.isMarkedNullable
            }
            // 생성자에 없는 읽기 전용 값은 JSON으로 채울 수 없다 — 늘 서버가 채워 내보낸다.
            val property = kClass.memberProperties.firstOrNull { it.name == name } ?: return@filter false
            property !is KMutableProperty1<*, *> && !property.returnType.isMarkedNullable
        }
    }

    /**
     * `$ref`로 돌아오면 본체는 정의 목록에 있다. 돌려받은 껍데기에 붙여봐야 문서에 안 나온다.
     */
    private fun definitionOf(
        resolved: Schema<*>,
        context: ModelConverterContext,
    ): Schema<*>? {
        val ref = resolved.`$ref` ?: return resolved
        return context.definedModels[ref.substringAfterLast('/')]
    }

    private fun kotlinClassOf(type: AnnotatedType): KClass<*>? {
        // AnnotatedType.type은 Class일 수도 ParameterizedType일 수도 잭슨 JavaType일 수도 있다.
        val raw = runCatching { Json.mapper().constructType(type.type)?.rawClass }.getOrNull() ?: return null
        // 코틀린이 아닌 클래스에 kotlin-reflect를 들이대면 던진다. @Metadata로 먼저 거른다.
        if (!raw.isAnnotationPresent(Metadata::class.java)) return null
        return runCatching { raw.kotlin }.getOrNull()
    }
}
