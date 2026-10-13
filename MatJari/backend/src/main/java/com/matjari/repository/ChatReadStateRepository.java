package com.matjari.repository;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 채팅 읽음 포인터(chat_read_state, V11). (matchId, userId)당 1행이며 값은 단조 증가만 한다.
 * 갱신은 UPDATE ... GREATEST -> (0행이면) INSERT -> (DuplicateKey 면) UPDATE 재시도 순서다. ON DUPLICATE KEY UPDATE 는 쓰지 않는다
 * (INSERT 의도 잠금이 다른 탭의 UPDATE 와 교착할 수 있어서). 시각은 호출자가 Clock 에서 받아 넘긴다.
 * 호출자의 트랜잭션 밖에서(PROPAGATION_NOT_SUPPORTED) 문장마다 자동 커밋으로 실행한다. REPEATABLE READ 에서 행이 없는 UPDATE 는 간격 잠금을 잡아
 * 같은 트랜잭션 안에서 INSERT 를 이어 하는 두 탭이 서로의 간격 잠금을 기다리며 교착하기 때문이다(실제 MySQL 테스트로 확인).
 * 자동 커밋이면 잠금이 문장 직후 풀려 교착이 생기지 않는다. 포인터는 다른 쓰기와 원자적으로 묶을 필요가 없다.
 * <b>주의: advance 는 매칭·users 행을 잠근 트랜잭션 안에서 호출하면 안 된다.</b> NOT_SUPPORTED 로 호출자 트랜잭션을 보류한 채 별도 연결에서
 * FK(match_id, user_id) 검사용 S 잠금을 요구하므로, 보류된 트랜잭션이 쥔 X 잠금을 자기 자신이 기다리다 1205(잠금 대기 초과)가 난다.
 * 지금 호출자(markRead)는 트랜잭션 없이 호출한다.
 * 그래도 교착 희생자(ConcurrencyFailureException)가 나오면 최대 {@value #MAX_ATTEMPTS}번까지 다시 시도한다.
 */
@Repository
public class ChatReadStateRepository {

    private static final String UPDATE = "UPDATE chat_read_state SET last_read_message_id = GREATEST(last_read_message_id, ?), updated_at = ? "
            + "WHERE match_id = ? AND user_id = ?";
    private static final String INSERT = "INSERT INTO chat_read_state (match_id, user_id, last_read_message_id, updated_at) VALUES (?, ?, ?, ?)";

    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate outsideTransaction;

    public ChatReadStateRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.outsideTransaction = new TransactionTemplate(transactionManager);
        this.outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /** 포인터를 lastReadMessageId 이상으로 올린다(내리지 않는다). 호출자가 MAX(id) 절단을 마친 값을 넘긴다. */
    public void advance(Long matchId, Long userId, long lastReadMessageId, LocalDateTime now) {
        for (int attempt = 1; ; attempt++) {
            try {
                outsideTransaction.executeWithoutResult(status -> advanceOnce(matchId, userId, lastReadMessageId, now));
                return;
            } catch (ConcurrencyFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private void advanceOnce(Long matchId, Long userId, long lastReadMessageId, LocalDateTime now) {
        if (jdbc.update(UPDATE, lastReadMessageId, now, matchId, userId) > 0) {
            return;
        }
        try {
            jdbc.update(INSERT, matchId, userId, lastReadMessageId, now);
        } catch (DuplicateKeyException e) {
            // 동시에 다른 탭이 먼저 INSERT 했다: 이제 행이 있으므로 UPDATE 로 재시도
            jdbc.update(UPDATE, lastReadMessageId, now, matchId, userId);
        }
    }

    /** 마지막으로 읽은 메시지 id. 행이 없으면 empty (호출자가 0 으로 취급). */
    public Optional<Long> find(Long matchId, Long userId) {
        List<Long> rows = jdbc.queryForList("SELECT last_read_message_id FROM chat_read_state WHERE match_id = ? AND user_id = ?",
                Long.class, matchId, userId);
        return rows.stream().findFirst();
    }
}
