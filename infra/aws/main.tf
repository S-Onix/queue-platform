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
locals {
  az = "ap-northeast-2d"

  # 역할 → 사양. 시간당 합계 약 $0.131 (m7g.xlarge $0.0453 ×2 + c7g.large $0.0204 ×2)
  nodes = {
    app    = "m7g.xlarge" # queue-api ×3
    data   = "m7g.xlarge" # mysql + kafka ×3 + redis ×6
    worker = "c7g.large"  # queue-batch + queue-consumer
    k6     = "c7g.large"  # 부하 드라이버
  }

  # 루트 볼륨. data 는 MySQL 데이터 + Kafka 로그, app 은 Gradle 빌드 + 이미지.
  disk = { app = 40, data = 40, worker = 20, k6 = 20 }
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

  instance_market_options {
    market_type = "spot"
    # 정지(stop)가 아니라 종료(terminate). 정지만 해도 EBS 요금이 계속 나간다.
    spot_options { instance_interruption_behavior = "terminate" }
  }

  root_block_device {
    volume_size = local.disk[each.key]
    volume_type = "gp3"
  }

  tags = { Name = "queue-${each.key}" }
}

output "ssh" {
  value = { for k, i in aws_instance.node : k => "ssh -i ~/.ssh/queue-aws ubuntu@${i.public_ip}" }
}

output "public_ip" {
  value = { for k, i in aws_instance.node : k => i.public_ip }
}

# 🔑 컨테이너가 서로를 찾는 주소. Redis announce-ip · Kafka advertised.listeners ·
#    앱의 DB_MASTER_HOST 가 전부 이 값이다. 퍼블릭 IP 를 쓰면 트래픽이 밖으로 나갔다 온다.
output "private_ip" {
  value = { for k, i in aws_instance.node : k => i.private_ip }
}
