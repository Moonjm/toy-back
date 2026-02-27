package com.toy.backend.common.aop

import com.toy.backend.common.annotation.ResponseCreated
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
        val signature = joinPoint.signature as MethodSignature

        var path = responseCreated.path
        signature.parameterNames.zip(joinPoint.args).forEach { (name, value) ->
            path = path.replace("{$name}", value.toString())
        }
        path = path.replace("{id}", result.body.toString())

        return ResponseEntity.created(URI.create(path)).build()
    }
}
