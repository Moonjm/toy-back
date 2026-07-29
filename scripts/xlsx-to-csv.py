#!/usr/bin/env python3
"""식약처 식품영양성분DB 엑셀을 `build-food-csv.py`가 읽을 CSV로 바꾼다.

공공데이터포털이 주는 원본이 xlsx라서 한 단계가 더 필요하다. 160개가 넘는 컬럼 중
정제에 쓰는 것만 남겨 중간 파일이 커지지 않게 한다(가공식품 원본은 188MB다).

  python3 scripts/xlsx-to-csv.py ~/Downloads/음식DB.xlsx /tmp/food-raw.csv
  python3 scripts/build-food-csv.py /tmp/food-raw.csv \
    apps/daily-record/src/main/resources/food/food-nutrition.csv

`openpyxl`이 필요하다: `pip3 install openpyxl`
"""

import csv
import sys

import openpyxl

# 남길 컬럼. 헤더 표기가 판본마다 조금씩 달라 공백을 지운 뒤 부분 문자열로 찾는다.
# 순서가 곧 출력 순서이며, 앞에 있는 힌트가 먼저 매칭된다.
WANTED = [
    "식품코드",
    "식품명",
    "영양성분함량기준량",
    "에너지(kcal)",
    "탄수화물(g)",
    "단백질(g)",
    "지방(g)",
    "1인(회)분량참고량",
    "식품중량",
]

# 없어도 되는 컬럼 — 데이터셋에 따라 빠져 있다.
OPTIONAL = {"1인(회)분량참고량", "식품중량"}


def find_column(header, hint):
    """`지방(g)`이 `포화지방산(g)`보다 먼저 잡히도록 원본 컬럼 순서대로 훑는다."""
    for index, name in enumerate(header):
        if hint.replace(" ", "") in name.replace(" ", ""):
            return index
    return None


def main(src_path, dst_path):
    workbook = openpyxl.load_workbook(src_path, read_only=True, data_only=True)
    sheet = workbook[workbook.sheetnames[0]]
    rows = sheet.iter_rows(values_only=True)

    header = ["" if v is None else str(v).strip() for v in next(rows)]
    columns = [(hint, find_column(header, hint)) for hint in WANTED]
    missing = [hint for hint, index in columns if index is None and hint not in OPTIONAL]
    if missing:
        sys.exit(f"원본에서 컬럼을 찾지 못했습니다: {missing}\n헤더: {header[:20]} ...")

    written = 0
    with open(dst_path, "w", encoding="utf-8", newline="") as dst:
        writer = csv.writer(dst)
        writer.writerow([header[index] if index is not None else hint for hint, index in columns])
        for row in rows:
            # 식품코드가 없는 줄은 빈 줄이거나 꼬리말이다.
            code_index = columns[0][1]
            if row[code_index] is None or not str(row[code_index]).strip():
                continue
            writer.writerow(
                [
                    "" if index is None or row[index] is None else str(row[index]).strip()
                    for _, index in columns
                ]
            )
            written += 1

    print(f"{written}행을 {dst_path}에 썼습니다 (원본 컬럼 {len(header)}개 중 {len(columns)}개 유지)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: xlsx-to-csv.py <원본.xlsx> <출력.csv>")
    main(sys.argv[1], sys.argv[2])
