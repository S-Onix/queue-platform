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
| 9090 | Prometheus |
| 3000 | Grafana |
| 9092 | (Sprint 8+) Kafka Broker |
| 8080 | queue-api Spring Boot |

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

## 15. 향후 추가 예정 (Sprint 8~11)

### Sprint 8: Kafka KRaft

```bash
# Kafka 3.5+ KRaft 모드 설치 (Zookeeper 없이)
# 디렉토리: ~/queue-platform-infra/kafka/
# 포트: 9092
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