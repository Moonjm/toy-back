package com.toy.backend.dispatch.image

import com.toy.backend.common.exception.CustomException
import com.toy.backend.dispatch.DispatchErrorCode
import org.springframework.stereotype.Component
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * 조각 하나. `index`는 왼쪽부터의 순서다 — 0번이 성명 컬럼을 담은 왼쪽 조각이다.
 *
 * **픽셀 범위는 담지 않는다.** 어느 픽셀이 어느 날짜인지 알 방법이 없어 조각 범위와
 * 날짜를 대응시킬 수 없다. 잘못 센 칸은 `visibleDays` 멤버십으로 칸 단위로 걸러낸다.
 */
data class ImageSlice(
    val index: Int,
    val base64: String,
)

/** 가장자리 한 줄이 단색인지 볼 때 허용할 채널 차이. JPEG 압축 노이즈를 흡수한다. */
private const val TOLERANCE = 12

/** 조각이 차지하는 비율. 0.55면 55%씩 두 조각이 되어 10%가 겹친다. */
private const val SLICE_RATIO = 0.55

private const val TARGET_LONG_EDGE = 1600

/**
 * 사진을 인식하기 좋게 다듬는다. **이 전처리가 없으면 모델은 빈 칸을 숫자로 메운다** —
 * 표가 가로로 길어 한 칸이 몇 픽셀밖에 안 되고, 그 크기에서는 빈 칸과 숫자가 구분되지
 * 않기 때문이다(설계 문서 함정 1).
 *
 * 표 경계를 찾지는 않는다. **여백 트리밍과 2등분까지**로 실측 100%가 나왔고,
 * 표 경계 검출은 별개 문제다.
 */
@Component
class DispatchImageSlicer {
    fun slice(bytes: ByteArray): List<ImageSlice> {
        val source =
            ImageIO.read(ByteArrayInputStream(bytes))
                ?: throw CustomException(DispatchErrorCode.IMAGE_UNREADABLE)
        val trimmed = trim(source)

        val width = trimmed.width
        val sliceWidth = (width * SLICE_RATIO).toInt().coerceAtLeast(1)
        val ranges =
            listOf(
                0 to sliceWidth.coerceAtMost(width),
                (width - sliceWidth).coerceAtLeast(0) to width,
            )

        return ranges.mapIndexed { index, (xFrom, xTo) ->
            val piece = trimmed.getSubimage(xFrom, 0, xTo - xFrom, trimmed.height)
            ImageSlice(
                index = index,
                base64 = Base64.getEncoder().encodeToString(toPng(upscale(piece, TARGET_LONG_EDGE))),
            )
        }
    }

    private fun toPng(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}

/**
 * 가장자리부터 **단색인 행·열을 걷어낸다.** 확대 캡처의 검은 여백과 밴드 스크린샷의
 * 흰 배경이 모두 여기서 빠진다. 색을 가정하지 않고 「그 줄이 한 가지 색인가」만 본다.
 */
internal fun trim(image: BufferedImage): BufferedImage {
    var top = 0
    var bottom = image.height - 1
    var left = 0
    var right = image.width - 1

    while (top < bottom && isUniformRow(image, top)) top++
    while (bottom > top && isUniformRow(image, bottom)) bottom--
    while (left < right && isUniformColumn(image, left, top, bottom)) left++
    while (right > left && isUniformColumn(image, right, top, bottom)) right--

    return image.getSubimage(left, top, right - left + 1, bottom - top + 1)
}

/**
 * 긴 변을 `targetLongEdge`로 키운다. **줄이지는 않는다** — 이미 큰 사진을 줄이면
 * 애써 확보한 해상도를 버리게 된다.
 */
internal fun upscale(
    image: BufferedImage,
    targetLongEdge: Int,
): BufferedImage {
    val longEdge = maxOf(image.width, image.height)
    if (longEdge >= targetLongEdge) return image

    val scale = targetLongEdge.toDouble() / longEdge
    val width = (image.width * scale).toInt()
    val height = (image.height * scale).toInt()

    val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = scaled.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(image, 0, 0, width, height, null)
    g.dispose()
    return scaled
}

private fun isUniformRow(
    image: BufferedImage,
    y: Int,
): Boolean {
    val first = image.getRGB(0, y)
    for (x in 1 until image.width) {
        if (!isNear(first, image.getRGB(x, y))) return false
    }
    return true
}

private fun isUniformColumn(
    image: BufferedImage,
    x: Int,
    top: Int,
    bottom: Int,
): Boolean {
    val first = image.getRGB(x, top)
    for (y in top + 1..bottom) {
        if (!isNear(first, image.getRGB(x, y))) return false
    }
    return true
}

private fun isNear(
    a: Int,
    b: Int,
): Boolean =
    abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= TOLERANCE &&
        abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= TOLERANCE &&
        abs((a and 0xFF) - (b and 0xFF)) <= TOLERANCE
