#!/usr/bin/env python3
"""식약처 `전국통합식품영양성분정보(음식)` 원본 CSV를 적재용 CSV로 정제한다.

준비 단계에서 1회만 돌린다(런타임 호출이 아니다).

  1. https://www.data.go.kr/data/15100070/standard.do 에서 CSV를 내려받는다.
     그리드 다운로드는 5만 건 제한이 있어 전량이 안 나오면 같은 페이지의 오픈API로 페이징해 덤프한다.
  2. python3 scripts/build-food-csv.py 원본.csv \
       apps/daily-record/src/main/resources/food/food-nutrition.csv

`영양성분함량 기준량` 컬럼이 따로 있다는 것은 값이 항상 100g 기준이 아니라는 뜻이다.
기준량이 200g인 행을 100g당으로 착각하면 그 음식만 칼로리가 2배로 잡힌다. 여기서 전부 100g
기준으로 환산하고, 기준량을 파싱할 수 없는 행은 버린다 — 틀린 값을 넣느니 매칭 실패로
LLM 추정에 맡기는 편이 낫다.

`1인(회)분량 참고량`은 실제 배포본에서 전 행이 비어 있는 경우가 있었다(2026-07-29 판,
19,495/19,495건). 이 컬럼이 비면 같은 뜻인 `식품중량`(그 음식 1회 제공량의 중량, g 또는
mL)으로 폴백한다 — 둘 다 없을 때만 결측으로 세고 빈 칸으로 내보낸다(Kotlin 파서가
`FoodPolicy.DEFAULT_SERVING_SIZE_G`로 채운다).
"""

import csv
import re
import sys
import unicodedata

OUT_HEADER = [
    "code", "servingSizeG", "kcalPer100g",
    "carbsPer100g", "proteinPer100g", "fatPer100g",
    "sugarPer100g", "sodiumMgPer100g", "fiberPer100g", "name",
]

# 원본 헤더는 공백·괄호 표기가 판번마다 조금씩 달라 부분 문자열로 찾는다.
COLUMN_HINTS = {
    "code": ["식품코드"],
    "name": ["식품명"],
    "basis": ["영양성분함량기준량", "기준량"],
    "kcal": ["에너지(kcal)", "에너지"],
    "carbs": ["탄수화물"],
    "protein": ["단백질"],
    "fat": ["지방"],
    "sugar": ["당류"],
    "sodium": ["나트륨"],
    "fiber": ["식이섬유"],
    # 1인분 기준량 우선, 없으면 식품중량으로 폴백한다(아래 main 참고). 둘 다 선택 컬럼이다.
    "servingReference": ["1인(회)분량참고량", "1회분량", "분량참고량"],
    "servingWeight": ["식품중량"],
}

# 이 컬럼들은 없어도 되는 컬럼이다 — 없으면 그 경로로는 못 채울 뿐 전체를 중단하지 않는다.
# 당류·나트륨·식이섬유는 판본이 바뀌어 컬럼이 빠지더라도 탄단지는 살려야 하므로 포함한다.
OPTIONAL_COLUMNS = {"servingReference", "servingWeight", "sugar", "sodium", "fiber"}

AMOUNT_PATTERN = re.compile(r"([0-9]+(?:\.[0-9]+)?)\s*(g|ml|mL|ML|㎖)")


def find_column(fieldnames, hints):
    squeezed = {name.replace(" ", ""): name for name in fieldnames}
    for hint in hints:
        for key, original in squeezed.items():
            if hint.replace(" ", "") in key:
                return original
    return None


def parse_amount(text):
    """`200g`, `1회 제공량(200g)`, `100 mL` → 200.0 / 100.0. 못 읽으면 None."""
    if not text:
        return None
    match = AMOUNT_PATTERN.search(text.replace(",", ""))
    if match:
        return float(match.group(1))
    # 단위 없이 숫자만 있는 경우도 g으로 본다.
    bare = text.strip().replace(",", "")
    try:
        return float(bare)
    except ValueError:
        return None


def clean_name(text):
    """BOM(U+FEFF)과 그 밖의 보이지 않는 제어문자를 뗀다.

    가공식품DB 원본 일부 행에 BOM이 이름 맨 앞에 붙어 있다(`﻿우리밀 한입 스콘 초코칩`).
    정규화(`FoodNameNormalizer`)는 이런 문자를 걸러내지 않는 문자 클래스([\\p{L}\\p{N}] 매칭
    대상이 아니므로 그대로 통과)라, 여기서 안 지우면 사람이 보기엔 같은 이름인데 완전일치가
    영영 안 맞는 행이 생긴다. NFC로 정규화해 자모 분리 등 조합 차이도 맞춘다.
    """
    text = text.replace("﻿", "")
    text = "".join(ch for ch in text if unicodedata.category(ch) != "Cc")
    text = unicodedata.normalize("NFC", text)
    return text.strip()


def to_float(text):
    if text is None:
        return None
    cleaned = text.strip().replace(",", "")
    if cleaned in ("", "-", "N/A"):
        return None
    try:
        return float(cleaned)
    except ValueError:
        return None


def main(src_path, dst_path):
    with open(src_path, encoding="utf-8-sig", newline="") as src:
        reader = csv.DictReader(src)
        columns = {key: find_column(reader.fieldnames, hints) for key, hints in COLUMN_HINTS.items()}
        missing = [key for key, value in columns.items() if value is None and key not in OPTIONAL_COLUMNS]
        if missing:
            sys.exit(f"원본에서 컬럼을 찾지 못했습니다: {missing}\n헤더: {reader.fieldnames}")

        rows = []
        dropped = 0
        malformed = 0
        serving_from_reference = 0
        serving_from_weight = 0
        serving_missing = 0
        for row in reader:
            basis = parse_amount(row.get(columns["basis"]))
            kcal = to_float(row.get(columns["kcal"]))
            carbs = to_float(row.get(columns["carbs"]))
            protein = to_float(row.get(columns["protein"]))
            fat = to_float(row.get(columns["fat"]))
            code = (row.get(columns["code"]) or "").strip()
            name = clean_name((row.get(columns["name"]) or "").replace("\n", " "))
            # 주의 영양소는 결측이어도 행을 버리지 않는다 — Kotlin 파서가 빈 칸을 0으로 채운다.
            sugar = to_float(row.get(columns["sugar"])) if columns["sugar"] else None
            sodium = to_float(row.get(columns["sodium"])) if columns["sodium"] else None
            fiber = to_float(row.get(columns["fiber"])) if columns["fiber"] else None

            # 출력은 인용부호 없이 그대로 join한다(아래 참고). code는 원본 값을 그대로
            # 옮기는 유일한 자유 텍스트 필드라 여기 구분자·개행이 섞이면 컬럼이 밀린다.
            # 다른 통계(결측/폴백 카운트)를 오염시키지 않도록 나머지 판단보다 먼저 버린다.
            if "," in code or "\n" in code:
                malformed += 1
                continue

            if not code or not name or not basis or basis <= 0 or None in (kcal, carbs, protein, fat):
                dropped += 1
                continue

            factor = 100.0 / basis

            # 1인분 기준량이 비어 있으면 식품중량(1회 제공량 중량)으로 폴백한다.
            # 실제 배포본에서 전자가 전 행 결측이었던 적이 있어 둘 다 없을 때만 결측으로 센다.
            reference = parse_amount(row.get(columns["servingReference"])) if columns["servingReference"] else None
            if reference:
                serving = reference
                serving_from_reference += 1
            else:
                weight = parse_amount(row.get(columns["servingWeight"])) if columns["servingWeight"] else None
                if weight:
                    serving = weight
                    serving_from_weight += 1
                else:
                    serving = None
                    serving_missing += 1

            # kcal/carbs/protein/fat/serving은 전부 f-string으로 다시 포맷한 숫자라
            # 콤마가 섞일 수 없다(파이썬 float 포맷은 로캘과 무관하게 '.'을 쓴다) —
            # 구조 손상은 code에서만 온다.
            rows.append([
                code,
                f"{serving:.1f}" if serving else "",
                f"{kcal * factor:.2f}",
                f"{carbs * factor:.2f}",
                f"{protein * factor:.2f}",
                f"{fat * factor:.2f}",
                f"{sugar * factor:.2f}" if sugar is not None else "",
                f"{sodium * factor:.1f}" if sodium is not None else "",
                f"{fiber * factor:.2f}" if fiber is not None else "",
                name,
            ])

    # 같은 식품코드가 중복되면 뒤엣것을 버린다(코드에 unique 제약이 있다).
    seen, unique_rows = set(), []
    for row in rows:
        if row[0] in seen:
            continue
        seen.add(row[0])
        unique_rows.append(row)

    # csv.writer(quoting=QUOTE_NONE)는 필드에 구분자(,)가 있으면 escapechar 없이는
    # 예외를 던진다 — 음식명에는 쉼표가 들어갈 수 있고 그걸 그대로 살리는 게 의도이므로
    # csv 모듈 대신 직접 join한다. Kotlin 쪽 파서도 인용부호를 해석하지 않는 단순
    # split(',')이라 왕복이 맞는다.
    with open(dst_path, "w", encoding="utf-8", newline="") as dst:
        dst.write(",".join(OUT_HEADER) + "\n")
        for row in unique_rows:
            dst.write(",".join(row) + "\n")

    print(
        f"적재 대상 {len(unique_rows)}건, 버린 행 {dropped}건, "
        f"구조 이상으로 버린 행 {malformed}건, 1인분량 결측 {serving_missing}건",
    )
    print(
        f"  1인분량 출처 — 1인(회)분량 참고량 {serving_from_reference}건, "
        f"식품중량 폴백 {serving_from_weight}건, 결측 {serving_missing}건",
    )
    print("※ 결측률이 높으면 FoodPolicy.DEFAULT_SERVING_SIZE_G(200g) 가정을 재검토할 것")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: build-food-csv.py <원본.csv> <출력.csv>")
    main(sys.argv[1], sys.argv[2])
