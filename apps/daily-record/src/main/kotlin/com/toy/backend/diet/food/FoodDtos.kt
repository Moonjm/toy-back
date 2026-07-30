package com.toy.backend.diet.food

data class FoodResponse(
    val code: String,
    val name: String,
    /** 앱이 「가공식품」임을 표시할 수 있게 함께 내려준다. */
    val dataset: FoodDataset,
    val servingSizeG: Double,
    /**
     * `servingSizeG`가 원본에서 온 값인지. **false면 앱이 이 값을 기본 수량으로 쓰면 안 된다** —
     * 근거 없는 200g이라 달걀 한 개가 4배(200g·312kcal)로 담긴다.
     *
     * 검색 결과의 상당수가 여기 걸린다 — 원재료 523건 전부, 가공식품 31%, 음식 21%.
     * 상한을 걸기 전에는 1800g처럼 눈에 띄게 이상해서 사용자가 고쳤지만, 지금은 200g이라
     * **그럴듯해 보인다.** 인식 경로는 이 값을 보고 모델이 준 1인분 중량으로 갈아타는데,
     * 이 필드가 없으면 앱은 같은 판단을 할 재료조차 못 받는다.
     */
    val servingSizeKnown: Boolean,
    val kcalPer100g: Double,
    val carbsPer100g: Double,
    val proteinPer100g: Double,
    val fatPer100g: Double,
    val sugarPer100g: Double,
    val sodiumMgPer100g: Double,
    val fiberPer100g: Double,
    /**
     * 포화지방산·트랜스지방산·콜레스테롤. **검색 결과에만 있다** — 자주 먹는 음식과 사진 인식
     * 항목은 `Food`가 아니라 저장된 `MealItem`에서 오므로 이 값을 못 싣는다. 알고 받는 한계다.
     */
    val saturatedFatPer100g: Double,
    val transFatPer100g: Double,
    val cholesterolMgPer100g: Double,
)

fun Food.toResponse(): FoodResponse =
    FoodResponse(
        code = code,
        name = name,
        dataset = dataset,
        servingSizeG = servingSizeG,
        servingSizeKnown = servingSizeKnown,
        kcalPer100g = kcalPer100g,
        carbsPer100g = carbsPer100g,
        proteinPer100g = proteinPer100g,
        fatPer100g = fatPer100g,
        sugarPer100g = sugarPer100g,
        sodiumMgPer100g = sodiumMgPer100g,
        fiberPer100g = fiberPer100g,
        saturatedFatPer100g = saturatedFatPer100g,
        transFatPer100g = transFatPer100g,
        cholesterolMgPer100g = cholesterolMgPer100g,
    )
