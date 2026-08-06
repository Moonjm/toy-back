package com.toy.backend.diet.chat

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * **응답 규칙에서 의도적으로 벗어난다.** 저장소 관례는 「생성은 `@ResponseCreated`로 201 +
 * Location」인데 여기서는 200 + 바디를 쓴다 — 201을 주고 클라이언트가 답을 다시 조회하게 만들면
 * 왕복이 두 번이 되고, 사용자가 화면에서 기다리는 대화에서 그 지연은 그대로 체감된다.
 * 답변 자체가 데이터라 `DataResponseBody`가 맞다. **관례 위반이 아니라 기록된 예외다.**
 */
@Tag(name = "하루 채팅", description = "하루 평가에 대해 되묻는 대화")
@RestController
@RequestMapping("/diet/days/{date}/chat")
class DietChatController(
    private val service: DietChatService,
) {
    @PostMapping
    @Operation(summary = "질문 — 답변을 만들어 저장하고 그대로 돌려준다")
    fun ask(
        @Parameter(description = "기준 날짜", example = "2026-08-01")
        @PathVariable
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @Valid @RequestBody request: DietChatRequest,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<DietChatMessageResponse>> =
        ResponseEntity.ok(DataResponseBody(service.ask(authentication.name, date, request.message)))

    @GetMapping
    @Operation(summary = "대화 조회 — LLM 키가 없어도 동작한다")
    fun list(
        @Parameter(description = "기준 날짜", example = "2026-08-01")
        @PathVariable
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<DietChatResponse>> = ResponseEntity.ok(DataResponseBody(service.history(authentication.name, date)))
}
