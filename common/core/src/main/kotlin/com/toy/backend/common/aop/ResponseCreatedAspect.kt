package com.toy.backend.common.aop

import com.toy.backend.common.annotation.ResponseCreated
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import java.net.URI

@Aspect
@Component
class ResponseCreatedAspect {
    @Around("@annotation(responseCreated)")
    fun handleResponseCreated(
        joinPoint: ProceedingJoinPoint,
        responseCreated: ResponseCreated,
    ): ResponseEntity<Void> {
        val result = joinPoint.proceed() as ResponseEntity<*>
        val id = result.body

        val method = (joinPoint.signature as MethodSignature).method
        var path = responseCreated.path

        method.parameters.forEachIndexed { index, param ->
            val pathVariable = param.getAnnotation(PathVariable::class.java) ?: return@forEachIndexed
            val name = pathVariable.value.ifEmpty { pathVariable.name.ifEmpty { param.name } }
            path = path.replace("{$name}", joinPoint.args[index]?.toString() ?: "")
        }

        path = path.replace("{id}", id?.toString() ?: "")

        return ResponseEntity.created(URI.create(path)).build()
    }
}
