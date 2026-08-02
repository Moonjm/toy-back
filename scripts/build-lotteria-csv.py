#!/usr/bin/env python3
"""롯데리아 공식 영양성분표(HTML)를 `manual-food-nutrition.csv` 형식으로 바꾼다.

    python3 scripts/build-lotteria-csv.py lotteria.html food-nutrition.csv > rows.csv

원본: https://www.lotteeatz.com/upload/stg/etc/ria/items.html (robots.txt 허용 경로)

**표에 탄수화물과 총지방이 없다.** 프랜차이즈 영양성분표는 어린이 기호식품 의무표시 항목
(열량·단백질·나트륨·당류·포화지방)만 싣기 때문이다. `Food.carbsPer100g`·`fatPer100g`은
non-null이므로 잔여 열량을 유사 음식의 탄:지 비율로 나눠 채운다. 근거와 실측 오차는
`apps/daily-record/src/main/resources/food/README.md` 참고.

두 번째 인자로 받는 음식DB 정제본이 그 「유사 음식」의 출처다.
"""
import csv
import hashlib
import html
import re
import statistics as st
import sys

import nutrition_estimate as estimator

# 롯데리아 구분 → 탄:지 비중을 빌려 올 음식DB 분류 접두사.
#
# **접두사로 잡는다.** 부분 문자열로 잡았더니 「콜라」가 `머핀_게랑트쇼콜라`를, 「소스」가
# `삼각김밥_참치마요네즈`를 끌어와 비중이 통째로 오염됐다.
REFERENCE = {
    "버거메뉴": ["버거"],
    "리아모닝": ["버거"],
    "쿠팡이츠 '하나만' 메뉴": ["버거"],
    "치킨메뉴": ["닭튀김", "닭다리튀김"],
}
# 구분만으로는 갈리지 않는 것들 — 제품명으로 먼저 본다. 위에서 아래로 처음 걸리는 것을 쓴다.
BY_NAME = [
    (("포테이토", "양념감자"), ["감자튀김"]),
    (("빙수",), ["빙수"]),
    (("토네이도",), ["아이스크림"]),
    (("라떼", "라뗴"), ["라떼"]),
    (("아메리카노", "티", "콜라", "사이다", "에이드"), ["에이드"]),
]
FALLBACK = ["돈가스", "닭튀김"]  # 튀김 사이드(치즈스틱·너겟·오징어링·지파이·돈까스)

# 넣지 않는 구분.
#
# - `버거세트`·치킨 팩은 **열량이 `661kcal ~ 1378kcal` 범위로만 나온다.** 음료 선택에 따라
#   달라지는 조합이라 단일값이 없다. 구성품을 따로 담으면 되므로 손해가 없다.
# - `토핑메뉴`·`소스& 시즈닝`은 **비중을 빌려 올 유사 음식이 음식DB에 없다.** 케첩 9g,
#   치즈토핑 18g처럼 양도 작아 따로 기록할 일이 드물다. 근거 없는 값을 넣느니 뺀다.
EXCLUDED_GROUPS = {"버거세트", "토핑메뉴", "소스& 시즈닝"}

MAKER = "롯데리아"

OUT_HEADER = (
    "code,servingSizeG,kcalPer100g,carbsPer100g,proteinPer100g,fatPer100g,"
    "sugarPer100g,sodiumMgPer100g,fiberPer100g,saturatedFatPer100g,"
    "transFatPer100g,cholesterolMgPer100g,estimatedFields,maker,name"
)
KCAL_CARB = estimator.KCAL_PER_G_CARBS
KCAL_PROTEIN = estimator.KCAL_PER_G_PROTEIN
KCAL_FAT = estimator.KCAL_PER_G_FAT


def num(value):
    if value is None:
        return None
    # `18(33%)` 처럼 1일 영양성분 기준치 비율이 괄호로 붙어 온다.
    text = re.sub(r"\(.*", "", str(value)).replace(",", "").strip()
    try:
        return float(text)
    except ValueError:
        return None


def parse_html(path):
    """`구분`이 rowspan으로 묶여 있어 셀 개수만 세면 분류가 밀린다."""
    source = open(path, encoding="utf-8", errors="replace").read()
    group, items = None, []
    for row in re.findall(r"<tr[^>]*>(.*?)</tr>", source, re.S):
        cells = []
        for match in re.finditer(r"<(t[dh])([^>]*)>(.*?)</\1>", row, re.S):
            text = re.sub(r"\s+", " ", html.unescape(re.sub("<[^>]+>", " ", match.group(3)))).strip()
            cells.append((text, "rowspan" in match.group(2).lower()))
        if not cells or cells[0][0] == "구분":
            continue
        if cells[0][1]:
            group, cells = cells[0][0], cells[1:]
        values = [text for text, _ in cells]
        # 단품은 알레르기 다음 칸(중량)이 숫자다. 세트·팩은 여기에 `661kcal ~ 1378kcal`가 온다.
        if len(values) >= 8 and num(values[2]) is not None:
            items.append(
                {
                    "group": group,
                    "name": values[0],
                    "weight": num(values[2]),
                    "kcal": num(values[3]),
                    "protein": num(values[4]),
                    "sodium": num(values[5]),
                    "sugar": num(values[6]),
                    "saturated": num(values[7]),
                }
            )
    return items


def carb_energy_shares(food_csv):
    """음식DB 분류별 「탄수화물이 탄+지 열량에서 차지하는 비중」. 규칙은 `nutrition_estimate`.

    **추정으로 채운 행은 기준에서 뺀다.** 그 행들의 비중은 정의상 분류 중앙값 그 자체라,
    넣으면 자기가 만든 값으로 자기를 정하는 순환이 된다. 피자는 원본 459행에 추정 4,227행이라
    중앙값이 통째로 합성값으로 바뀐다.
    """
    shares = {}
    for row in csv.DictReader(open(food_csv, encoding="utf-8")):
        if row.get("estimatedFields"):
            continue
        estimator.add_sample(
            shares, estimator.prefix_of(row["name"]),
            num(row["kcalPer100g"]), num(row["carbsPer100g"]),
            num(row["proteinPer100g"]), num(row["fatPer100g"]),
        )
    return {k: v for k, v in shares.items() if len(v) >= 2}


def existing_names(food_csv, maker):
    """공공데이터에 이미 있는 같은 브랜드 메뉴의 이름 집합.

    **이 파일의 목적은 「공공데이터에 없는 것」을 채우는 것이다.** 2026-08-02에 버려지던
    13,405행을 되살리면서 롯데리아 83건이 공공데이터 쪽에도 생겼는데, 그대로 두면
    `버거_더블X2 버거`(공공)와 `더블엑스투버거`(공식표)가 나란히 떠서 같은 메뉴가 두 번 보인다.
    27건이 그렇다. 값은 1~5% 안에서 일치하므로 어느 쪽을 남겨도 같다 — 중복만 없앤다.

    `분류_` 접두사와 공백을 떼고 맞춘다. 공공데이터는 `버거_더블X2 버거`, 공식표는
    `더블엑스투버거`라 그냥 비교하면 한 건도 안 겹친다.
    """
    return {
        squeeze(re.sub(r"^[^_]+_", "", row["name"]))
        for row in csv.DictReader(open(food_csv, encoding="utf-8"))
        if row.get("maker") == maker
    }


def squeeze(name):
    return re.sub(r"[^0-9A-Za-z가-힣]", "", name).lower()


def share_for(item, shares):
    for keywords, prefixes in BY_NAME:
        if any(k in item["name"] for k in keywords):
            return pick(prefixes, shares)
    return pick(REFERENCE.get(item["group"], FALLBACK), shares)


def pick(prefixes, shares):
    pool = [v for p in prefixes for v in shares.get(p, [])]
    if not pool:
        raise SystemExit(f"기준 분류를 음식DB에서 못 찾았다: {prefixes}")
    return st.median(pool), prefixes


def split(kcal, protein, sugar, saturated, share):
    """잔여 열량을 탄수화물과 지방으로 나눈다. 규칙은 `nutrition_estimate`에 있다 —
    공공데이터 경로와 갈라지면 같은 음식이 경로에 따라 다른 값을 갖는다."""
    rest = kcal - KCAL_PROTEIN * protein
    if rest <= 0:
        return 0.0, 0.0, rest < -0.5
    return estimator.split(rest, sugar, saturated, share)


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    items = parse_html(sys.argv[1])
    shares = carb_energy_shares(sys.argv[2])
    already = existing_names(sys.argv[2], MAKER)

    print(OUT_HEADER)
    used, dropped, conflicts = 0, [], []
    for item in items:
        if item["group"] in EXCLUDED_GROUPS:
            dropped.append((item["name"], f"제외 구분({item['group']})"))
            continue
        if squeeze(item["name"]) in already:
            dropped.append((item["name"], "공공데이터에 이미 있다"))
            continue
        weight, kcal = item["weight"], item["kcal"]
        if not weight or weight <= 0 or kcal is None:
            dropped.append((item["name"], "중량 또는 열량 결측"))
            continue
        protein = item["protein"] or 0.0
        sugar = item["sugar"] or 0.0
        saturated = item["saturated"] or 0.0
        share, prefixes = share_for(item, shares)
        carbs, fat, conflict = split(kcal, protein, sugar, saturated, share)
        if conflict:
            conflicts.append(item["name"])

        def per100(value):
            return round(value * 100.0 / weight, 2)

        code = "MANUAL-RIA-" + hashlib.sha1(item["name"].encode()).hexdigest()[:8]
        # 이름에 쉼표가 들어가므로 `name`은 반드시 마지막 컬럼이다(FoodCsvParser 주석 참고).
        print(
            ",".join(
                [
                    code,
                    f"{weight:g}",
                    f"{per100(kcal):g}",
                    f"{per100(carbs):g}",
                    f"{per100(protein):g}",
                    f"{per100(fat):g}",
                    f"{per100(sugar):g}",
                    f"{per100(item['sodium'] or 0.0):g}",
                    "",  # 식이섬유 — 표에 없다
                    f"{per100(saturated):g}",
                    "",  # 트랜스지방 — 표에 없다
                    "",  # 콜레스테롤 — 표에 없다
                    # 표에 탄수화물·총지방이 없어 둘 다 잔여 열량에서 계산한 값이다.
                    "carbs|fat",
                    MAKER,
                    item["name"].replace(",", " "),
                ]
            )
        )
        used += 1

    print(f"적재 대상 {used}건, 제외 {len(dropped)}건", file=sys.stderr)
    for name, why in dropped:
        print(f"  - {name}: {why}", file=sys.stderr)
    if conflicts:
        print(f"※ 당류·포화지방 하한이 열량을 넘어 열량을 못 지킨 행 {len(conflicts)}건: {conflicts}",
              file=sys.stderr)
    print("기준 분류 표본 수: "
          + ", ".join(f"{k}={len(shares[k])}" for k in
                      ("버거", "닭튀김", "감자튀김", "빙수", "아이스크림", "라떼", "에이드", "돈가스")
                      if k in shares), file=sys.stderr)


if __name__ == "__main__":
    main()
