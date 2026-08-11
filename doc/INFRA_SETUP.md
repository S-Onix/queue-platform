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
├── Prometheus (9090)
├── Grafana (3000)
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

## 6.5. Redis Cluster (Sprint 8+ 학습 환경) ⭐

> **작성일**: 2026-07-08 (Sprint 5-D 완료 후)
> **목적**: Sentinel과 병행 실습 가능한 Redis Cluster 구축
> **최종 목표**: Sprint 10 프로덕션 Cluster 도입 사전 학습

### 6.5-1. Cluster 구성 개요

Sentinel과 병행 실행하여 두 방식을 비교 학습하는 환경.

```
[Sentinel (Sprint 5-D 인프라, 유지)]
- Master 6379
- Replica 6380, 6381
- Sentinel 26379, 26380, 26381
- 총 6 프로세스

[Cluster A (신규, 7001-7008)]
- Master 7001, 7002, 7003, 7004
- Replica 7005, 7006, 7007, 7008
- 총 8 프로세스, 8 GB Master 저장

[Cluster B (신규, 8001-8008)]
- Master 8001, 8002, 8003, 8004
- Replica 8005, 8006, 8007, 8008
- 총 8 프로세스, 8 GB Master 저장

[전체]
- 22 Redis 프로세스
- 프로덕션 최종 목표 (4 Cluster × 4 Master × 4GB)의 축소판
```

### 6.5-2. 각 Cluster 설정 파일 자동 생성

Cluster A 구성 스크립트:

```bash
# Cluster A 구성 (7001-7008)
cat << 'SCRIPT' > /tmp/setup-cluster-a.sh
#!/bin/bash

NODES=(7001 7002 7003 7004 7005 7006 7007 7008)
CLUSTER_HOME=/home/sonix/redis-cluster-a

mkdir -p $CLUSTER_HOME
for port in "${NODES[@]}"; do
    mkdir -p $CLUSTER_HOME/$port
done

for port in "${NODES[@]}"; do
    cat > $CLUSTER_HOME/$port/redis.conf << EOF
# Redis Cluster A Node - Port $port
port $port
bind 127.0.0.1
protected-mode no

# Cluster 설정
cluster-enabled yes
cluster-config-file nodes-$port.conf
cluster-node-timeout 5000
cluster-require-full-coverage yes

# 영속성
appendonly yes
appendfilename "appendonly-$port.aof"

# 데이터/로그 경로
dir $CLUSTER_HOME/$port
pidfile $CLUSTER_HOME/$port/redis.pid
logfile $CLUSTER_HOME/$port/redis.log
loglevel notice

# 메모리 제한 (1GB - 로컬 실습)
maxmemory 1gb
maxmemory-policy noeviction
EOF
done

echo "Cluster A 설정 완료"
SCRIPT

chmod +x /tmp/setup-cluster-a.sh
/tmp/setup-cluster-a.sh
```

Cluster B 구성 스크립트 (동일한 패턴, 포트만 8001-8008):

```bash
# Cluster B 구성 (8001-8008)
# 위 스크립트와 동일, NODES=(8001 8002 8003 8004 8005 8006 8007 8008)
# CLUSTER_HOME=/home/sonix/redis-cluster-b
```

### 6.5-3. systemd 서비스 등록

```bash
# Cluster A systemd 서비스 등록
cat << 'SCRIPT' > /tmp/create-cluster-a-services.sh
#!/bin/bash

NODES=(7001 7002 7003 7004 7005 7006 7007 7008)
CLUSTER_HOME=/home/sonix/redis-cluster-a

for i in "${!NODES[@]}"; do
    port=${NODES[$i]}
    index=$((i + 1))
    
    sudo tee /etc/systemd/system/redis-cluster-a-$index.service > /dev/null << EOF
[Unit]
Description=Redis Cluster-A Node-$index (Queue Platform, $port)
After=network.target

[Service]
Type=simple
User=sonix
Group=sonix
ExecStart=/usr/bin/redis-server $CLUSTER_HOME/$port/redis.conf
ExecStop=/usr/bin/redis-cli -p $port shutdown
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
done

sudo systemctl daemon-reload
SCRIPT

chmod +x /tmp/create-cluster-a-services.sh
/tmp/create-cluster-a-services.sh
```

Cluster B 서비스도 동일 패턴 (`redis-cluster-b-N.service`).

### 6.5-4. 노드 실행 및 Cluster 초기화

```bash
# Cluster A 실행
for i in 1 2 3 4 5 6 7 8; do
    sudo systemctl enable redis-cluster-a-$i
    sudo systemctl start redis-cluster-a-$i
done

# 노드 상태 확인
for port in 7001 7002 7003 7004 7005 7006 7007 7008; do
    result=$(redis-cli -p $port ping 2>&1)
    echo "Port $port: $result"
done

# Cluster A 초기화 (4 Master + 4 Replica)
redis-cli --cluster create \
  127.0.0.1:7001 127.0.0.1:7002 127.0.0.1:7003 127.0.0.1:7004 \
  127.0.0.1:7005 127.0.0.1:7006 127.0.0.1:7007 127.0.0.1:7008 \
  --cluster-replicas 1
# yes 입력
```

Cluster B도 동일 (포트 8001-8008).

### 6.5-5. 초기 배치 결과

Cluster A 초기화 후 Slot 배정:

```
Master 7001: Slot 0-4095
Master 7002: Slot 4096-8191
Master 7003: Slot 8192-12287
Master 7004: Slot 12288-16383

Replica 매칭 (자동):
- 7005 → 7002의 Replica
- 7006 → 7001의 Replica
- 7007 → 7003의 Replica
- 7008 → 7004의 Replica
```

Cluster B도 동일한 4096 slot 균등 분배.

### 6.5-6. 검증 명령

```bash
# Cluster A 상태
redis-cli -c -p 7001 cluster info
# 기대: cluster_state:ok, cluster_size:4, cluster_known_nodes:8

redis-cli -c -p 7001 cluster nodes

# Cluster B 상태
redis-cli -c -p 8001 cluster info
redis-cli -c -p 8001 cluster nodes

# 전체 프로세스 확인
ps aux | grep -E "redis-server|redis-sentinel" | grep -v grep | wc -l
# 기대: 22 (Sentinel 6 + Cluster A 8 + Cluster B 8)

# 완전 독립성 검증
redis-cli -c -p 7001 SET "queue:test_a:waiting" "cluster_a_data"
redis-cli -c -p 8001 SET "queue:test_b:waiting" "cluster_b_data"

# Cluster A에서 test_b 조회 (없어야 함 - 완전 격리)
redis-cli -c -p 7001 GET "queue:test_b:waiting"

# 정리
redis-cli -c -p 7001 DEL "queue:test_a:waiting"
redis-cli -c -p 8001 DEL "queue:test_b:waiting"
```

### 6.5-7. Failover 테스트

Cluster A의 Master 하나 강제 종료:

```bash
# 현재 Master 확인
redis-cli -c -p 7001 cluster nodes | grep master

# Master 7001 강제 종료
sudo systemctl stop redis-cluster-a-1

# 10초 대기 (Cluster 감지 + Failover)
sleep 10

# Failover 결과 확인 - 7006(Replica)가 Master로 승격
redis-cli -c -p 7002 cluster nodes

# 원상 복구 (7001 재시작 시 Replica로 강등)
sudo systemctl start redis-cluster-a-1
```

### 6.5-8. Hash Tag 실전 활용

각 Master로 Queue를 명시적 배치:

```bash
# 각 shard tag의 slot 확인
for tag in "shard_A1" "shard_A2" "shard_A3" "shard_A4"; do
    slot=$(redis-cli -c -p 7001 cluster keyslot "queue:{$tag}:test:waiting")
    
    if [ $slot -le 4095 ]; then
        master="Master 1 (7001)"
    elif [ $slot -le 8191 ]; then
        master="Master 2 (7002)"
    elif [ $slot -le 12287 ]; then
        master="Master 3 (7003)"
    else
        master="Master 4 (7004)"
    fi
    
    echo "Tag $tag: slot=$slot → $master"
done
```

Hash Tag로 특정 Master에 Queue 강제 배치:

```bash
# {shard_X} 문법으로 애플리케이션이 Master 선택 가능
redis-cli -c -p 7001 SET "queue:{shard_A1}:q_bts_001:waiting" "user_1"
redis-cli -c -p 7001 SET "queue:{shard_A2}:q_bts_002:waiting" "user_2"
```

### 6.5-9. Cluster 정리 명령

```bash
# 모든 Cluster A 노드 정지
for i in 1 2 3 4 5 6 7 8; do
    sudo systemctl stop redis-cluster-a-$i
done

# Cluster 데이터 초기화 (재구성 시)
for port in 7001 7002 7003 7004 7005 7006 7007 7008; do
    dir="/home/sonix/redis-cluster-a/$port"
    rm -f $dir/nodes-$port.conf
    rm -f $dir/appendonly-$port.aof
    rm -f $dir/dump.rdb
done

# 재시작
for i in 1 2 3 4 5 6 7 8; do
    sudo systemctl start redis-cluster-a-$i
done
```

### 6.5-10. 로컬 vs 프로덕션 매핑

```
[로컬 실습 (지금)]
- 2 Cluster (A, B)
- 각 4 Master + 4 Replica
- 각 Master 1 GB
- 총 저장: 8 GB (Master만)
- 총 처리량 (이론): 320,000 ops/초

[프로덕션 최종 목표 (Sprint 15+)]
- 4 Cluster
- 각 4 Master + 4 Replica
- 각 Master 4 GB
- 총 저장: 64 GB (Master만)
- 총 처리량 (이론): 640,000 ops/초

[확장 비율]
- Cluster 수: 2배 (2 → 4)
- Master 크기: 4배 (1GB → 4GB)
- 총 처리량: 2배 (Master 수 2배)
- 총 저장: 8배 (크기 4배 × Cluster 2배)
```

### 6.5-11. 아키텍처 결정 이유

**왜 Master를 작게, 많이 두는가?**

Redis는 Single Thread 특성으로 각 Master가 CPU 1개만 사용:

```
[성능 관점]
- 각 Master: 최대 40,000 ops/초 (Single Thread 한계)
- Master 크기가 커도 성능 X (CPU 낭비)
- Master 수를 늘려야 처리량 선형 증가

[본인 프로젝트 계산]
- 3 Master: 120,000 ops/초
- 5 Master: 200,000 ops/초
- 8 Master (Cluster A+B): 320,000 ops/초 ⭐
- 16 Master (프로덕션): 640,000 ops/초

[관리 vs 성능]
- Master 4개 (Cluster당): 관리 감당 가능
- CPU 코어 4개 완전 활용
- Failover 손실 25% (4개 중 1)
```

**왜 2 Cluster로 나누는가?**

```
[격리 관점]
- Cluster 간 완전 격리 (통신 X)
- 하나의 Cluster 부하가 다른 Cluster에 영향 없음
- 애플리케이션 라우팅으로 Tenant/Queue별 배치

[실습 가치]
- 다중 Cluster 라우팅 학습
- Cluster 간 격리 검증
- 프로덕션 아키텍처 사전 검증
```

### 6.5-12. 이중 라우팅 아키텍처 (Sprint 12+)

Cluster + Hash Tag 조합으로 완전한 제어:

```
[Layer 1 - Cluster Router (애플리케이션)]
- Tenant Tier 기반
- 예상 규모 기반
- Least Load 알고리즘
- Cluster A 또는 B 선택

[Layer 2 - Hash Tag (Cluster 내)]
- {shard_X} 문법
- 부하 기반 Master 선택
- Cluster 내 4 Master 중 하나 결정

[최종 Key 예시]
"queue:{shard_A2}:q_bts_002:waiting"
- Cluster: A (Application 결정)
- Master: 2 (shard_A2 Hash Tag)
- Queue: q_bts_002
```

**Application 코드 흐름**:

```java
// Layer 1 - Cluster 선택
Cluster targetCluster = clusterRouter.selectCluster(tenant, request);

// Layer 2 - Master 선택 (Hash Tag)
String shard = shardResolver.selectShard(targetCluster);

// 최종 Key 구성
String redisKey = String.format("queue:{%s}:%s:waiting", shard, queueId);

// Lettuce가 {shard} 부분으로 slot 계산 → 자동 라우팅
targetCluster.execute(enqueueScript, redisKey, ...);
```

### 6.5-13. 참고 - 실측 결과

로컬 실습 완료 시 확인된 사항:

| 항목 | 결과 |
|------|------|
| 총 Redis 프로세스 | 22개 (Sentinel 6 + Cluster A 8 + Cluster B 8) |
| 총 포트 사용 | 41개 (Redis + Cluster Bus + Sentinel IPv6) |
| 메모리 사용 | 약 900MB (각 프로세스 40-50MB) |
| 디스크 여유 요구 | 최소 2GB (실제 사용 <500MB) |
| Cluster 간 격리 | 완벽 (교차 접근 불가) |
| Sentinel과 병행 | 무충돌 (서로 다른 포트 대역) |

---

## 7. Prometheus + Grafana (모니터링 스택)

WSL2에서 직접 실행. Spring Boot Actuator의 메트릭을 수집/시각화.

### 7-1. 모니터링 아키텍처

```
[Windows]
└── Spring Boot (queue-api, 8080)
       └── /actuator/prometheus  ← Pull 대상

         ↑ scrape (15초마다)
[WSL2]
├── Prometheus (9090)
│     └── 시계열 DB + scrape 관리
│              ↑ PromQL 쿼리
└── Grafana (3000)
       └── 대시보드 시각화
```

**핵심 결정:**
- Pull 방식 (Prometheus가 scrape) — 애플리케이션 단순화
- Micrometer 추상화 — 백엔드 교체 가능
- WSL ↔ Windows 통신 — Windows host IP 사용

### 7-2. Spring Boot 메트릭 노출 사전 작업

`queue-api/build.gradle`:

```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'  // 추가
}
```

`queue-api/src/main/resources/application.yml` (공통):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus
  prometheus:
    metrics:
      export:
        enabled: true
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
      percentiles:
        http.server.requests: 0.5, 0.95, 0.99
```

`SecurityConfig.java`에 Actuator 인증 우회 추가:

```java
.requestMatchers("/actuator/**").permitAll()
```

검증:

```bash
curl http://localhost:8080/actuator/prometheus | head -20
# JVM, HTTP, HikariCP 메트릭 출력 확인
```

### 7-3. Prometheus 설치

```bash
cd ~

# Prometheus 3.0.1 다운로드
wget https://github.com/prometheus/prometheus/releases/download/v3.0.1/prometheus-3.0.1.linux-amd64.tar.gz

# 압축 해제
tar xvf prometheus-3.0.1.linux-amd64.tar.gz
mv prometheus-3.0.1.linux-amd64 prometheus

# 정리
rm prometheus-3.0.1.linux-amd64.tar.gz

# 확인
~/prometheus/prometheus --version
# prometheus, version 3.0.1 ...
```

### 7-4. Prometheus 설정

`~/prometheus/prometheus.yml`:

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s
  external_labels:
    cluster: 'queue-platform'
    environment: 'local'

scrape_configs:
  # Prometheus 자기 자신
  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']

  # Queue Platform API
  - job_name: 'queue-platform-api'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['172.19.64.1:8080']    # Windows host IP
        labels:
          application: 'queue-api'
          environment: 'local'

  # Queue Platform Consumer (Kafka lag / 적재량)
  - job_name: 'queue-platform-consumer'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['172.19.64.1:8082']    # Windows host IP
        labels:
          application: 'queue-consumer'
          environment: 'local'

# queue-batch(8081)는 actuator 의존성이 없어 /actuator/prometheus를 노출하지 않는다.
# job을 넣으면 항상 DOWN으로 뜨므로, 의존성을 추가한 뒤에 등록할 것.

# 향후 확장 (Phase 5 — 인프라 Exporter):
# - job_name: 'mysql'         # mysqld_exporter (9104)
# - job_name: 'redis'         # redis_exporter (9121)
# - job_name: 'kafka'         # kafka_exporter (9308)
# - job_name: 'node'          # node_exporter (9100)
```

**핵심 — Windows host IP 확인:**

```bash
ip route show | grep -i default | awk '{ print $3 }'
# 출력 예: 172.19.64.1

# 검증
curl http://172.19.64.1:8080/actuator/prometheus | head -5
```

⚠️ **주의**: WSL2 재부팅 시 IP가 바뀔 수 있음. §7-7 자동화 스크립트 참조.

**설정 검증:**

```bash
~/prometheus/promtool check config ~/prometheus/prometheus.yml
# SUCCESS: prometheus.yml is valid
```

### 7-5. Grafana 설치 (APT)

```bash
# 의존성
sudo apt update
sudo apt install -y software-properties-common wget apt-transport-https

# Grafana GPG 키
sudo mkdir -p /etc/apt/keyrings/
wget -q -O - https://apt.grafana.com/gpg.key | gpg --dearmor | sudo tee /etc/apt/keyrings/grafana.gpg > /dev/null

# Grafana 저장소
echo "deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main" \
    | sudo tee -a /etc/apt/sources.list.d/grafana.list

# 설치
sudo apt update
sudo apt install -y grafana

# 자동 시작 비활성화 (직접 관리)
sudo systemctl stop grafana-server
sudo systemctl disable grafana-server

# 버전 확인
grafana-server -v
```

### 7-6. Grafana 초기 설정

Grafana를 한 번 시작해서 데이터 소스만 등록:

```bash
sudo systemctl start grafana-server
sudo systemctl status grafana-server
```

브라우저에서 `http://localhost:3000`:

```
[로그인]
Username: admin
Password: admin
→ 비밀번호 변경 요청 (강력한 비밀번호 설정)

[Prometheus 데이터 소스 추가]
1. 좌측 메뉴 → Connections → Data sources
2. "Add new data source" → Prometheus 선택
3. URL: http://localhost:9090
4. Save & Test
   → "Successfully queried the Prometheus API"

[공식 대시보드 임포트 — 추천]
Dashboards → New → Import
ID: 11378 (Spring Boot Statistics)
ID: 4701  (JVM Micrometer)
```

설정 완료 후 systemd 종료 (자동 관리 스크립트로 통합):

```bash
sudo systemctl stop grafana-server
```

### 7-7. 자동 시작/관리 스크립트 (~/.bashrc)

`§6-6`의 redis 함수와 같은 패턴으로 추가:

```bash
cat >> ~/.bashrc << 'EOF'

# ============================================================
# Queue Platform — Monitoring (Prometheus + Grafana)
# ============================================================

QP_PROM_DIR=~/prometheus

mon_start() {
    echo "Starting Prometheus + Grafana..."
    
    # 1. Windows host IP 자동 갱신 (WSL2 재부팅 대응)
    local win_host=$(ip route show | grep -i default | awk '{ print $3 }')
    if [ -n "$win_host" ]; then
        sed -i.bak -E "s|targets: \['[0-9.]+:8080'\]|targets: ['${win_host}:8080']|" \
            $QP_PROM_DIR/prometheus.yml
        echo "  Windows host IP: $win_host"
    fi
    
    # 2. Prometheus 실행
    if pgrep -f "prometheus --config" > /dev/null; then
        echo "  Prometheus: already running"
    else
        cd $QP_PROM_DIR
        nohup ./prometheus --config.file=prometheus.yml > prometheus.log 2>&1 &
        echo "  Prometheus: started (PID: $!)"
        cd - > /dev/null
    fi
    
    # 3. Grafana 실행
    if systemctl is-active --quiet grafana-server; then
        echo "  Grafana: already running"
    else
        sudo systemctl start grafana-server
        echo "  Grafana: started"
    fi
    
    sleep 2
    echo ""
    echo "Access:"
    echo "  Prometheus: http://localhost:9090"
    echo "  Grafana:    http://localhost:3000"
}

mon_stop() {
    echo "Stopping monitoring stack..."
    pkill -f "prometheus --config"
    sudo systemctl stop grafana-server
    sleep 1
    echo "Done."
}

mon_status() {
    echo "=== Monitoring Stack ==="
    
    # Prometheus
    if pgrep -f "prometheus --config" > /dev/null; then
        echo "  Prometheus: RUNNING (http://localhost:9090)"
    else
        echo "  Prometheus: STOPPED"
    fi
    
    # Grafana
    if systemctl is-active --quiet grafana-server; then
        echo "  Grafana:    RUNNING (http://localhost:3000)"
    else
        echo "  Grafana:    STOPPED"
    fi
    
    echo ""
    echo "=== Targets (last scrape) ==="
    curl -s http://localhost:9090/api/v1/targets 2>/dev/null \
        | grep -oE '"health":"[^"]+","[^"]+":"[^"]+","instance":"[^"]+"' \
        | head -5 \
        || echo "  (Prometheus not responding)"
}

mon_logs() {
    local target=${1:-prometheus}
    case $target in
        prometheus|prom) tail -f $QP_PROM_DIR/prometheus.log ;;
        grafana)         sudo journalctl -u grafana-server -f ;;
        *) echo "Usage: mon_logs [prometheus|grafana]" ;;
    esac
}
EOF

source ~/.bashrc
```

### 7-8. 정상 동작 검증

```bash
# 1. 시작
mon_start

# 2. 상태 확인
mon_status

# 기대 결과:
#   Prometheus: RUNNING (http://localhost:9090)
#   Grafana:    RUNNING (http://localhost:3000)

# 3. Prometheus Targets 확인
# 브라우저: http://localhost:9090/targets
# → queue-platform-api: UP 확인

# 4. 메트릭 쿼리 확인
# 브라우저: http://localhost:9090/graph
# 쿼리:
#   up                              → 1 (UP)
#   jvm_memory_used_bytes{area="heap"}
#   http_server_requests_seconds_count

# 5. Grafana 대시보드 확인
# 브라우저: http://localhost:3000
# → 임포트한 대시보드에서 그래프 보이는지

# 6. API 호출 후 메트릭 변화 관찰
curl -X POST http://172.19.64.1:8080/api/v1/tenants/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123!","name":"Test"}'

# 15초 대기 후 쿼리:
# http_server_requests_seconds_count{uri="/api/v1/tenants/signup"}
# → 카운트 증가 확인
```

### 7-9. Grafana 대시보드 N/A 문제

대시보드 임포트 후 패널이 "N/A" 또는 "No data" 표시되는 경우:

**원인**: 대시보드의 `application` 변수가 우리 메트릭 라벨과 불일치

**진단**:

```
1. Prometheus에서 직접 쿼리
   http://localhost:9090/graph
   쿼리: jvm_memory_used_bytes
   → 라벨 확인 (application="queue-api"가 있는지)

2. Grafana 대시보드 상단 변수
   Application 드롭다운 옵션 확인
   → 옵션이 비어있으면 변수 정의 문제

3. Grafana Explore에서 직접 쿼리
   좌측 메뉴 → Explore → Prometheus 선택
   쿼리: jvm_memory_used_bytes
   → 데이터 보이면 데이터 소스는 정상
```

**해결**:

```
A. 다른 대시보드 시도
   - 11378 (Spring Boot 2.1 Statistics)
   - 12900 (Spring Boot 2.1 Statistics 변형)

B. 직접 패널 만들기 (확실)
   Dashboards → New → New dashboard → Add visualization
   Query: jvm_memory_used_bytes{area="heap"}

C. 대시보드 변수 수정
   Settings → Variables → application
   Query: label_values(jvm_memory_used_bytes, application)
```

### 7-10. 데이터 보존 정책 (선택)

기본 데이터 보존: 15일.

장기 보존 시 Prometheus 시작 옵션 변경:

```bash
~/prometheus/prometheus \
  --config.file=prometheus.yml \
  --storage.tsdb.retention.time=30d \
  --storage.tsdb.path=./data
```

`mon_start` 함수의 nohup 라인 수정해서 반영 가능.

---

## 8. Claude Code 통합

### 8-1. Windows에 Claude Code 설치

```powershell
# PowerShell (관리자 권한 X)
irm https://claude.ai/install.ps1 | iex

# 재시작 후 확인
claude --version
```

### 8-2. IntelliJ Plugin 설치

```
IntelliJ Settings (Ctrl+Alt+S)
  → Plugins → Marketplace
  → "Claude Code [Beta]" 검색 (제작자: Anthropic)
  → Install → IntelliJ 재시작
```

### 8-3. 첫 인증

```
1. IntelliJ에서 프로젝트 열기
2. Ctrl+Esc → Claude 패널 열림
3. 브라우저 자동 열림 → Anthropic 계정 로그인
4. 인증 완료 → IntelliJ로 토큰 자동 전달
```

### 8-4. CLAUDE.md 파일

프로젝트 루트의 `CLAUDE.md`가 Claude Code의 자동 컨텍스트로 사용됨.
상세 내용은 별도 문서 참조.

---

## 9. 포트 사용 요약

### 기본 인프라

| 포트 | 용도 |
|------|------|
| 3306 | MySQL Master |
| 3307 | MySQL Replica |
| 6379 | Redis Sentinel Master |
| 6380 | Redis Sentinel Slave 1 |
| 6381 | Redis Sentinel Slave 2 |
| 26379 | Redis Sentinel 1 |
| 26380 | Redis Sentinel 2 |
| 26381 | Redis Sentinel 3 |
| 9090 | Prometheus |
| 3000 | Grafana |
| 9092 | (Sprint 8+) Kafka Broker |
| 8080 | queue-api Spring Boot |

### Redis Cluster (Sprint 8+ 학습 환경)

| 포트 대역 | 용도 |
|-----------|------|
| 7001-7004 | Cluster A Master (4개) |
| 7005-7008 | Cluster A Replica (4개) |
| 17001-17008 | Cluster A Bus (자동, Redis 포트 + 10000) |
| 8001-8004 | Cluster B Master (4개) |
| 8005-8008 | Cluster B Replica (4개) |
| 18001-18008 | Cluster B Bus (자동) |

향후 추가 (Phase 5 — 인프라 Exporter):

| 포트 | 용도 |
|------|------|
| 9100 | node_exporter (시스템 리소스) |
| 9104 | mysqld_exporter |
| 9121 | redis_exporter |
| 9308 | kafka_exporter |

---

## 10. 자주 쓰는 명령어 모음

### 인프라 관리

```bash
# Redis (직접 만든 함수)
redis_start
redis_stop
redis_status
redis_logs master

# Monitoring (Prometheus + Grafana)
mon_start
mon_stop
mon_status
mon_logs prometheus
mon_logs grafana

# MySQL (수동)
mysql -u root -p -P 3306    # Master
mysql -u root -p -P 3307    # Replica

# 포트 사용 확인
sudo ss -tlnp | grep -E '3306|3307|6379|6380|6381|26379|26380|26381|9090|3000'

# 프로세스 확인
ps aux | grep -E "redis|mysql|prometheus|grafana"
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

## 11. 트러블슈팅

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

### Prometheus 관련

#### 문제: Targets에서 queue-platform-api: DOWN

**원인 1**: Spring Boot 미실행
```bash
# Windows에서 Spring Boot 실행 중인지 확인
curl http://172.19.64.1:8080/actuator/health
```

**원인 2**: Windows host IP 변경 (WSL2 재부팅 후)
```bash
# 현재 Windows host IP 확인
ip route show | grep default | awk '{ print $3 }'

# prometheus.yml의 targets와 다르면 mon_start로 자동 갱신
mon_start
```

**원인 3**: Spring Boot가 localhost만 listen
```bash
# Windows에서 확인
netstat -an | findstr 8080
# TCP    0.0.0.0:8080 (모든 인터페이스) ← 정상
# TCP    127.0.0.1:8080 (localhost만) ← 문제

# 해결: application.yml에 server.address: 0.0.0.0 추가 후 재시작
```

**원인 4**: Windows Defender 방화벽 차단
```
Windows 방화벽에서 인바운드 규칙 추가:
  - 포트 8080
  - 허용 (TCP)
  - WSL 가상 네트워크 허용
```

#### 문제: Actuator endpoint가 401/403 반환

**원인**: SecurityConfig에서 actuator 허용 안 함  
**해결**: 
```java
.requestMatchers("/actuator/**").permitAll()
```

### Grafana 관련

#### 문제: 대시보드 패널이 모두 "N/O data" 또는 "N/A"

**진단 흐름**:

```
1. Prometheus 직접 확인 (http://localhost:9090)
   쿼리: up
   → 1이 보여야 함

2. Grafana Explore에서 동일 쿼리 (http://localhost:3000/explore)
   데이터 소스: Prometheus
   쿼리: jvm_memory_used_bytes
   → 그래프 보여야 함

3. 대시보드 상단 변수 (application, instance) 확인
   → 드롭다운에 옵션이 있어야 함
   → 옵션 없으면 변수 정의 또는 라벨 매칭 문제
```

**해결**: 위 §7-9 참조

#### 문제: Grafana 시작 안 됨 (systemd 에러)

**원인**: WSL 옛 버전에서 systemd 비활성화  
**해결**:
```bash
# /etc/wsl.conf 작성
sudo nano /etc/wsl.conf

# 내용:
[boot]
systemd=true

# Windows PowerShell (관리자):
wsl --shutdown

# WSL 재시작 후
systemctl --version
```

### 권한 관련

#### 문제: `Permission denied` 에러

**원인**: 시스템 디렉토리에 sudo로 만든 파일 소유자가 root  
**해결**:
```bash
sudo chown -R sonix:sonix ~/queue-platform-infra/
sudo chown -R sonix:sonix ~/prometheus/
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

## 12. 환경 재구축 절차 (Disaster Recovery)

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
7. Prometheus + Grafana 설치 (위 §7)
8. ~/.bashrc 스크립트 설정 (위 §6-6, §7-7)
9. 기동 + 검증
   redis_start
   redis_status
   mon_start
   mon_status
10. Windows IntelliJ + Claude Code 설치 (위 §8)
11. 프로젝트 빌드 테스트
    cd ~/queue-platform && ./gradlew build
12. Grafana 데이터 소스 + 대시보드 임포트 (위 §7-6)
```

---

## 13. 백업 / 데이터 보존

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

### Prometheus 데이터 보존

기본 보존: 15일. Prometheus 자체가 시계열 DB.

```bash
# 데이터 위치
ls -lh ~/prometheus/data/

# 보존 기간 변경 — mon_start 함수의 prometheus 실행 옵션에 추가
./prometheus --config.file=prometheus.yml \
  --storage.tsdb.retention.time=30d

# 운영 환경에선 별도 장기 저장 (Thanos, Cortex) 고려
```

### Grafana 설정 백업

```bash
# Grafana 설정 + 대시보드는 /var/lib/grafana에 저장
sudo cp -r /var/lib/grafana ~/backups/grafana_$(date +%Y%m%d)

# 핵심: grafana.db (SQLite)
sudo cp /var/lib/grafana/grafana.db ~/backups/grafana_$(date +%Y%m%d).db
```

---

## 14. 참조 문서

| 문서 | 내용 |
|------|------|
| `docs/sprint-5/REDIS_SENTINEL.md` | Sprint 5 Phase 1 Sentinel 학습 노트 |
| `docs/sprint-5/LUA_SCRIPTS.md` | Sprint 5 Phase 2 Lua Script 분석 |
| `docs/schema.sql` | MySQL DDL + 파티션 운영 쿼리 |
| `docs/DECISIONS.md` | §30 Redis Master/Replica Sentinel 설계 결정 |
| `docs/monitoring/MONITORING_DESIGN.md` | 모니터링 시스템 설계 (5개 카테고리) |
| `CLAUDE.md` | 프로젝트 컨텍스트 (Claude Code 자동 로드) |

---

## 15. 향후 추가 예정 (Sprint 8~15)

### Sprint 8: Redis Cluster 학습 (완료 - 6.5 섹션 참조)

로컬 실습 환경 완료:
- Cluster A + B 병행 실행
- Sentinel 유지
- Failover 검증 완료

### Sprint 10: Redis Cluster 프로덕션 도입

```
[프로덕션 최소 구성]
- 3 Master + 3 Replica
- 각 Master 8-16 GB
- Multi-AZ 배치
- AWS ElastiCache 또는 자체 관리

[성능 목표]
- 초당 120,000-200,000 ops
- Failover 33% 손실
- 관리 부담 낮음
```

### Sprint 11: Kafka KRaft

```bash
# Kafka 3.5+ KRaft 모드 설치 (Zookeeper 없이)
# 디렉토리: ~/queue-platform-infra/kafka/
# 포트: 9092
```

### Sprint 12: Cluster 확장 + Hash Tag

```
[확장 구성]
- 3 Master → 5-7 Master
- Hash Tag 정책 도입
- 부하 기반 Shard 선택 (Layer 2)
- 이중 라우팅 시작
```

### Sprint 15+: 4x4x4GB 극대 분산

```
[최종 목표 구성]
- 4 Cluster × 4 Master × 4 Replica
- 각 Master 4 GB
- 총 32 노드
- 640,000 ops/초 처리 능력
- 물리 서버 4대 (r6g.2xlarge)
- Multi-AZ 배치
- 이중 라우팅 완전 구현

[비용]
- $1,961-2,561/월 (EC2 자체 관리)
- 1억 대기 처리 가능
```

### Phase 5 (모니터링 확장): 인프라 Exporter

```bash
# mysqld_exporter (9104)
# redis_exporter (9121)
# kafka_exporter (9308)
# node_exporter (9100) — 시스템 리소스

# 각 Exporter를 WSL에서 실행 후
# prometheus.yml에 scrape_configs 추가
```

### Phase 6 (모니터링): AlertManager + 알림

```bash
# AlertManager (9093) 추가
# Slack/Discord Webhook 통합
# Alert Rules: 임계치 기반 자동 알림
```

### Sprint 11: AWS 배포 시

```
WSL2 인프라 → AWS Managed Services 매핑:
  MySQL Master/Replica → RDS MySQL with Read Replica
  Redis Sentinel → ElastiCache Redis with Multi-AZ
  Kafka KRaft → MSK (Managed Streaming for Kafka)
  Prometheus/Grafana → Managed Grafana, CloudWatch, 또는
                       Self-managed on EKS
```

---

## 16. 보안 주의사항

```
⚠️ 운영 환경에서 절대 하지 말 것:
  - protected-mode no (외부 노출)
  - bind 0.0.0.0 (모든 IP에서 접근)
  - 기본 비밀번호 사용
  - 평문 패스워드 (config 파일에)
  - Prometheus를 외부에 직접 노출
  - Grafana admin/admin 유지

✅ 운영 환경 권장:
  - requirepass 설정 (Redis)
  - AUTH 필수 (Redis Sentinel)
  - SSL/TLS 통신
  - 비밀번호는 환경 변수 또는 Vault
  - 방화벽 규칙 (특정 IP만 허용)
  - Prometheus: 인증 필수 (Basic Auth, OAuth)
  - Grafana: LDAP/SAML 통합
  - /actuator endpoint: 인증 필수 (운영)
```

이 INFRA_SETUP.md는 **로컬 개발 환경** 기준이므로 운영 시 추가 보안 강화 필요.