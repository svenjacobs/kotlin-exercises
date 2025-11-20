package effective.safe.userrefresher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

class UserRefresher(
    private val scope: CoroutineScope,
    private val refreshData: suspend (Int) -> Unit,
) {
    private var refreshJob: Job? = null
    private val userIds = Channel<Int>(capacity = Channel.UNLIMITED)
    private val mutex = Mutex()

    init {
        scope.launch {
            for (userId in userIds) {
                refreshData(userId)
            }
        }
    }

    suspend fun refresh(userId: Int) {
        userIds.send(userId)
    }

    suspend fun refreshWithMutex(userId: Int) {
        refreshJob?.join()
        refreshJob = scope.launch {
            mutex.withLock {
                refreshData(userId)
            }
        }
    }

    /**
     * I believe this would also be a valid and concise solution as the mutex ensures that only
     * one coroutine can run this suspending function. However, this function fails with the tests
     * because the condition
     *
     * assert(currentTime <= 1000)
     *
     * is not true anymore. currenTime is already at 1.000.000 after refreshWithMutex2() has run
     * 1000 times.
     */
    suspend fun refreshWithMutex2(userId: Int) {
        mutex.withLock {
            refreshData(userId)
        }
    }
}

class UserRefresherTest {
    @Test
    fun `should finish all refreshes`(): Unit = runTest {
        val refreshed = ConcurrentHashMap.newKeySet<Int>()
        val finished = AtomicInteger(0)
        val userRefresher = UserRefresher(
            scope = backgroundScope,
            refreshData = { userId ->
                refreshed += userId
                finished.incrementAndGet()
            }
        )

        coroutineScope {
            repeat(1000) {
                launch { userRefresher.refresh(it) }
            }
        }
        await { finished.get() >= 1000 }
        assertEquals(1000, refreshed.size)
    }

    @Test
    fun `should not start more than one refresh job`(): Unit = runTest {
        val finished = AtomicInteger(0)
        val userRefresher = UserRefresher(
            scope = backgroundScope,
            refreshData = { userId ->
                delay(1000)
                finished.incrementAndGet()
            }
        )

        coroutineScope {
            repeat(1000) {
                launch { userRefresher.refresh(it) }
            }
        }
        assert(currentTime <= 1000)
        await { finished.get() >= 1000 }
        assertEquals(1000 * 1000, currentTime)
    }

    @Test
    fun `should not start more than one refresh job (on real time)`(): Unit =
        runBlocking(Dispatchers.Default) {
            val finished = AtomicInteger(0)
            val backgroundScope = CoroutineScope(Job())
            val userRefresher = UserRefresher(
                scope = backgroundScope,
                refreshData = { userId ->
                    delay(10)
                    finished.incrementAndGet()
                }
            )

            val sendTime = measureTime {
                coroutineScope {
                    repeat(100) {
                        launch { userRefresher.refresh(it) }
                    }
                }
            }
            val executionTime = measureTime {
                await { finished.get() >= 100 }
            }
            assertEquals(0, sendTime.inWholeSeconds)
            assert(1 >= executionTime.inWholeSeconds)
            backgroundScope.cancel()
        }
}

suspend fun await(condition: () -> Boolean) {
    while (!condition()) {
        delay(1)
    }
}
