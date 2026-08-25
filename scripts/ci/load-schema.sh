#!/usr/bin/env bash
# doc/schema.sql 의 DDL만 뽑아 주입한다. CI 통합 레인 전용.
#
# 🔴 schema.sql 을 통째로 파이프하면 안 된다. 파일 중간에 "파티션 운영 쿼리" 절이 있고
#    그 안의 ALTER TABLE tokens DROP PARTITION 이 주석이 아니라 실행 가능한 문장이다.
#    운영 절은 refresh_tokens CREATE TABLE 앞에서 끝나므로, 배너부터 다음 CREATE TABLE
#    직전까지를 걷어낸다(줄 번호로 자르지 않는다 — 파일이 바뀌면 조용히 엉뚱한 데를 자른다).
set -euo pipefail

SQL_FILE=${1:-doc/schema.sql}
: "${MYSQL_HOST:=127.0.0.1}" "${MYSQL_PORT:=3306}"
: "${MYSQL_USER:=root}" "${MYSQL_PASSWORD:=root}" "${MYSQL_DB:=queue_platform}"

DDL=$(awk '
  /파티션 운영 쿼리/ { skip = 1 }
  skip && /^CREATE TABLE/ { skip = 0 }
  !skip
' "$SQL_FILE")

if grep -qE '^\s*(ALTER TABLE .* DROP PARTITION|DROP TABLE|DROP DATABASE)' <<<"$DDL"; then
  echo "DDL 추출에 파괴적 문장이 남았다 — schema.sql 구조가 바뀌었는지 확인하라" >&2
  exit 1
fi

mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" <<<"$DDL"

# 주입 결과를 센다. mysql 은 일부 문장이 죽어도 0으로 끝나는 경우가 있어 개수로 못박는다.
COUNT=$(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -B \
  -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$MYSQL_DB'")
EXPECTED=$(grep -c '^CREATE TABLE' "$SQL_FILE")
echo "테이블 $COUNT/$EXPECTED"
[ "$COUNT" -eq "$EXPECTED" ] || { echo "스키마 주입 실패" >&2; exit 1; }
