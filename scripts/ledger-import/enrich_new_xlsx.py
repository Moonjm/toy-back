#!/usr/bin/env python3
"""신형 .xlsx의 '추가입력' 칸을, 아이폰 백업 sms.db의 카드 승인 문자 원문으로 채운다.

신형 파일은 카드 문자에서 온 내역인데도 원문을 저장하지 않았다. 백업 sms.db에는 원문이
남아 있으므로 (금액 + 시각 ±N분)으로 매칭해 '추가입력'에 원문을 채워 넣는다. 그러면
import_ledger.py가 추가입력이 있는 행을 SMS(문자)로 이관한다.

- 매칭은 원화 금액 문자열("6,160원")이 문자에 있고 승인 시각이 엑셀 일시와 가까운 것.
- 같은 문자가 여러 행에 중복 배정되지 않도록 한 번 쓴 문자는 소진 처리한다.
- 원본은 건드리지 않고 --out 경로에 새로 저장한다.

사용법:
  python enrich_new_xlsx.py \
      --xlsx ~/Downloads/2026-07-19_new.xlsx \
      --db   ~/Downloads/back/3d/3d0d7e5fb2ce288813306e4d4636395e047a3d28 \
      --out  ~/Downloads/2026-07-19_new.enriched.xlsx \
      [--window-min 3]
"""
import argparse
import os
import sqlite3
from datetime import datetime, timedelta

import openpyxl

# 신형 헤더: 기간(1) 자산(2) 분류(3) 소분류(4) 내용(5) KRW(6) 수입/지출(7) 추가입력(8) 금액(9) 화폐(10) 자산(11)
COL_DATE = 1
COL_CONTENT = 5
COL_EXTRA = 8
COL_AMOUNT = 9
COL_CURRENCY = 10

# Mac absolute time(2001-01-01) → Unix epoch 보정값
MAC_EPOCH_OFFSET = 978307200


def load_approvals(db_path):
    """sms.db에서 카드 결제 문자를 (시각, 원문)으로 읽는다.

    카드사마다 형식이 달라 승인/사용/일시불이 섞여 있다(현대·대한항공=승인,
    KB체크=사용, 롯데=일시불). 카드주 마스킹명 '문*민'을 함께 요구해 입금요청·
    ISP등록·자동납부 같은 비(非)카드 문자를 배제한다.
    """
    con = sqlite3.connect(db_path)
    try:
        rows = con.execute(
            """
            SELECT date/1000000000 + ?, text
            FROM message
            WHERE text LIKE '%문*민%'
              AND (text LIKE '%승인%' OR text LIKE '%사용%' OR text LIKE '%일시불%')
            """,
            (MAC_EPOCH_OFFSET,),
        ).fetchall()
    finally:
        con.close()
    approvals = []
    for ts, text in rows:
        if not text:
            continue
        approvals.append({"dt": datetime.fromtimestamp(ts), "text": text, "used": False})
    approvals.sort(key=lambda a: a["dt"])
    return approvals


def build_index(approvals):
    """분(minute) 버킷 인덱스로 시간 근처 후보를 빠르게 찾는다."""
    index = {}
    for approval in approvals:
        key = approval["dt"].strftime("%Y%m%d%H%M")
        index.setdefault(key, []).append(approval)
    return index


def find_match(index, entry_dt, amount_krw, merchant, window_min):
    """금액 문자열 + 시각 근접으로 아직 안 쓴 승인 문자를 찾는다.

    상호명이 문자 안에 있으면 우선 매칭해 오탐(동일 금액·시각 우연)을 줄인다.
    """
    amount_token = f"{amount_krw:,}원"
    candidates = []
    for offset in range(-window_min, window_min + 1):
        key = (entry_dt + timedelta(minutes=offset)).strftime("%Y%m%d%H%M")
        for approval in index.get(key, []):
            if approval["used"]:
                continue
            if amount_token not in approval["text"]:
                continue
            candidates.append(approval)
    if not candidates:
        return None
    # 상호명이 원문에 포함된 후보를 우선, 그다음 시각이 가까운 순.
    merchant_key = (merchant or "").strip()

    def score(approval):
        has_merchant = bool(merchant_key) and merchant_key[:6] in approval["text"]
        gap = abs((approval["dt"] - entry_dt).total_seconds())
        return (0 if has_merchant else 1, gap)

    candidates.sort(key=score)
    return candidates[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--xlsx", required=True, help="신형 .xlsx 경로")
    parser.add_argument("--db", required=True, help="아이폰 백업 sms.db 경로")
    parser.add_argument("--out", required=True, help="보강본 저장 경로 (원본 미변경)")
    parser.add_argument("--window-min", type=int, default=3, help="시각 매칭 허용 오차(분)")
    args = parser.parse_args()

    approvals = load_approvals(os.path.expanduser(args.db))
    index = build_index(approvals)
    print(f"승인 문자 로드: {len(approvals)}건")

    workbook = openpyxl.load_workbook(os.path.expanduser(args.xlsx))
    sheet = workbook.worksheets[0]

    filled = 0
    already = 0
    scanned = 0
    for row in sheet.iter_rows(min_row=2):
        date_cell = row[COL_DATE - 1].value
        if not isinstance(date_cell, datetime):
            continue
        scanned += 1
        extra_cell = row[COL_EXTRA - 1]
        if extra_cell.value and str(extra_cell.value).strip():
            already += 1
            continue  # 이미 채워진 건 건드리지 않는다

        amount_val = row[COL_AMOUNT - 1].value
        currency = str(row[COL_CURRENCY - 1].value or "KRW").strip().upper()
        if currency != "KRW" or not amount_val:
            continue  # 외화·금액없음은 원화 문자열 매칭 대상 아님
        amount_krw = int(round(float(amount_val)))
        merchant = str(row[COL_CONTENT - 1].value or "")

        match = find_match(index, date_cell, amount_krw, merchant, args.window_min)
        if match:
            match["used"] = True
            extra_cell.value = match["text"]
            filled += 1

    workbook.save(os.path.expanduser(args.out))
    print(f"스캔 {scanned}행 · 기존 추가입력 {already}행 · 문자 채움 {filled}행")
    print(f"저장: {args.out}")


if __name__ == "__main__":
    main()
