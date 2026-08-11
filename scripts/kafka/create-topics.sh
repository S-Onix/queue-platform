#!/usr/bin/env bash
#
# 토큰 생명주기 토픽 생성 (멱등).
#
# 파티션 수와 복제 설정은 나중에 바꾸기 어렵거나 위험한 값이라 여기서 한 번에 고정한다.
#   · 파티션은 줄일 수 없다.
#   · 늘리면 hash(tokenId) % N 이 바뀌어, 그 시점에 살아 있는 토큰의 과거/신규 이벤트가
#     서로 다른 파티션에 놓인다 → 상태 전이 순서 보장이 끊긴다. 토큰 수명이 최대
#     waitingTtl(2시간)이므로 증설은 그만큼 위험 구간을 만든다.
#
# 사용:
#   ./scripts/kafka/create-topics.sh              # 생성 + 검증
#   ./scripts/kafka/create-topics.sh --describe   # 검증만
#
set -euo pipefail

KAFKA_HOME="${KAFKA_HOME:-/home/sonix/kafka_2.13-4.2.1}"
BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092,localhost:9094,localhost:9096}"

TOPIC="${QUEUE_EVENT_TOPIC:-token-lifecycle}"
DLT_TOPIC="${TOPIC}.DLT"

PARTITIONS="${QUEUE_EVENT_PARTITIONS:-18}"
REPLICATION="${QUEUE_EVENT_REPLICATION:-3}"

# outbox의 존재 이유가 유실 방지이므로 이 둘은 협상 대상이 아니다.
#   min.insync.replicas=2 + 프로듀서 acks=all → 복제본 2개가 받아야 성공 처리.
#   1로 두면 acks=all이 사실상 acks=1이 되어 리더 장애 시 유실된다.
MIN_ISR="${QUEUE_EVENT_MIN_ISR:-2}"

# 보관 기간. ⚠️ 장애 지속 시간이 이 값을 넘으면 아직 소비하지 못한 이벤트가 삭제된다.
# 복구가 DB 재구성에 의존하게 되는 지점이므로, 줄일 때는 최대 허용 장애 시간을 먼저 정할 것.
RETENTION_MS="${QUEUE_EVENT_RETENTION_MS:-604800000}"   # 7일

TOPICS_CMD="${KAFKA_HOME}/bin/kafka-topics.sh"

if [[ ! -x "${TOPICS_CMD}" ]]; then
    echo "kafka-topics.sh를 찾을 수 없다: ${TOPICS_CMD}" >&2
    echo "KAFKA_HOME 환경변수로 설치 경로를 지정할 것" >&2
    exit 1
fi

# ──────────────────────────────────────────────────────────────
# 검증
# ──────────────────────────────────────────────────────────────

describe() {
    echo "── ${TOPIC} ────────────────────────────────────────"
    "${TOPICS_CMD}" --bootstrap-server "${BOOTSTRAP}" --describe --topic "${TOPIC}"
    echo
    echo "── ${DLT_TOPIC} ────────────────────────────────────"
    "${TOPICS_CMD}" --bootstrap-server "${BOOTSTRAP}" --describe --topic "${DLT_TOPIC}"
}

topic_exists() {
    "${TOPICS_CMD}" --bootstrap-server "${BOOTSTRAP}" --list | grep -qx "$1"
}

partition_count() {
    "${TOPICS_CMD}" --bootstrap-server "${BOOTSTRAP}" --describe --topic "$1" \
        | awk '/PartitionCount/ { for (i = 1; i < NF; i++) if ($i == "PartitionCount:") print $(i+1) }' \
        | head -1
}

# 이미 있는 토픽의 파티션 수가 다르면 조용히 넘어가면 안 된다.
# --if-not-exists 는 "있으면 그냥 통과"라 설정 불일치를 감춘다. 순서 보장이 파티션 수에
# 걸려 있으므로, 기대와 다르면 반드시 사람이 보고 판단해야 한다.
assert_partitions() {
    local topic="$1" expected="$2" actual
    actual="$(partition_count "${topic}")"
    if [[ "${actual}" != "${expected}" ]]; then
        echo >&2
        echo "⚠️  ${topic} 의 파티션 수가 기대와 다르다: 현재=${actual} 기대=${expected}" >&2
        echo "    파티션은 줄일 수 없고, 늘리면 살아 있는 토큰의 이벤트 순서가 끊긴다." >&2
        echo "    의도한 변경이면 문서(DECISIONS)에 남기고 이 스크립트의 기본값을 함께 고칠 것." >&2
        return 1
    fi
    echo "✓ ${topic}: 파티션 ${actual}개"
}

# ──────────────────────────────────────────────────────────────
# 생성
# ──────────────────────────────────────────────────────────────

create_topic() {
    local topic="$1"
    if topic_exists "${topic}"; then
        echo "· ${topic} 이미 존재 — 생성 건너뜀"
        return 0
    fi

    "${TOPICS_CMD}" --bootstrap-server "${BOOTSTRAP}" \
        --create --if-not-exists \
        --topic "${topic}" \
        --partitions "${PARTITIONS}" \
        --replication-factor "${REPLICATION}" \
        --config "min.insync.replicas=${MIN_ISR}" \
        --config "retention.ms=${RETENTION_MS}"

    echo "· ${topic} 생성 완료"
}

main() {
    if [[ "${1:-}" == "--describe" ]]; then
        describe
        exit 0
    fi

    echo "bootstrap : ${BOOTSTRAP}"
    echo "파티션    : ${PARTITIONS} / 복제 ${REPLICATION} / min.insync ${MIN_ISR}"
    echo

    create_topic "${TOPIC}"

    # DLT는 원본과 파티션 수를 맞춘다. DeadLetterPublishingRecoverer 가 기본적으로
    # 원본과 같은 파티션 번호로 보내기 때문에, DLT가 더 적으면 발행이 실패한다.
    create_topic "${DLT_TOPIC}"

    echo
    assert_partitions "${TOPIC}" "${PARTITIONS}"
    assert_partitions "${DLT_TOPIC}" "${PARTITIONS}"
    echo
    describe
}

main "$@"
