# 부하 실측 전용 환경. 세션이 끝나면 terraform destroy 한다.
# SUT와 k6 드라이버는 반드시 같은 AZ에 둔다 — AZ를 넘으면 WAN 지연이 측정값을 삼킨다.

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

# 2026-09-12 spot 최저가 AZ. m7g.2xlarge $0.0951 + c7g.large $0.0204 = 시간당 $0.1155.
# AZ 마다 재고가 달라 값이 갈린다(같은 날 2a 는 m7g 가 1.5배였다).
locals {
  az = "ap-northeast-2d"
}

data "aws_subnet" "this" {
  availability_zone = local.az
  default_for_az    = true
}

data "aws_ssm_parameter" "ubuntu" {
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id"
}

# 집 IP가 바뀌어도 apply 때마다 따라간다
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

  ingress {
    description = "k6 to SUT, all ports within SG"
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

# 🔴 m7g.xlarge(4 vCPU)로 1차를 재고 **무효 판정**했다 (2026-09-12).
#    idle 0~2.4%로 완전 포화 — 컨테이너 합 375%/400%(api 3대 ~250% · mysql 40% · kafka 3대 66%).
#    enqueue p95 가 2.30s 로 로컬(16코어, RF=1)의 33.59ms 대비 70배였고, 실패율은 0%였다.
#    즉 "에러"가 아니라 전부 "느림"이었다. 설계 때 메모리(4.5/15.6GB)만 보고 **CPU를 예산에 안 넣었다**.
#    ⚠️ 이건 앱이 200rps 를 못 버틴다는 뜻이 아니다 — 같은 앱이 로컬 16코어에서는 33.59ms 였다.
#       한 대에 MySQL·Kafka 3대·Redis 6대까지 얹어 **같은 코어를 두고 경쟁**시킨 것이 원인이다.
resource "aws_instance" "sut" {
  ami                         = data.aws_ssm_parameter.ubuntu.value
  instance_type               = "m7g.2xlarge" # 8 vCPU / 32GB
  subnet_id                   = data.aws_subnet.this.id
  vpc_security_group_ids      = [aws_security_group.this.id]
  key_name                    = aws_key_pair.this.key_name
  associate_public_ip_address = true
  user_data                   = file("${path.module}/user_data_sut.sh")

  instance_market_options {
    market_type = "spot"
    spot_options { instance_interruption_behavior = "terminate" }
  }

  root_block_device {
    volume_size = 40 # gradle 빌드 + 컨테이너 이미지 + mysql 데이터
    volume_type = "gp3"
  }

  tags = { Name = "queue-sut" }
}

resource "aws_instance" "k6" {
  ami                         = data.aws_ssm_parameter.ubuntu.value
  instance_type               = "c7g.large"
  subnet_id                   = data.aws_subnet.this.id
  vpc_security_group_ids      = [aws_security_group.this.id]
  key_name                    = aws_key_pair.this.key_name
  associate_public_ip_address = true
  user_data                   = file("${path.module}/user_data_k6.sh")

  instance_market_options {
    market_type = "spot"
    spot_options { instance_interruption_behavior = "terminate" }
  }

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
  }

  tags = { Name = "queue-k6" }
}

output "ssh_sut" { value = "ssh -i ~/.ssh/queue-aws ubuntu@${aws_instance.sut.public_ip}" }
output "ssh_k6"  { value = "ssh -i ~/.ssh/queue-aws ubuntu@${aws_instance.k6.public_ip}" }
output "sut_private_ip" { value = aws_instance.sut.private_ip } # k6가 이걸로 때린다
