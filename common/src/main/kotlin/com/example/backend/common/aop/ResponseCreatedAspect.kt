package com.example.backend.common.aop

import com.example.backend.common.annotation.ResponseCreated
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
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
        val body = result.body

        var path = responseCreated.path
        val placeholders = PLACEHOLDER_REGEX.findAll(path).map { it.groupValues[1] }.toList()

        for (name in placeholders) {
            val arg = getArgument(joinPoint, name)
            val value = arg?.toString() ?: body?.toString() ?: ""
            path = path.replace("{$name}", value)
        }

        return ResponseEntity.created(URI.create(path)).build()
    }

    companion object {
        private val PLACEHOLDER_REGEX = "\\{(\\w+)}".toRegex()
    }

    private fun getArgument(
        joinPoint: ProceedingJoinPoint,
        name: String,
    ): Any? {
        val signature = joinPoint.signature as MethodSignature
        val paramNames = signature.parameterNames
        val args = joinPoint.args

        val index = paramNames.indexOf(name)
        return if (index >= 0) args[index] else null
    }
}
