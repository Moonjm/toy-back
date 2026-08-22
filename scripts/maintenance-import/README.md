# 관리비 과거 이관

`2025-08_2025-11.sql` — 관리비 앱 화면 캡처로 남은 넉 달을 넣는다.

## 전제

`ddl-auto: update`로 테이블이 만들어진 뒤에 돌린다(앱을 한 번 띄운 뒤).

## 실행

호스트에 `psql`이 없는 환경에서는 도커 컨테이너(`pg-postgis`)를 거쳐 돌린다.

```bash
docker exec -i pg-postgis psql -v ON_ERROR_STOP=1 -U toy -d daily-record < scripts/maintenance-import/2025-08_2025-11.sql
```

(호스트에 `psql`이 로컬에 설치돼 있으면 `psql -U toy -d daily-record -f scripts/maintenance-import/2025-08_2025-11.sql`도 된다.)

마지막 요약 `SELECT`가 네 줄 모두 `ok`를 찍어야 한다. 그 뒤에 같은 조건을 다시 검사하는
`DO` 블록이 있어, 하나라도 `MISMATCH`면 예외를 던져 트랜잭션 전체가 롤백된다.
`-f`(또는 `-i` 리다이렉트)로 파일을 통째로 돌리면 사람이 중간에 멈출 수 없어
`SELECT` 결과만으로는 커밋을 막을 수 없기 때문이다 — `DO` 블록이 실제 가드다.

## 두 번 돌리면

`year_month_value` unique 제약에 걸려 트랜잭션이 통째로 실패한다. 의도한 동작이다.
다시 넣어야 하면 해당 연월을 지우고 돌린다.

```bash
docker exec pg-postgis psql -U toy -d daily-record -c "DELETE FROM maintenance_bills WHERE year_month_value BETWEEN '2025-08' AND '2025-11'"
```

항목은 `cascade`가 아니라 FK로 묶여 있으므로, 위 `DELETE`가 FK 위반으로 막히면
`maintenance_bill_items`를 먼저 지운다.
