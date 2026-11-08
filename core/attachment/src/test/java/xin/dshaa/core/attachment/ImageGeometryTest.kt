package xin.dshaa.core.attachment

import kotlin.test.Test
import kotlin.test.assertEquals

/** 归一化尺寸算法：与上游 `requestImageDimensions` 逐位一致（小图不放大、等比、预算内取整）。 */
class ImageGeometryTest {

    @Test
    fun smallImageNotEnlarged() {
        assertEquals(800 to 600, ImageNormalizer.targetDimensions(800, 600))
        assertEquals(2048 to 2048, ImageNormalizer.targetDimensions(2048, 2048))
    }

    @Test
    fun landscapeScaledIntoPixelBudget() {
        // 4000x3000：scale=0.5912… → 2364x1773（4,191,372 ≤ 2048²）
        assertEquals(2364 to 1773, ImageNormalizer.targetDimensions(4000, 3000))
    }

    @Test
    fun panoramaKeepsAspect() {
        // 5000x1000：4578x916（4579 超预算，上游算法逐像素回退一位）
        assertEquals(4578 to 916, ImageNormalizer.targetDimensions(5000, 1000))
    }

    @Test
    fun portraitKeepsAspect() {
        // 3000x4000 → 1773x2364（与 landscape 对称）
        assertEquals(1773 to 2364, ImageNormalizer.targetDimensions(3000, 4000))
    }

    @Test
    fun longEdgeCapApplies() {
        // 默认预算下长边上限 8192 不会触发；单独验证上限逻辑
        assertEquals(8192 to 8192, ImageNormalizer.targetDimensions(10000, 10000, maxPixels = 100_000_000, maxDimension = 8192))
        assertEquals(8192 to 4096, ImageNormalizer.targetDimensions(20000, 10000, maxPixels = 400_000_000, maxDimension = 8192))
    }
}
