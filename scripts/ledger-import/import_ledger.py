#!/usr/bin/env python3
"""기존 가계부 엑셀 내보내기 2종을 ledger_entries로 일회성 이관한다.

- 구형 .xls: 실제로는 HTML 테이블 (날짜, 계좌, 대분류, 내용, 금액, 수입/지출, 상세내역)
- 신형 .xlsx: openpyxl 파싱 (기간, 자산, 분류, 소분류, 내용, KRW, 수입/지출, 추가입력, 금액, 화폐, 자산)

두 파일에서 겹치는 건은 (일자, 금액, 내용) 기준으로 중복 제거하며, 시간 정보가 있는
신형 파일 쪽을 우선한다. 지출(EXPENSE)만 이관하고 수입(INCOME)은 제외한다.
분류는 사용하지 않으므로 엑셀의 분류/계좌/자산 값은 모두 버린다.

사용법:
  python import_ledger.py --xls 2026-07-19.xls --xlsx 2026-07-19_new.xlsx \
      --username moon --dsn "host=localhost port=5432 dbname=daily-record user=toy password=toy00" \
      [--dry-run]
"""
import argparse
import html
import re
import sys
from collections import Counter
from datetime import datetime
from decimal import Decimal

import psycopg2

import openpyxl


def parse_old_xls(path):
    """HTML 테이블 형식의 구형 파일을 파싱한다. 반환: dict 리스트."""
    text = open(path, encoding="utf-8", errors="replace").read()
    rows = re.findall(r"<tr[^>]*>(.*?)</tr>", text, re.S)
    entries = []
    for row in rows:
        cells = re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)
        cells = [html.unescape(re.sub(r"<[^>]+>", "", c)).strip() for c in cells]
        if len(cells) < 6 or cells[0] == "날짜":
            continue
        # 날짜: "2026. 06. 25"
        date_match = re.match(r"(\d{4})\.\s*(\d{2})\.\s*(\d{2})", cells[0])
        if not date_match:
            continue
        entry_at = datetime(*(int(g) for g in date_match.groups()))
        # 상세내역은 카드 승인 문자 원문이다. 있으면 문자(SMS)에서 온 내역으로 본다.
        detail = (cells[6][:500] or None) if len(cells) > 6 else None
        entries.append(
            {
                "entry_at": entry_at,
                "amount": Decimal(cells[4].replace(",", "")),
                "currency": "KRW",
                "type": "INCOME" if cells[5] == "수입" else "EXPENSE",
                "merchant": cells[3][:100] or None,
                "description": detail,
                "source": "SMS" if detail else "MANUAL",
            }
        )
    return entries


def parse_new_xlsx(path):
    """신형 xlsx 파일을 파싱한다. 헤더: 기간, 자산, 분류, 소분류, 내용, KRW, 수입/지출, 추가입력, 금액, 화폐, 자산"""
    workbook = openpyxl.load_workbook(path, read_only=True)
    sheet = workbook.worksheets[0]
    entries = []
    for row in sheet.iter_rows(min_row=2, values_only=True):
        if not row or row[0] is None:
            continue
        entry_at, _asset, _category, _subcat, content, _krw, io_type, extra, amount, currency = row[:10]
        if not isinstance(entry_at, datetime):
            continue
        # 추가입력에는 (enrich_new_xlsx.py로 채운) 카드 승인 문자 원문이 들어 있다.
        # 있으면 문자(SMS)에서 온 내역으로 본다.
        detail = (str(extra).strip()[:500] or None) if extra else None
        entries.append(
            {
                "entry_at": entry_at,
                "amount": Decimal(str(amount)),
                "currency": (currency or "KRW").strip(),
                "type": "INCOME" if io_type == "수입" else "EXPENSE",
                "merchant": (str(content).strip()[:100] or None) if content else None,
                "description": detail,
                "source": "SMS" if detail else "MANUAL",
            }
        )
    return entries


def dedupe_key(entry):
    """구형 파일에는 시간이 없으므로 일자 단위로 비교한다."""
    return (entry["entry_at"].date(), entry["amount"], entry["merchant"])


def merge(old_entries, new_entries):
    """신형(시간 있음) 우선으로 병합한다.

    같은 키(일자, 금액, 내용)의 구형 행은 신형에 존재하는 '개수만큼만' 중복으로 간주해 버린다 —
    같은 날 같은 금액의 정당한 별개 거래가 통째로 유실되는 것을 막는다.
    """
    remaining = Counter(dedupe_key(e) for e in new_entries)
    unique_old = []
    for entry in old_entries:
        key = dedupe_key(entry)
        if remaining.get(key, 0) > 0:
            remaining[key] -= 1
        else:
            unique_old.append(entry)
    return unique_old + new_entries


def main():
    arg_parser = argparse.ArgumentParser()
    arg_parser.add_argument("--xls", required=True, help="구형 .xls (HTML) 파일 경로")
    arg_parser.add_argument("--xlsx", required=True, help="신형 .xlsx 파일 경로")
    arg_parser.add_argument("--username", required=True, help="대상 사용자 username")
    arg_parser.add_argument("--dsn", required=True, help="PostgreSQL DSN")
    arg_parser.add_argument("--dry-run", action="store_true", help="INSERT 없이 건수만 출력")
    args = arg_parser.parse_args()

    old_entries = parse_old_xls(args.xls)
    new_entries = parse_new_xlsx(args.xlsx)
    all_merged = merge(old_entries, new_entries)
    # 지출만 이관한다 — 수입(INCOME) 항목은 제외.
    merged = [e for e in all_merged if e["type"] == "EXPENSE"]
    income_dropped = len(all_merged) - len(merged)
    dropped = len(old_entries) + len(new_entries) - len(all_merged)
    sms_count = sum(1 for e in merged if e["source"] == "SMS")
    print(f"구형 {len(old_entries)}건 + 신형 {len(new_entries)}건 → 병합 {len(all_merged)}건 (중복 제거 {dropped}건)")
    print(f"지출만 이관: {len(merged)}건 (수입 {income_dropped}건 제외)")
    print(f"출처: 문자(SMS) {sms_count}건 / 수동(MANUAL) {len(merged) - sms_count}건")

    if args.dry_run:
        for entry in merged[:5]:
            print("  샘플:", entry)
        return

    conn = psycopg2.connect(args.dsn)
    try:
        with conn, conn.cursor() as cur:
            cur.execute("select id from users where username = %s", (args.username,))
            row = cur.fetchone()
            if not row:
                sys.exit(f"사용자를 찾을 수 없습니다: {args.username}")
            user_id = row[0]

            for entry in merged:
                cur.execute(
                    """
                    insert into ledger_entries
                        (user_id, entry_at, amount, currency, type, merchant, description,
                         source, created_at, updated_at)
                    values (%s, %s, %s, %s, %s, %s, %s, %s, now(), now())
                    """,
                    (
                        user_id,
                        entry["entry_at"],
                        entry["amount"],
                        entry["currency"],
                        entry["type"],
                        entry["merchant"],
                        entry["description"],
                        entry["source"],
                    ),
                )
        print(f"이관 완료: {len(merged)}건 (username={args.username})")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
