# 관리비에서 안 쓰는 금액 필드를 걷어낸다

작성일: 2026-08-23

## 무엇을 뺐나

`maintenance_bills`에서 네 컬럼을 지웠다.

| 컬럼 | 뺀 이유 |
|---|---|
| `due_amount` | 납기내 금액이 늘 당월부과액과 같다. 미납을 안 다루기로 하면서 구조적으로 같아진다 |
| `unpaid_amount` | 미납할 일이 없다 |
| `unpaid_late_fee` | 위와 같다 |
| `due_date` | 쓰는 화면이 없다 |

`charged_amount`는 기본값(`ZERO`)을 갖게 되어 생성자 인자에서 빠졌다. 값은 `fill`이 채운다.

적재된 넉 달을 확인한 뒤 지웠다 — 네 달 모두 `due_amount = charged_amount`였고 미납 두
컬럼은 전부 0이라 잃은 값이 없다.

## ⚠️ 배포할 때 컬럼을 손으로 지워야 한다

**`ddl-auto: update`는 컬럼을 지우지 않는다.** 엔티티에서만 빼면 지워진 세 컬럼이
`NOT NULL`인 채 DB에 남고, Hibernate가 INSERT에서 그것들을 빼므로 **관리비 저장이 전부
실패한다.** 배포 후 아래를 한 번 돌린다.

```bash
docker exec -i <postgres 컨테이너> psql -v ON_ERROR_STOP=1 -U toy -d daily-record <<'SQL'
ALTER TABLE maintenance_bills
  DROP COLUMN due_amount,
  DROP COLUMN unpaid_amount,
  DROP COLUMN unpaid_late_fee,
  DROP COLUMN due_date;
SQL
```

`scripts/maintenance-import/2025-08_2025-11.sql`도 새 컬럼 구성에 맞춰 고쳤다. 아직 운영에
돌리지 않았다면 그대로 쓰면 되고, 이미 돌렸다면 위 `ALTER`만 하면 된다.

## 인식은 그대로 읽는다

프롬프트와 `RecognizedBill`은 건드리지 않았다. 문구가 실측(합계 검증 16/16)의 대상이라
바꾸면 그 측정이 무효가 되기 때문이다.

그래서 모델은 여전히 미납액·연체료를 읽지만 저장할 곳이 없다. **조용히 버리지 않는다** —
값이 0이 아니면 검수 화면에 경고로 올린다. 미납이 없다는 전제가 깨진 달을 아무도 모르고
넘기는 것이 이 변경의 유일한 위험이라, 그 자리만 막아 두었다.

`discount_total`은 남겼다. 할인총계는 영수증에 있는 항목이고 안 쓴다고 정한 적이 없다.
