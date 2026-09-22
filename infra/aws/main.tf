# 부하 실측 전용 환경. 세션이 끝나면 terraform destroy 한다.
#
# 🔴 1차(단일 m7g.xlarge 4 vCPU)는 무효 판정했다 (2026-09-12).
#    idle 0~2.4% 로 완전 포화 — 컨테이너 합 375%/400%(api 3대 ~250% · kafka 3대 66% ·
#    mysql 40% · consumer 19%). enqueue p95 2.30s(로컬 16코어 RF=1 에서는 33.59ms)인데
#    실패율은 0% 였다. 에러가 아니라 전부 "느림"이었다.
#    ⚠️ 앱이 200rps 를 못 버틴다는 뜻이 아니다. 한 대에 6종을 전부 올려 같은 코어를
#       두고 경쟁시킨 것이 원인이다.
#
# 그래서 역할별로 가른다. 나누는 기준은 실측 부하다:
#   app(api 3대 ~250%) · data(mysql+kafka+redis ~108%) · worker(consumer 19% + batch <12%)
#   worker 를 따로 빼는 이유는 무게가 아니라 측정이다 — 컨슈머 lag 이 CPU 부족 때문인지
#   설계 때문인지 지금까지 구분할 수 없었다. 전용 코어를 주면 그게 갈린다.
#
# 모든 노드는 같은 AZ 에 둔다. AZ 를 넘으면 지연이 붙고 전송료도 붙는다.

terraform {
  required_version = ">= 1.9"
  required_providers {
    aws  = { source = "hashicorp/aws", version = "~> 5.0" }
    http = { source = "hashicorp/http", version = "~> 3.0" }
  }
}

provider "aws" {
  region = "ap-northeast-2"
}

# 2026-09-12 spot 최저가 AZ. AZ 마다 재고가 달라 값이 갈린다(같은 날 2a 는 m7g 가 1.5배였다).
variable "use_spot" {
  description = "스팟으로 띄울지. 측정 판은 false(온디맨드) — 스팟은 측정 중 회수당한다."
  type        = bool
  default     = false
}

locals {
  az = "ap-northeast-2d"

  # 역할 → 사양.
  #
  # 🔑 2차 실측(2026-09-12)에서 data 한 대를 셋으로 갈랐다. 근거는 추측이 아니라
  #    부하 중 컨테이너별 CPU 실측이다(500 RPS, 100% = 1코어):
  #      mysql 0.83 · kafka ×3 합 1.09 · redis A ×3 합 0.33 · redis B ×3 합 0.01
  #      컨테이너 합 2.29 인데 노드 전체는 3.07 — 나머지 0.78 은 커널(softirq·네트워크)이다.
  #    합쳐 두면 셋이 4 vCPU 를 두고 순번을 다툰다. 실제로 배치 크기를 키우자
  #    **MySQL 을 안 쓰는 poll 의 p95 가 +57% 로 가장 크게** 악화됐다 — 락이 아니라
  #    CPU 순번이었다는 증거다(락 대기는 6,800 커밋당 1건).
  #
  # 🔑 제일 무거운 것은 mysql 이 아니라 kafka 다(1.09 vs 0.83). RF=3 이라 쓰기가 3배로 분다.
  #    그래서 kafka 에만 4 vCPU 를 준다 — 게다가 브로커 간 복제가 이제 노드 안이 아니라
  #    네트워크로 나가므로 여유가 더 필요하다.
  # ⚠️ 쪼갠다고 CPU 총량이 주는 것이 아니다. 홉이 늘어 오히려 조금 는다.
  #    얻는 것은 "서로 기다리지 않는 것"이다.
  #
  # ✅ 3차 실측(2026-09-12 밤)에서 이 분리가 **이겼다**. 500 RPS 60초, 실패 0%:
  #      admit p95 242.94 → 167.2ms (−31%) · enqueue p95 53.46 → 37.9ms (−29%)
  #      poll p95 18.94 → 11.7ms (−38%) · complete avg 26.39 → 19.4ms (−26%)
  #    목표 부하 200 RPS 는 여유롭다 — enqueue p95 25.6~28.4ms · admit p95 124~128ms.
  #
  # 🔴 그 직전 판(admit p95 2.46s)은 **토폴로지 탓이 아니었다.** 3노드 round 4(워밍업 3판
  #    누적)와 6노드 round 1 을 비교한 것이 원인이다. 같은 6노드를 워밍업 2판 뒤에 재면
  #    167ms 다 — 15배 차. 홉 세금 자체는 재현된다(app sys 20.3%)지만 **인과가 아니다.**
  #    🪤 판 사이에 Kafka lag 이 0 이 될 때까지 기다려야 한다. 남은 lag 이 다음 판의
  #       MySQL 쓰기로 새어들어 판 간 오염이 된다.
  #    절차 정본은 `~/queue-platform-it/aws-round3-20260912/roundB.sh` (비커밋).
  #
  # 🔴 다음 병목은 app 이다(500 RPS 에서 83.5%). 다만 **컨슈머 드레인 천장 225/s 가 먼저다** —
  #    200 RPS 에서도 이벤트 유입이 ~590/s 라 lag 이 쌓인다(worker 는 4.2% 로 남아돈다).
  #    컨슈머를 안 고치고 app 만 키우면 lag 만 더 쌓인다.
  #
  # 🔴 **시간당 합계 $2.46 (2026-09-18 AWS Pricing API 실측, 서울·온디맨드).**
  #    예전 주석은 "$0.169" 였는데 **14.5배 틀렸다** — app 이 m7g.xlarge ×1 이던 시절 값이
  #    5차의 4xlarge ×2 증설 뒤에도 그대로 남아 있었다. 예산 판단의 근거라 특히 위험했다.
  #      app 0.8024 ×2 · k6 0.3264 · kafka 0.1632 · mysql 0.1003 · obs 0.0816 · redis 0.1003
  #      · worker 0.0816  (+ EIP 0.005). 170분 판이면 약 $7, 빌드·대기까지 4시간이면 약 $10.
  #    총 52 vCPU — 온디맨드 쿼터(6차에 64로 올렸다)를 넘지 않는지 판 전에 확인하라.
  nodes = {
    # 🔴 6차(2026-09-16): 200만 전 구간 + 생애주기 판. 쿼터가 64 로 올라 16 vCPU ×2 가 된다
    #    (app 32 + data 10 + k6 8 = 50). 5차는 쿼터 32 가 벽이라 8+4 로 갈 수밖에 없었다.
    app = "m7g.4xlarge" # queue-api ×3 — 4차 f500 에서 CPU 90.1%. 5차에서 증설해 잰다
    # 🔑 5차: app 노드를 둘로 나눠 **수평 확장 전제를 실증한다**(CLAUDE.md "N대 Stateless").
    #    같은 노드에 컨테이너만 늘리는 것은 무의미하다 — 폴링 10,000 rps 에서 노드 CPU 가
    #    94.86% 로 이미 포화였다. 늘려야 하는 것은 프로세스가 아니라 **노드(=vCPU)** 다.
    #    🪤 스팟 쿼터가 32 vCPU 다. data 10 + app 8 + k6 8 = 26 이라 여유가 6 뿐이라 xlarge(4)다.
    app2 = "m7g.4xlarge" # queue-api ×3 (2번째 노드)
    # 🔴 **관측을 여기서 떼어냈다 (2026-09-18).** 8차에서 이 노드가 2 vCPU 인데 mysql +
    #    prometheus + grafana + alertmanager + redis-exporter 를 같이 돌렸다. 그 결과
    #    "쿼리 시간 27,468초 vs 이 노드가 낼 수 있었던 18,360 CPU-s" → **최소 33%가 대기**였고
    #    항목별 절대 초를 **해석할 수 없게** 됐다. 게다가 Prometheus 가 같은 노드에 있어
    #    **관측이 관측 대상을 오염**시켰다. 이제 이 노드는 MySQL 전용이다.
    mysql = "m7g.large" # mysql 전용 (8차 0.83코어 — 관측을 뗀 뒤 진짜 값을 처음 본다)
    # 🔑 관측 전용. Prometheus(800m) + Grafana(500m) + Alertmanager(200m) + redis-exporter(200m)
    #    + node-exporter(200m) = 1.9GB 라 4GB/2 vCPU 로 충분하다.
    #    🪤 redis-exporter 는 **Prometheus 와 같은 노드여야 한다** — prometheus.yml 이
    #       replacement: localhost:9121 로 부른다(multi-target 이라 Redis 노드일 필요는 없다).
    obs    = "c7g.large"  # prometheus · grafana · alertmanager · redis-exporter
    kafka  = "c7g.xlarge" # kafka ×3 (1.09코어) — RF=3 복제가 네트워크로 나간다
    redis  = "m7g.large"  # redis ×6 (0.34코어) — 싱글스레드라 코어 수보다 코어 성능이다
    worker = "c7g.large"  # queue-batch + queue-consumer (3.4% — 남아돈다)
    # 🔴 5차에서 c7g.large(2 vCPU) → c7g.2xlarge(8 vCPU). 폴링 목표 25,448 rps 를 재려면
    #    드라이버부터 커야 한다 — 로컬 6코어 천장이 15,500 이었다(그때도 천장은 Platform 이 아니라 CPU).
    # 🔴 **8 vCPU 가 천장이었다**(2026-09-22 실측). 25,448 rps 를 걸면 app 노드는 CPU 29.5%
    #    인데 k6 노드가 96.4% 로 포화해 13,977 rps 밖에 못 냈다. VU 상한(3,665/8,000)도
    #    본문 파싱(discardResponseBodies)도 원인이 아니었다 — 둘 다 바꿔도 수치가 안 움직였고,
    #    VU 를 늘리자 드라이버 일이 늘어 p95 가 97ms → 247ms 로 **더 나빠졌다.**
    # 🪤 이 레포에서 "측정 장비가 천장" 이 세 번째다(로컬 CPU 경쟁 · 도구 언어 한계 · 이번).
    #    수치가 안 움직이면 **대상이 아니라 재는 쪽부터** 의심해라.
    k6 = "c7g.4xlarge" # 부하 드라이버 — 16 vCPU
  }

  # 루트 볼륨. mysql 은 데이터 + 파티션, kafka 는 로그 세그먼트, app 은 Gradle 빌드 + 이미지.
  # obs 는 Prometheus TSDB 가 쓴다. 170분 판 × 7노드 × 15초면 수백 MB 수준이라 20 으로 충분하다.
  disk = { app = 40, app2 = 40, mysql = 40, kafka = 40, redis = 20, worker = 20, k6 = 20, obs = 20 }
}

data "aws_subnet" "this" {
  availability_zone = local.az
  default_for_az    = true
}

data "aws_ssm_parameter" "ubuntu" {
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id"
}

# 집 IP 가 바뀌어도 apply 때마다 따라간다
data "http" "myip" {
  url = "https://checkip.amazonaws.com"
}

resource "aws_key_pair" "this" {
  key_name   = "queue-loadtest"
  public_key = file("~/.ssh/queue-aws.pub")
}

resource "aws_security_group" "this" {
  name   = "queue-loadtest"
  vpc_id = data.aws_subnet.this.vpc_id

  ingress {
    description = "SSH from my machine"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["${chomp(data.http.myip.response_body)}/32"]
  }

  # 🔑 노드 간은 전부 연다. host 네트워크라 Redis 7001-8003 · Kafka 9092-9293 ·
  #    MySQL 3306 · 앱 8080-8084 가 전부 인스턴스 포트로 직접 뜬다.
  #    인터넷에는 하나도 안 열린다 — 열린 것은 위의 SSH 하나뿐이다.
  ingress {
    description = "all traffic within SG"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "node" {
  for_each = local.nodes

  ami                         = data.aws_ssm_parameter.ubuntu.value
  instance_type               = each.value
  subnet_id                   = data.aws_subnet.this.id
  vpc_security_group_ids      = [aws_security_group.this.id]
  key_name                    = aws_key_pair.this.key_name
  associate_public_ip_address = true
  user_data                   = file("${path.module}/user_data_${each.key == "k6" ? "k6" : "docker"}.sh")

  # 🔴 6차부터 온디맨드다. 스팟은 측정 중에 회수당한다 — 5차에서 실제로 k6 노드가
  #    회수됐고(Service initiated), 그때 측정 중이었으면 판을 통째로 버려야 했다.
  #    200만 판은 한 시간 넘게 돈다. use_spot=true 로 되돌릴 수 있게만 남긴다.
  dynamic "instance_market_options" {
    for_each = var.use_spot ? [1] : []
    content {
      market_type = "spot"
      # 정지(stop)가 아니라 종료(terminate). 정지만 해도 EBS 요금이 계속 나간다.
      spot_options { instance_interruption_behavior = "terminate" }
    }
  }

  root_block_device {
    volume_size = local.disk[each.key]
    volume_type = "gp3"
  }

  tags = { Name = "queue-${each.key}" }
}

# ─────────────────────────────────────────────────────────────────────────────
# 🔑 관측 노드에만 **고정 IP**를 붙인다.
#
# 왜: 판마다 terraform apply → destroy 를 반복하면 퍼블릭 IP 가 매번 바뀐다. 그러면
#     Grafana 접속 주소도 매번 바뀌고, **Slack 알람에 박아 둔 대시보드 링크는 영영 맞지 않는다.**
#     EIP 를 붙이면 그 노드 주소가 판을 건너 유지되므로 링크를 한 번 박아 두면 된다.
#
# 🪤 **왜 obs 노드 하나뿐인가**: Prometheus·Grafana·Alertmanager 가 거기 있다(deploy.sh).
#    🔴 2026-09-18 에 mysql → obs 로 **옮겼다.** 관측을 mysql 노드에서 분리했기 때문이다 —
#       EIP 는 "Grafana 가 사는 노드"를 따라간다. 둘을 따로 움직이면 링크가 죽는다.
#    나머지 노드는 SSH 로만 닿고 주소를 사람이 기억할 이유가 없다 — EIP 는 붙인 만큼 돈이 든다.
#
# 🔴 **"destroy 해도 EIP 는 남는다"는 거짓이었다 (2026-09-18 실측).** terraform 이 관리하는
#    리소스라 `terraform destroy` 가 **같이 지운다** — 9차 destroy 후 EIP 가 0개였다.
#    즉 지금 이 리소스가 주는 것은 "판을 건너는 고정 주소"가 아니라 **한 판 안에서의 고정**뿐이다.
#    ⚖️ 판을 건너 고정하려면 `lifecycle { prevent_destroy = true }` 로 바꿔야 하고, 그러면
#       안 쓰는 동안에도 시간당 $0.005 가 계속 나간다. **아직 그렇게 하지 않았다** — Slack 링크가
#       지금은 localhost 터널 주소라, 고정 주소로 얻는 것이 없기 때문이다(GRAFANA_BASE 주입이 선행).

resource "aws_eip" "monitoring" {
  domain = "vpc"
  tags   = { Name = "queue-loadtest-monitoring" }

  # 인스턴스가 사라져도 주소는 유지한다(그게 이걸 쓰는 이유다).
  lifecycle { prevent_destroy = false }
}

resource "aws_eip_association" "monitoring" {
  instance_id   = aws_instance.node["obs"].id
  allocation_id = aws_eip.monitoring.id
}

output "monitoring_ip" {
  description = "Grafana·Prometheus·Alertmanager 가 있는 노드의 고정 주소"
  value       = aws_eip.monitoring.public_ip
}

output "ssh" {
  # 🪤 obs 는 EIP 를 읽는다 — public_ip 출력과 **같은 이유**다. EIP 를 붙이면 자동 할당 공인 IP 가
  #    해제되는데 aws_instance.public_ip 는 그 해제된 주소를 들고 있어, 그대로 쓰면 죽은 주소로
  #    ssh 를 건다. PR #95 에서 public_ip 만 고치고 이 줄을 놓쳤다(2026-09-18 발견).
  value = { for k, i in aws_instance.node :
  k => "ssh -i ~/.ssh/queue-aws ubuntu@${k == "obs" ? aws_eip.monitoring.public_ip : i.public_ip}" }
}

output "public_ip" {
  # 🪤 obs 만 EIP 를 읽는다. EIP 를 붙이면 인스턴스의 자동 할당 공인 IP 가 **해제**되는데
  #    `aws_instance.public_ip` 는 그 해제된 주소를 그대로 들고 있다 → deploy.sh 가 죽은 IP 로
  #    SSH 를 걸어 [0/5] 에서 무한 대기한다(②와 증상이 똑같아 원인을 헷갈린다).
  value = { for k, i in aws_instance.node :
  k => k == "obs" ? aws_eip.monitoring.public_ip : i.public_ip }
}

# 🔑 컨테이너가 서로를 찾는 주소. Redis announce-ip · Kafka advertised.listeners ·
#    앱의 DB_MASTER_HOST 가 전부 이 값이다. 퍼블릭 IP 를 쓰면 트래픽이 밖으로 나갔다 온다.
output "private_ip" {
  value = { for k, i in aws_instance.node : k => i.private_ip }
}
