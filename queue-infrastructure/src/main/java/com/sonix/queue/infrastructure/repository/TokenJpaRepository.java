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

    /**
     * 이미 완료된 토큰의 {@code completed_at}. {@link #markCompleted}가 0행을 돌려준 뒤에만 쓴다.
     *
     * <p>verify가 완료를 확정하게 되면서 {@code verify → complete}를 둘 다 부르는 정상 Tenant가
     * 0행 경로에 도달한다. 그때 <b>처음 완료된 시각</b>을 그대로 돌려줘야 재시도에도 같은 답이
     * 나온다. 지금 시각을 대신 주면 응답이 거짓이 된다.
     *
     * <p>{@code admit_token}까지 대조하는 이유는 남의 토큰 완료 시각을 읽지 못하게 하기 위해서다.
     */
    @Query(value = """
            SELECT completed_at
              FROM tokens
             WHERE queue_id = :queueId AND tenant_id = :tenantId AND token_id = :tokenId
               AND admit_token = :admitToken
               AND status = 2
            """, nativeQuery = true)
    Optional<LocalDateTime> findCompletedAt(@Param("queueId") String queueId,
                                            @Param("tenantId") long tenantId,
                                            @Param("tokenId") String tokenId,
                                            @Param("admitToken") String admitToken);
}
