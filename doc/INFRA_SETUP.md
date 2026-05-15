# Queue Platform — Infrastructure Setup

> 작성일: 2026-05 (Sprint 5 진행 중)  
> 목적: WSL2 + Windows 환경의 인프라 세팅 가이드  
> 대상: 새 환경 구축 또는 재구축 시 참고

---

## 1. 전체 인프라 구성 개요

```
[Windows 10/11]
├── IntelliJ IDEA (코드 작성)
├── Claude Code (IntelliJ Plugin)
└── (선택) Docker Desktop

[WSL2 Ubuntu]
├── JDK 21
├── Git
├── MySQL 8.0 (Master 3306 + Replica 3307)
├── Redis Sentinel
│   ├── Master (6379)
│   ├── Slave 1 (6380)
│   ├── Slave 2 (6381)
│   ├── Sentinel 1 (26379)
│   ├── Sentinel 2 (26380)
│   └── Sentinel 3 (26381)
└── (Sprint 8+) Kafka KRaft
```

---

## 2. 시스템 환경

### WSL2 정보

```bash
# 확인 명령
lsb_release -a
# Distributor ID: Ubuntu
# Release:        22.04 또는 24.04

uname -r
# Linux kernel 5.x.x WSL2
```

### 패키지 업데이트 (재구축 시 첫 단계)

```bash
sudo apt update
sudo apt upgrade -y
```

### 필수 도구

```bash
sudo apt install -y \
    curl \
    wget \
    git \
    vim \
    tree \
    htop \
    net-tools \
    build-essential
```

---

## 3. JDK 21 설치

### 설치

```bash
# Eclipse Temurin (Adoptium) — 권장
wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-21-jdk

# 확인
java -version
# openjdk version "21.x.x"

javac -version
```

### JAVA_HOME 설정

```bash
# ~/.bashrc 추가
echo 'export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64' >> ~/.bashrc
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.bashrc
source ~/.bashrc

# 확인
echo $JAVA_HOME
```

---

## 4. Gradle 설치 (선택, Wrapper 사용 권장)

프로젝트가 Gradle Wrapper(`./gradlew`)를 포함하므로 시스템 Gradle은 필수 아님.

만약 시스템 Gradle 필요:

```bash
# SDKMAN으로 설치 (버전 관리 편리)
curl -s "https://get.sdkman.io" | bash
source ~/.sdkman/bin/sdkman-init.sh
sdk install gradle 8.5

gradle -v
```

> ⚠️ **Gradle 8.5 사용** (이전 Sprint 1에서 호환성 이슈로 결정)

---

## 5. MySQL 8.0 설치 + Master/Replica 구성

### 5-1. MySQL 설치

```bash
sudo apt install -y mysql-server-8.0
sudo systemctl stop mysql       # 우리는 직접 관리할 거라 중단
sudo systemctl disable mysql    # 자동 시작 비활성화
```

### 5-2. 디렉토리 구조

```bash
mkdir -p ~/queue-platform-infra/mysql/{master,replica}/{data,logs}
```

### 5-3. Master (port 3306) 설정

`~/queue-platform-infra/mysql/master/my.cnf`:

```ini
[mysqld]
server-id=1
port=3306
datadir=/home/sonix/queue-platform-infra/mysql/master/data
socket=/home/sonix/queue-platform-infra/mysql/master/master.sock
pid-file=/home/sonix/queue-platform-infra/mysql/master/master.pid
log-error=/home/sonix/queue-platform-infra/mysql/master/logs/error.log

# GTID 복제 설정
gtid-mode=ON
enforce-gtid-consistency=ON
log-bin=master-bin
binlog-format=ROW

# 시간대
default-time-zone='+09:00'

# 문자셋
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci

# 성능
max_connections=200
innodb_buffer_pool_size=512M
```

### 5-4. Replica (port 3307) 설정

`~/queue-platform-infra/mysql/replica/my.cnf`:

```ini
[mysqld]
server-id=2
port=3307
datadir=/home/sonix/queue-platform-infra/mysql/replica/data
socket=/home/sonix/queue-platform-infra/mysql/replica/replica.sock
pid-file=/home/sonix/queue-platform-infra/mysql/replica/replica.pid
log-error=/home/sonix/queue-platform-infra/mysql/replica/logs/error.log

# GTID 복제 설정 (Replica 측)
gtid-mode=ON
enforce-gtid-consistency=ON
relay-log=replica-relay-bin
read-only=ON
super-read-only=ON

default-time-zone='+09:00'
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci

max_connections=200
innodb_buffer_pool_size=512M
```

### 5-5. 초기화

```bash
# Master 초기화
sudo mysqld --defaults-file=~/queue-platform-infra/mysql/master/my.cnf --initialize --user=sonix
sudo chown -R sonix:sonix ~/queue-platform-infra/mysql/

# Replica 초기화
sudo mysqld --defaults-file=~/queue-platform-infra/mysql/replica/my.cnf --initialize --user=sonix
```

### 5-6. 기동

```bash
mysqld --defaults-file=~/queue-platform-infra/mysql/master/my.cnf &
mysqld --defaults-file=~/queue-platform-infra/mysql/replica/my.cnf &

# 확인
mysql -u root -p -P 3306 -e "SELECT @@server_id;"  # Master: 1
mysql -u root -p -P 3307 -e "SELECT @@server_id;"  # Replica: 2
```

### 5-7. GTID 복제 설정

Master에서:

```sql
-- 복제 사용자 생성
CREATE USER 'replicator'@'%' IDENTIFIED BY 'StrongPassword';
GRANT REPLICATION SLAVE ON *.* TO 'replicator'@'%';
FLUSH PRIVILEGES;

-- GTID 정보 확인
SHOW MASTER STATUS;
```

Replica에서:

```sql
-- Master 연결
CHANGE MASTER TO
  MASTER_HOST='127.0.0.1',
  MASTER_PORT=3306,
  MASTER_USER='replicator',
  MASTER_PASSWORD='StrongPassword',
  MASTER_AUTO_POSITION=1;

START SLAVE;

-- 복제 상태 확인
SHOW SLAVE STATUS\G
-- 다음 두 항목이 'Yes'여야 함:
-- Slave_IO_Running: Yes
-- Slave_SQL_Running: Yes
```

### 5-8. 데이터베이스 생성

```sql
-- Master에서 (Replica로 자동 복제됨)
CREATE DATABASE queue_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE queue_platform;

-- 스키마는 docs/schema.sql 참조
SOURCE /home/sonix/queue-platform/docs/schema.sql;
```

---

## 6. Redis Sentinel 클러스터 ⭐ (Sprint 5 핵심)

### 6-1. Redis 설치

```bash
sudo apt install -y redis-server redis-sentinel

# 자동 시작 비활성화 (직접 관리)
sudo systemctl stop redis-server
sudo systemctl disable redis-server

# 버전 확인
redis-server --version    # Redis 7.0.x
redis-sentinel --version
```

### 6-2. 디렉토리 구조

```bash
mkdir -p ~/queue-platform-infra/redis/{master,slave-1,slave-2}/{data,logs}
mkdir -p ~/queue-platform-infra/redis/{sentinel-1,sentinel-2,sentinel-3}/logs
```

### 6-3. Master 설정

`~/queue-platform-infra/redis/master/redis.conf` 작성 (기본 템플릿 복사 후 수정):

```bash
# 시스템 템플릿 복사
sudo cp /etc/redis/redis.conf ~/queue-platform-infra/redis/master/redis.conf
sudo chown -R sonix:sonix ~/queue-platform-infra/

# 핵심 설정 수정
CONF=~/queue-platform-infra/redis/master/redis.conf

sed -i 's|^bind 127.0.0.1 -::1|bind 127.0.0.1|' $CONF
sed -i 's|^dir /var/lib/redis|dir /home/sonix/queue-platform-infra/redis/master/data|' $CONF
sed -i 's|^logfile /var/log/redis/redis-server.log|logfile /home/sonix/queue-platform-infra/redis/master/logs/redis.log|' $CONF
sed -i 's|^pidfile /run/redis/redis-server.pid|pidfile /home/sonix/queue-platform-infra/redis/master/redis.pid|' $CONF
```

핵심 설정값:
- `bind 127.0.0.1`
- `port 6379`
- `daemonize yes`
- `dir /home/sonix/queue-platform-infra/redis/master/data`
- `logfile /home/sonix/queue-platform-infra/redis/master/logs/redis.log`
- `min-replicas-to-write 1` (Split Brain 방어, 추가 권장)

### 6-4. Slave 설정 (2대)

Master 설정을 복사 후 포트/경로/replicaof만 변경:

```bash
# slave-1
cp ~/queue-platform-infra/redis/master/redis.conf ~/queue-platform-infra/redis/slave-1/redis.conf
CONF=~/queue-platform-infra/redis/slave-1/redis.conf
sed -i 's|^port 6379|port 6380|' $CONF
sed -i 's|/redis/master/|/redis/slave-1/|g' $CONF
echo "" >> $CONF
echo "# === Slave 설정 ===" >> $CONF
echo "replicaof 127.0.0.1 6379" >> $CONF

# slave-2 (위와 동일, slave-2 / 6381)
cp ~/queue-platform-infra/redis/master/redis.conf ~/queue-platform-infra/redis/slave-2/redis.conf
CONF=~/queue-platform-infra/redis/slave-2/redis.conf
sed -i 's|^port 6379|port 6381|' $CONF
sed -i 's|/redis/master/|/redis/slave-2/|g' $CONF
echo "" >> $CONF
echo "# === Slave 설정 ===" >> $CONF
echo "replicaof 127.0.0.1 6379" >> $CONF
```

### 6-5. Sentinel 설정 (3대)

직접 작성 (apt 템플릿이 없을 수 있음):

```bash
# sentinel-1
cat > ~/queue-platform-infra/redis/sentinel-1/sentinel.conf << 'EOF'
# === Queue Platform Sentinel-1 ===
port 26379
daemonize yes
pidfile /home/sonix/queue-platform-infra/redis/sentinel-1/sentinel.pid
logfile /home/sonix/queue-platform-infra/redis/sentinel-1/logs/sentinel.log
dir /tmp
protected-mode no

# === 감시 대상 정의 ===
sentinel monitor mymaster 127.0.0.1 6379 2

# === 감지 정책 ===
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 10000
sentinel parallel-syncs mymaster 1
EOF

# sentinel-2, 3는 sentinel-1 복사 후 포트/경로 변경
cp ~/queue-platform-infra/redis/sentinel-1/sentinel.conf ~/queue-platform-infra/redis/sentinel-2/sentinel.conf
cp ~/queue-platform-infra/redis/sentinel-1/sentinel.conf ~/queue-platform-infra/redis/sentinel-3/sentinel.conf

sed -i 's|port 26379|port 26380|; s|/sentinel-1/|/sentinel-2/|g' ~/queue-platform-infra/redis/sentinel-2/sentinel.conf
sed -i 's|port 26379|port 26381|; s|/sentinel-1/|/sentinel-3/|g' ~/queue-platform-infra/redis/sentinel-3/sentinel.conf
```

### 6-6. 자동 시작/관리 스크립트 (~/.bashrc)

```bash
cat >> ~/.bashrc << 'EOF'

# ============================================================
# Queue Platform — Redis Sentinel Cluster Management
# ============================================================

QP_REDIS_DIR=~/queue-platform-infra/redis

redis_start() {
    echo "Starting Redis Master + Slaves + Sentinels..."
    
    # Master 먼저
    redis-server $QP_REDIS_DIR/master/redis.conf
    sleep 1
    
    # Slave 2대
    redis-server $QP_REDIS_DIR/slave-1/redis.conf
    redis-server $QP_REDIS_DIR/slave-2/redis.conf
    sleep 2
    
    # Sentinel 3대
    redis-sentinel $QP_REDIS_DIR/sentinel-1/sentinel.conf
    redis-sentinel $QP_REDIS_DIR/sentinel-2/sentinel.conf
    redis-sentinel $QP_REDIS_DIR/sentinel-3/sentinel.conf
    sleep 3
    
    echo ""
    echo "Done. Status:"
    redis_status
}

redis_stop() {
    echo "Stopping all Redis processes..."
    pkill -f "redis-sentinel"
    pkill -f "redis-server"
    sleep 2
    echo "Done."
}

redis_status() {
    echo "=== Processes ==="
    local count=$(ps aux | grep -E "redis-(server|sentinel)" | grep -v grep | wc -l)
    ps aux | grep -E "redis-(server|sentinel)" | grep -v grep | awk '{print "  PID " $2 ": " $11, $12, $13}'
    echo "  Total: $count / 6"
    echo ""
    
    echo "=== Sentinel View ==="
    redis-cli -p 26379 sentinel master mymaster 2>/dev/null \
        | grep -A1 -E "^(port|flags|num-slaves|num-other-sentinels)$" \
        | paste - - | sed 's/^/  /'
}

redis_logs() {
    local target=${1:-master}
    case $target in
        master)     tail -f $QP_REDIS_DIR/master/logs/redis.log ;;
        slave-1)    tail -f $QP_REDIS_DIR/slave-1/logs/redis.log ;;
        slave-2)    tail -f $QP_REDIS_DIR/slave-2/logs/redis.log ;;
        sentinel-1) tail -f $QP_REDIS_DIR/sentinel-1/logs/sentinel.log ;;
        sentinel-2) tail -f $QP_REDIS_DIR/sentinel-2/logs/sentinel.log ;;
        sentinel-3) tail -f $QP_REDIS_DIR/sentinel-3/logs/sentinel.log ;;
        *) echo "Usage: redis_logs [master|slave-1|slave-2|sentinel-1|sentinel-2|sentinel-3]" ;;
    esac
}
EOF

source ~/.bashrc
```

### 6-7. 정상 동작 검증

```bash
# 1. 기동
redis_start

# 2. 상태 확인
redis_status

# 기대 결과:
#   Total: 6 / 6
#   port: 6379
#   flags: master
#   num-slaves: 2
#   num-other-sentinels: 2

# 3. 복제 동작 테스트
redis-cli -p 6379 set test "hello"
redis-cli -p 6380 get test    # → "hello" (Slave에 복제됨)
redis-cli -p 6381 get test    # → "hello"

# 4. Slave 쓰기 차단 확인
redis-cli -p 6380 set fail "x"
# 결과: (error) READONLY You can't write against a read only replica.

# 5. 정리
redis-cli -p 6379 del test
```

### 6-8. Failover 실증

```bash
# Master 강제 종료
kill -9 $(cat ~/queue-platform-infra/redis/master/redis.pid)

# 10초 대기 (Sentinel 자동 Failover)
sleep 10

# 새 Master 확인
redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
# 출력: 6380 또는 6381 (승격된 Slave)

# Sentinel 로그
redis_logs sentinel-1
# +sdown → +odown → +failover → +switch-master 메시지 확인
```

### 6-9. 초기 상태 복구 (Failover 후)

CONFIG REWRITE로 conf 파일이 자동 변경되었기 때문에 정리 필요:

```bash
# 모든 Redis 프로세스 종료
redis_stop

# Master conf 정리
CONF=~/queue-platform-infra/redis/master/redis.conf
sed -i '/^# Generated by CONFIG REWRITE/,$d' $CONF
sed -i '/^replicaof/d' $CONF

# Slave conf 정리
for slave in slave-1 slave-2; do
    CONF=~/queue-platform-infra/redis/$slave/redis.conf
    sed -i '/^# Generated by CONFIG REWRITE/,$d' $CONF
    sed -i '/^replicaof/d' $CONF
    sed -i '/^# === Slave 설정/d' $CONF
    echo "" >> $CONF
    echo "# === Slave 설정 ===" >> $CONF
    echo "replicaof 127.0.0.1 6379" >> $CONF
done

# Sentinel conf 정리 (또는 새로 작성)
for s in sentinel-1 sentinel-2 sentinel-3; do
    CONF=~/queue-platform-infra/redis/$s/sentinel.conf
    sed -i '/^# Generated by CONFIG REWRITE/,$d' $CONF
    sed -i '/^known-/d' $CONF
    sed -i '/^current-epoch/d' $CONF
    sed -i '/^sentinel myid/d' $CONF
done

# 재기동
redis_start
```

---

## 7. Claude Code 통합

### 7-1. Windows에 Claude Code 설치

```powershell
# PowerShell (관리자 권한 X)
irm https://claude.ai/install.ps1 | iex

# 재시작 후 확인
claude --version
```

### 7-2. IntelliJ Plugin 설치

```
IntelliJ Settings (Ctrl+Alt+S)
  → Plugins → Marketplace
  → "Claude Code [Beta]" 검색 (제작자: Anthropic)
  → Install → IntelliJ 재시작
```

### 7-3. 첫 인증

```
1. IntelliJ에서 프로젝트 열기
2. Ctrl+Esc → Claude 패널 열림
3. 브라우저 자동 열림 → Anthropic 계정 로그인
4. 인증 완료 → IntelliJ로 토큰 자동 전달
```

### 7-4. CLAUDE.md 파일

프로젝트 루트의 `CLAUDE.md`가 Claude Code의 자동 컨텍스트로 사용됨.
상세 내용은 별도 문서 참조.

---

## 8. 포트 사용 요약

| 포트 | 용도 |
|------|------|
| 3306 | MySQL Master |
| 3307 | MySQL Replica |
| 6379 | Redis Master |
| 6380 | Redis Slave 1 |
| 6381 | Redis Slave 2 |
| 26379 | Redis Sentinel 1 |
| 26380 | Redis Sentinel 2 |
| 26381 | Redis Sentinel 3 |
| 9092 | (Sprint 8+) Kafka Broker |
| 8080 | queue-api Spring Boot |

---

## 9. 자주 쓰는 명령어 모음

### 인프라 관리

```bash
# Redis (직접 만든 함수)
redis_start
redis_stop
redis_status
redis_logs master

# MySQL (수동)
mysql -u root -p -P 3306    # Master
mysql -u root -p -P 3307    # Replica

# 포트 사용 확인
sudo ss -tlnp | grep -E '3306|3307|6379|6380|6381|26379|26380|26381'

# 프로세스 확인
ps aux | grep -E "redis|mysql"
```

### 프로젝트 빌드/실행

```bash
cd ~/queue-platform   # 또는 본인 프로젝트 위치

# 빌드
./gradlew clean build

# 단위 테스트
./gradlew test

# queue-api 실행
./gradlew :queue-api:bootRun

# 특정 모듈 빌드
./gradlew :queue-domain:build
```

### Git

```bash
# 현재 브랜치
git branch

# 새 feature 브랜치
git checkout -b feat/sprint-5-redis-sentinel dev

# 커밋 (Conventional Commits)
git add .
git commit -m "feat(sprint-5): Redis Sentinel 클러스터 구성"

# Push
git push -u origin feat/sprint-5-redis-sentinel
```

---

## 10. 트러블슈팅

### Redis 관련

#### 문제: Sentinel이 `num-other-sentinels: 0`

**원인**: Sentinel들이 서로를 발견 못함  
**해결**:
```bash
# 1분 대기 후 재확인
sleep 60
redis-cli -p 26379 sentinel master mymaster | grep num-other-sentinels

# 또는 Sentinel 토폴로지 강제 초기화
redis-cli -p 26379 sentinel reset mymaster
redis-cli -p 26380 sentinel reset mymaster
redis-cli -p 26381 sentinel reset mymaster
```

#### 문제: CONFIG REWRITE로 conf 파일이 변경됨

**원인**: Sentinel이 Failover 후 자동으로 conf 파일 수정  
**해결**: 위 "6-9 초기 상태 복구" 참조

#### 문제: Master kill 후 Failover가 발생 안 함

**진단**:
```bash
tail -50 ~/queue-platform-infra/redis/sentinel-1/logs/sentinel.log
```

**흔한 원인**:
- `down-after-milliseconds` 시간 미충족 → 5초 더 기다리기
- `num-other-sentinels: 0` → Sentinel 클러스터 미구성

### MySQL 관련

#### 문제: 복제가 시작 안 됨

**진단**:
```sql
SHOW SLAVE STATUS\G
```

**확인 항목**:
- `Slave_IO_Running: Yes`
- `Slave_SQL_Running: Yes`
- `Last_Error`에 에러 메시지

**일반적 해결**:
```sql
STOP SLAVE;
CHANGE MASTER TO MASTER_AUTO_POSITION=1;  -- GTID 기반 재연결
START SLAVE;
```

### 권한 관련

#### 문제: `Permission denied` 에러

**원인**: 시스템 디렉토리에 sudo로 만든 파일 소유자가 root  
**해결**:
```bash
sudo chown -R sonix:sonix ~/queue-platform-infra/
```

### Claude Code 관련

#### 문제: `command not found: claude` (WSL2)

```bash
# PATH 확인
echo $PATH | tr ':' '\n' | grep -i claude

# 직접 경로
~/.local/bin/claude --version

# PATH 영구 추가
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

---

## 11. 환경 재구축 절차 (Disaster Recovery)

WSL2를 새로 설치하거나 환경 완전 재구축 시:

### 순서

```
1. WSL2 Ubuntu 설치
2. 패키지 업데이트 (apt update + upgrade)
3. JDK 21 설치
4. Git 설치 + 프로젝트 clone
   git clone <repo-url> ~/queue-platform
5. MySQL 설치 + 디렉토리 구성 (위 §5)
6. Redis 설치 + Sentinel 구성 (위 §6)
7. ~/.bashrc 스크립트 설정 (위 §6-6)
8. 기동 + 검증
   redis_start
   redis_status
9. Windows IntelliJ + Claude Code 설치 (위 §7)
10. 프로젝트 빌드 테스트
    cd ~/queue-platform && ./gradlew build
```

---

## 12. 백업 / 데이터 보존

### MySQL 백업

```bash
# 전체 DB 덤프
mysqldump -u root -p -P 3306 queue_platform > backup_$(date +%Y%m%d).sql

# 특정 테이블만
mysqldump -u root -p -P 3306 queue_platform tenants api_keys queues > management_$(date +%Y%m%d).sql

# 복원
mysql -u root -p -P 3306 queue_platform < backup_20260515.sql
```

### Redis 백업 (RDB)

```bash
# 강제 스냅샷
redis-cli -p 6379 BGSAVE

# RDB 파일 위치
ls -lh ~/queue-platform-infra/redis/master/data/dump.rdb

# 다른 곳에 백업
cp ~/queue-platform-infra/redis/master/data/dump.rdb ~/backups/redis_$(date +%Y%m%d).rdb
```

---

## 13. 참조 문서

| 문서 | 내용 |
|------|------|
| `docs/sprint-5/REDIS_SENTINEL.md` | Sprint 5 Phase 1 Sentinel 학습 노트 |
| `docs/sprint-5/LUA_SCRIPTS.md` | Sprint 5 Phase 2 Lua Script 분석 |
| `docs/schema.sql` | MySQL DDL + 파티션 운영 쿼리 |
| `docs/DECISIONS.md` | §30 Redis Master/Replica Sentinel 설계 결정 |
| `CLAUDE.md` | 프로젝트 컨텍스트 (Claude Code 자동 로드) |

---

## 14. 향후 추가 예정 (Sprint 8~11)

### Sprint 8: Kafka KRaft

```bash
# Kafka 3.5+ KRaft 모드 설치 (Zookeeper 없이)
# 디렉토리: ~/queue-platform-infra/kafka/
# 포트: 9092
```

### Sprint 11: AWS 배포 시

```
WSL2 인프라 → AWS Managed Services 매핑:
  MySQL Master/Replica → RDS MySQL with Read Replica
  Redis Sentinel → ElastiCache Redis with Multi-AZ
  Kafka KRaft → MSK (Managed Streaming for Kafka)
```

---

## 15. 보안 주의사항

```
⚠️ 운영 환경에서 절대 하지 말 것:
  - protected-mode no (외부 노출)
  - bind 0.0.0.0 (모든 IP에서 접근)
  - 기본 비밀번호 사용
  - 평문 패스워드 (config 파일에)

✅ 운영 환경 권장:
  - requirepass 설정 (Redis)
  - AUTH 필수 (Redis Sentinel)
  - SSL/TLS 통신
  - 비밀번호는 환경 변수 또는 Vault
  - 방화벽 규칙 (특정 IP만 허용)
```

이 INFRA_SETUP.md는 **로컬 개발 환경** 기준이므로 운영 시 추가 보안 강화 필요.