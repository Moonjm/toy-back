package com.toy.backend.dispatch.image

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * **전처리가 이 기능의 성패를 가른다.** 같은 사진·같은 모델인데 전처리 유무로 인식
 * 정확도가 0%와 100%로 갈렸다(설계 문서의 실측표).
 *
 * 사진은 두 형태로 온다 — 시간표만 확대해 찍어 **위아래가 검은 여백**인 것과,
 * 밴드 글을 통째로 찍어 **흰 배경**인 것. 둘 다 걷어내야 한다.
 */
class DispatchImageSlicerTest :
    BehaviorSpec({
        /** 가운데에만 내용이 있고 가장자리는 단색인 이미지를 만든다. */
        fun imageWithBorder(
            border: Color,
            width: Int = 400,
            height: Int = 200,
            contentX: Int = 50,
            contentY: Int = 60,
            contentW: Int = 300,
            contentH: Int = 80,
        ): BufferedImage {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = border
            g.fillRect(0, 0, width, height)
            // 내용부는 **행 방향으로도 열 방향으로도** 단색이 아니어야 트리밍이 멈춘다.
            // 세로 줄무늬를 그리면 각 열이 단색이 되어 isUniformColumn이 내용부까지 깎는다.
            for (x in contentX until contentX + contentW) {
                for (y in contentY until contentY + contentH) {
                    g.color = if (((x / 10) + (y / 10)) % 2 == 0) Color.WHITE else Color.BLUE
                    g.fillRect(x, y, 1, 1)
                }
            }
            g.dispose()
            return image
        }

        fun toBytes(image: BufferedImage): ByteArray {
            val out = ByteArrayOutputStream()
            ImageIO.write(image, "png", out)
            return out.toByteArray()
        }

        Given("검은 여백에 둘러싸인 이미지") {
            val trimmed = trim(imageWithBorder(Color.BLACK))

            Then("여백이 걷힌다") {
                trimmed.width shouldBe 300
                trimmed.height shouldBe 80
            }
        }

        Given("흰 여백에 둘러싸인 이미지") {
            val trimmed = trim(imageWithBorder(Color.WHITE))

            Then("흰 여백도 걷힌다") {
                trimmed.width shouldBe 300
                trimmed.height shouldBe 80
            }
        }

        Given("여백이 없는 이미지") {
            val image = imageWithBorder(Color.BLACK, contentX = 0, contentY = 0, contentW = 400, contentH = 200)
            val trimmed = trim(image)

            Then("원본 크기가 유지된다") {
                trimmed.width shouldBe 400
                trimmed.height shouldBe 200
            }
        }

        Given("가로로 긴 표 사진") {
            val slices = DispatchImageSlicer().slice(toBytes(imageWithBorder(Color.BLACK)))

            Then("두 조각으로 나뉜다") {
                slices.size shouldBe 2
            }

            Then("왼쪽부터 순서가 매겨진다 — 0번에만 성명 컬럼이 있다") {
                slices.map { it.index } shouldBe listOf(0, 1)
            }

            Then("각 조각이 base64로 나온다") {
                slices.all { it.base64.isNotBlank() } shouldBe true
            }
        }

        Given("작은 이미지") {
            val upscaled = upscale(imageWithBorder(Color.BLACK, width = 200, height = 100), targetLongEdge = 1600)

            Then("긴 변이 목표치로 커진다") {
                upscaled.width shouldBe 1600
                upscaled.height shouldBe 800
            }
        }

        Given("이미 충분히 큰 이미지") {
            val upscaled = upscale(imageWithBorder(Color.BLACK, width = 2000, height = 1000), targetLongEdge = 1600)

            Then("줄이지는 않는다") {
                upscaled.width shouldBe 2000
            }
        }
    })
