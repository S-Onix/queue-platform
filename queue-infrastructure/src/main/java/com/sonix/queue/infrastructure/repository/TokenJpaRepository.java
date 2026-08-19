package com.sonix.queue.infrastructure.repository;

import com.sonix.queue.infrastructure.entity.TokenEntity;
import com.sonix.queue.infrastructure.entity.TokenEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * ⚠️ 아래 세 쿼리는 <b>native</b>다. {@code UTC_TIMESTAMP(3)}가 MySQL 함수라 JPQL로 쓸 수 없다.
 *
 * <p>왜 {@code NOW()}가 아닌가: 앱의 JDBC 세션은 {@code time_zone=+00:00}이라 NOW()도 UTC지만,
 * 서버 {@code default-time-zone}은 아직 {@code +09:00}이라 mysql CLI로 같은 쿼리를 돌리면
 * NOW()가 KST다. {@code UTC_TIMESTAMP(3)}는 어느 경로에서도 같은 값이다 (FRS §6.5 · §77).
 */
public interface TokenJpaRepository extends JpaRepository<TokenEntity, TokenEntityId> {

    /**
     * 신원 조회. 상태·시각 술어 없음 — 근거는 {@code TokenRepository.findByTokenId} 참조.
     *
     * <p>{@code LIMIT 1}: {@code token_id}는 {@code UNIQUE(token_id, issued_at)}의 앞자리라
     * 단독으로는 유일이 보장되지 않는다(실제로는 UUID라 유일하다). 스키마가 못 막는 것을
     * 쿼리로 막아 {@code NonUniqueResultException}이 안 나게 한다.
     */
    @Query(value = """
            SELECT * FROM tokens
             WHERE queue_id = :queueId AND tenant_id = :tenantId AND token_id = :tokenId
             LIMIT 1
            """, nativeQuery = true)
    Optional<TokenEntity> findOneByTokenId(@Param("queueId") String queueId,
                                           @Param("tenantId") long tenantId,
                                           @Param("tokenId") String tokenId);

    /** verify DB fallback (FRS §6.5). 기준 컬럼은 {@code admitted_at}이다. */
    @Query(value = """
            SELECT * FROM tokens
             WHERE queue_id = :queueId AND tenant_id = :tenantId
               AND admit_token = :admitToken
               AND status = 1
               AND admitted_at > UTC_TIMESTAMP(3) - INTERVAL :freshSeconds SECOND
             LIMIT 1
            """, nativeQuery = true)
    Optional<TokenEntity> findAdmittedByAdmitToken(@Param("queueId") String queueId,
                                                   @Param("tenantId") long tenantId,
                                                   @Param("admitToken") String admitToken,
                                                   @Param("freshSeconds") int freshSeconds);

    /**
     * complete의 상태 전이 (FRS §6.6). 탐색 키는 {@code token_id}, {@code admit_token}은 자격 술어.
     *
     * <p>이 UPDATE 한 문장이 동시 complete의 유일한 조정 수단이다 — 두 요청이 겹치면
     * 뒤쪽은 {@code status IN (0,1)}에 걸려 0행을 받는다(락 불필요).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE tokens
               SET status = 2, completed_at = :completedAt
             WHERE queue_id = :queueId AND tenant_id = :tenantId AND token_id = :tokenId
               AND admit_token = :admitToken
               AND status IN (0, 1)
               AND admitted_at > UTC_TIMESTAMP(3) - INTERVAL :validWindowSeconds SECOND
            """, nativeQuery = true)
    int markCompleted(@Param("queueId") String queueId,
                      @Param("tenantId") long tenantId,
                      @Param("tokenId") String tokenId,
                      @Param("admitToken") String admitToken,
                      @Param("completedAt") LocalDateTime completedAt,
                      @Param("validWindowSeconds") int validWindowSeconds);
}
