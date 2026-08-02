#!/usr/bin/env python3
"""탄수화물·지방이 빠진 행을 열량에서 되짚어 채우는 규칙.

`build-food-csv.py`(공공데이터)와 `build-lotteria-csv.py`(브랜드 공식표)가 **같은 함수를
쓴다.** 규칙이 갈라지면 같은 음식이 어느 경로로 들어왔느냐에 따라 다른 값을 갖는다.

## 왜 필요한가

- 공공데이터 음식DB 19,495행 중 13,405행이 탄수화물·지방 없이 들어온다. 그런데 **전부**
  열량과 단백질을 갖고 있다(당류 99%, 포화지방 96%). 지금은 이 행들을 통째로 버려서
  피자 4,227 · 커피 1,647 · 스무디 1,011 · 파파존스 528 · 스타벅스 213이 검색에 없다.
- 프랜차이즈 공식 영양성분표는 **어린이 기호식품 의무표시 항목**(열량·단백질·나트륨·당류·
  포화지방)만 싣는다. 탄수화물·총지방이 아예 없다.

## 규칙

1. 잔여 열량(`열량 − 4×단백질`)을 **같은 분류의 탄:지 열량 비중**으로 나눈다
2. 물리 하한 둘을 건다 — 당류 ≤ 탄수화물, 포화지방 ≤ 총지방
3. 열량은 원본 값을 그대로 지킨다
4. **채운 필드만** 추정으로 표시한다(한쪽만 비어 있는 행이 752건 있다)

실측 오차는 `apps/daily-record/src/main/resources/food/README.md` 참고.
"""
import statistics as st

KCAL_PER_G_CARBS = 4.0
KCAL_PER_G_PROTEIN = 4.0
KCAL_PER_G_FAT = 9.0

# `Food.estimatedFields`에 들어갈 수 있는 값. Kotlin 쪽 `FoodPolicy.ESTIMABLE_FIELDS`와 같아야 한다.
ESTIMABLE_FIELDS = ("carbs", "protein", "fat")

# 이 미만이면 비중을 못 믿는다. 5건 기준으로 되살릴 13,405행의 92.1%가 커버된다.
MIN_REFERENCE_SAMPLES = 5

# 기준 표본에서 뺄 행 — 원본에도 `4탄+4단+9지`가 열량과 안 맞는 행이 섞여 있다.
ATWATER_TOLERANCE = 0.15

# 기준 표본이 부족한 분류를 비슷한 분류에 붙인다.
#
# **차류(허브차·홍차·녹차)는 넣지 않았다** — 열량이 1kcal이라 잔여 열량이 0에 가깝고,
# 무슨 비중으로 나누든 결과가 0이다. 붙일 이유가 없다.
PREFIX_ALIASES = {
    "와플": "도넛",
    "허니브레드": "케이크",
    "카스텔라": "케이크",
    "기타빵": "케이크",
    "타르트": "케이크",
    "소세지빵": "크림빵",
}


def prefix_of(name):
    """음식DB 식품명은 `분류_이름` 꼴이다(`피자_뉴욕 오리진 피자 오리지널 (L)`).

    **접두사로 잡는다. 부분 문자열로 잡으면 안 된다** — 롯데리아 작업 때 「콜라」가
    `머핀_게랑트쇼콜라`를, 「소스」가 `삼각김밥_참치마요네즈`를 끌어와 비중이 통째로
    오염됐다. 숫자는 멀쩡해 보이고 아무 데서도 안 걸린다.
    """
    return name.split("_")[0] if "_" in name else ""


def add_sample(shares, prefix, kcal, carbs, protein, fat):
    """기준 표본 하나를 더한다. 못 쓸 행이면 조용히 넘어간다.

    **되살릴 행 판정에 이 함수를 쓰면 안 된다.** 여기는 아트워터 정합까지 보는데, 되살릴
    행은 애초에 탄·지가 없어 아트워터를 계산할 수 없다. 두 판정을 한 곳에 합치면
    「13,405행」이 「13,459행」이 되는 식으로 조용히 어긋난다(실측 중 실제로 겪었다).
    """
    if not prefix or None in (kcal, carbs, protein, fat) or kcal <= 0:
        return
    energy = KCAL_PER_G_CARBS * carbs + KCAL_PER_G_FAT * fat
    if energy <= 0:
        return
    atwater = KCAL_PER_G_CARBS * carbs + KCAL_PER_G_PROTEIN * protein + KCAL_PER_G_FAT * fat
    if abs(atwater - kcal) > kcal * ATWATER_TOLERANCE:
        return
    shares.setdefault(prefix, []).append(KCAL_PER_G_CARBS * carbs / energy)


def share_for(prefix, shares, min_samples=MIN_REFERENCE_SAMPLES):
    """분류의 탄 열량 비중 중앙값. 표본이 모자라면 별칭을 한 번 따라간 뒤 포기한다."""
    for candidate in (prefix, PREFIX_ALIASES.get(prefix)):
        samples = shares.get(candidate) if candidate else None
        if samples and len(samples) >= min_samples:
            return st.median(samples)
    return None


def estimate(kcal, protein, carbs, fat, sugar, saturated, share):
    """빠진 탄수화물·지방을 채운다.

    돌려주는 값: `(carbs, fat, estimated_fields, conflict)`.
    채울 수 없으면 `None` — 호출자가 그 행을 버린다.

    `estimated_fields`에는 **실제로 채운 것만** 담는다. 원본에 있던 값까지 추정으로 표시하면
    표시가 무의미해진다. 탄수화물만 비어 있는 행이 647건, 지방만 비어 있는 행이 105건 있다.
    """
    if kcal is None or protein is None:
        return None
    if carbs is not None and fat is not None:
        return carbs, fat, [], False

    rest = kcal - KCAL_PER_G_PROTEIN * protein
    sugar = sugar or 0.0
    saturated = saturated or 0.0
    if rest <= 0:
        # 단백질만으로 열량이 다 차는 행. **그래도 물리 하한은 지킨다** — 당류 25g이 적힌
        # 젤라또를 탄수화물 0으로 내보내면 「당류 > 탄수화물」인 행이 나온다. 원본이
        # 자기모순이므로 열량 쪽을 포기한다.
        return (carbs if carbs is not None else sugar,
                fat if fat is not None else saturated,
                [f for f, v in (("carbs", carbs), ("fat", fat)) if v is None],
                True)

    if carbs is not None:
        # 탄수화물은 알고 지방만 모른다 — 비중을 쓸 필요가 없다.
        return carbs, max((rest - KCAL_PER_G_CARBS * carbs) / KCAL_PER_G_FAT, saturated, 0.0), ["fat"], False
    if fat is not None:
        return max((rest - KCAL_PER_G_FAT * fat) / KCAL_PER_G_CARBS, sugar, 0.0), fat, ["carbs"], False

    if share is None:
        return None
    carbs, fat, conflict = split(rest, sugar, saturated, share)
    return carbs, fat, ["carbs", "fat"], conflict


def split(rest, sugar, saturated, share):
    """잔여 열량을 탄수화물과 지방으로 나눈다. 물리 하한을 지키고 열량을 맞춘다.

    - **당류 ≤ 탄수화물** — 없으면 사이다(잔여 열량의 93%가 당류)에 없는 지방이 생긴다
    - **포화지방 ≤ 총지방**

    두 하한이 동시에 잔여 열량을 넘으면 원본끼리 모순인 것이라, 그때는 하한 둘을 지키고
    열량을 포기한다(`conflict=True`로 알린다).
    """
    carbs = max(rest * share / KCAL_PER_G_CARBS, sugar)
    fat = (rest - KCAL_PER_G_CARBS * carbs) / KCAL_PER_G_FAT
    if fat < saturated:
        fat = saturated
        carbs = (rest - KCAL_PER_G_FAT * fat) / KCAL_PER_G_CARBS
        if carbs < sugar:
            return sugar, saturated, True
    return max(carbs, 0.0), max(fat, 0.0), False
