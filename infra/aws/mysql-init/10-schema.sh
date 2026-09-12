#!/bin/bash
# doc/schema.sql 을 그대로 쓰되 "파티션 운영 쿼리" 절만 건너뛴다.
# 그 절은 예시이고, 특히 REORGANIZE 가 CREATE TABLE 에 이미 있는 p2027_01 을
# 다시 만들려 해서 초기화가 통째로 실패한다.
set -e
awk '/^-- 파티션 운영 쿼리/{skip=1} /^-- refresh_tokens \(Sprint 5/{skip=0} !skip' /schema/schema.sql \
  | mysql --protocol=socket -uroot -p"$MYSQL_ROOT_PASSWORD" queue_platform
