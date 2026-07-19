# 가계부 엑셀 일회성 이관

기존 가계부 앱에서 내보낸 엑셀 2종을 `ledger_entries`로 이관한다.
설계: `docs/superpowers/specs/2026-07-19-ledger-design.md`

## 실행

```bash
python3 -m venv venv && ./venv/bin/pip install -r requirements.txt
./venv/bin/python import_ledger.py \
  --xls ~/Downloads/2026-07-19.xls \
  --xlsx ~/Downloads/2026-07-19_new.xlsx \
  --username <사용자명> \
  --dsn "host=localhost port=5432 dbname=ledger user=toy password=toy00" \
  --dry-run   # 먼저 건수 확인 후 제거하고 재실행
```
