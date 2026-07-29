package com.toy.backend.diet

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * `@Async` 작업을 **커밋 뒤에** 시작한다. 트랜잭션 안에서 바로 부르면 비동기 스레드가
 * 아직 커밋되지 않은 행을 조회해 "대상이 없다"로 끝난다. 트랜잭션이 없으면 그냥 실행한다.
 */
fun runAfterCommit(action: () -> Unit) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return action()
    TransactionSynchronizationManager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCommit() = action()
        },
    )
}
