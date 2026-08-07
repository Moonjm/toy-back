package com.toy.backend.diet.chat

import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.llm.ChatTurn
import com.toy.backend.diet.llm.OpenRouterClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime

class DietChatServiceTest :
    BehaviorSpec({
        val store = mockk<DietChatStore>()
        val client = mockk<OpenRouterClient>()
        val date = LocalDate.of(2026, 8, 1)

        fun context(
            feedback: String? = "총평",
            history: List<ChatTurn> = emptyList(),
        ) = ChatContext(
            dataBlock = "[2026-08-01 (토) 먹은 끼니]\n- 점심: 제육볶음 (555kcal)\n[직전 7일]\n- 07-30 (목) 58점 2930kcal",
            dayFeedback = feedback,
            history = history,
        )

        val answer =
            DietChatMessageResponse(
                id = 2L,
                type = ChatMessageType.TEXT,
                date = date,
                role = ChatRole.ASSISTANT,
                createdAt = LocalDateTime.now(),
                content = "나트륨 때문입니다",
            )

        Given("정상 흐름이면") {
            val service = DietChatService(store, client)
            val turns = slot<List<ChatTurn>>()
            every { store.loadContext("testuser", date) } returns
                context(history = listOf(ChatTurn("user", "이전 질문"), ChatTurn("assistant", "이전 답")))
            every { client.chat(DietChatPrompts.SYSTEM_PROMPT, capture(turns)) } returns "나트륨 때문입니다"
            every { store.append("testuser", date, "왜 낮아?", "나트륨 때문입니다") } returns answer

            val result = service.ask("testuser", date, "왜 낮아?")

            Then("저장된 답을 돌려준다") {
                result shouldBe answer
            }

            // 데이터 → 총평 → 히스토리 → 이번 질문. 순서가 어긋나면 모델이 맥락을 잃는다.
            Then("턴 순서가 데이터·총평·히스토리·이번 질문이다") {
                turns.captured.map { it.role } shouldBe listOf("user", "assistant", "user", "assistant", "user")
                turns.captured.first().content shouldContain "[직전 7일]"
                turns.captured[1].content shouldBe "총평"
                turns.captured.last().content shouldBe "왜 낮아?"
            }
        }

        Given("하루 피드백이 아직 없으면") {
            val service = DietChatService(store, client)
            val turns = slot<List<ChatTurn>>()
            every { store.loadContext("testuser", date) } returns context(feedback = null)
            every { client.chat(any(), capture(turns)) } returns "답"
            every { store.append(any(), any(), any(), any()) } returns answer

            service.ask("testuser", date, "질문")

            Then("오프닝 턴을 넣지 않는다 — assistant가 안 들어간다") {
                turns.captured.map { it.role } shouldBe listOf("user", "user")
            }
        }

        // 질문만 저장되면 히스토리가 user, user, assistant로 어긋나 다음 턴 프롬프트가 깨진다.
        Given("LLM이 null을 돌려주면") {
            val service = DietChatService(store, client)
            every { store.loadContext("testuser", date) } returns context()
            every { client.chat(any(), any()) } returns null

            Then("CHAT_FAILED이고 아무것도 저장하지 않는다") {
                val e = shouldThrow<CustomException> { service.ask("testuser", date, "질문") }
                e.errorCode shouldBe DietErrorCode.CHAT_FAILED
                verify(exactly = 0) { store.append(any(), any(), any(), any()) }
            }
        }

        Given("키가 없으면") {
            val service = DietChatService(store, null)

            Then("LLM_UNAVAILABLE이고 컨텍스트를 읽지도 않는다") {
                val e = shouldThrow<CustomException> { service.ask("testuser", date, "질문") }
                e.errorCode shouldBe DietErrorCode.LLM_UNAVAILABLE
                verify(exactly = 0) { store.loadContext(any(), any()) }
            }

            // 저장된 대화를 보여주는 데는 LLM이 필요 없다(함정 4).
            Then("조회는 그대로 동작한다") {
                val response = DietChatPageResponse(emptyList(), null)
                every { store.page("testuser", null, 30) } returns response
                service.page("testuser", null, 30) shouldBe response
            }
        }

        // coerceIn을 지우면 size=100000 한 번이 대화 전량 조회가 되고, size=0이면
        // 스토어가 `rows.take(0)` 뒤 `page.last()`에서 500을 낸다 — 스토어로 넘어가는
        // 값을 직접 잡아 이 방어가 실제로 동작하는지 확인한다.
        Given("size가 범위를 벗어나면") {
            val service = DietChatService(store, client)
            val size = slot<Int>()
            every { store.page(any(), any(), capture(size)) } returns DietChatPageResponse(emptyList(), null)

            service.page("testuser", null, 0)
            val zeroCoercedTo = size.captured

            service.page("testuser", null, 101)
            val overCoercedTo = size.captured

            service.page("testuser", null, -1)
            val negativeCoercedTo = size.captured

            Then("0이면 1로 올린다") {
                zeroCoercedTo shouldBe 1
            }

            Then("101이면 100으로 내린다") {
                overCoercedTo shouldBe 100
            }

            Then("음수면 1로 올린다") {
                negativeCoercedTo shouldBe 1
            }
        }

        // 데이터 블록이 저장되면 대화 도중 끼니를 고쳤을 때 낡은 숫자가 굳는다.
        Given("저장 호출은") {
            val service = DietChatService(store, client)
            val question = slot<String>()
            every { store.loadContext("testuser", date) } returns context()
            every { client.chat(any(), any()) } returns "답"
            every { store.append("testuser", date, capture(question), "답") } returns answer

            service.ask("testuser", date, "왜 낮아?")

            Then("사용자가 쓴 질문만 넘긴다 — 데이터 블록이 섞이지 않는다") {
                question.captured shouldBe "왜 낮아?"
                question.captured shouldNotContain "[직전 7일]"
            }
        }
    })
