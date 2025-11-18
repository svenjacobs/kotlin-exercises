package coroutines.test

import coroutines.test.articleservice.RequestAuth
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class CourseService(
    private val userService: UserService,
    private val courseService: CourseProvider,
    private val courseChallengeService: CourseChallengeService,
) {
    suspend fun getUserCourse(requestAuth: RequestAuth, courseId: Long): UserCourse =
        coroutineScope {
            val userId = userService.readUserId(requestAuth)
            val course = async { courseService.getCourse(courseId) }
            val status = async { courseChallengeService.getUserChallengeStatus(userId) }
            val steps = course.await().steps.map { step -> mapCourseStep(step, status.await()) }
            UserCourse(
                courseId = courseId,
                name = course.await().name,
                description = course.await().description,
                steps = steps,
            )
        }

    private fun mapCourseStep(
        step: CodingChallengeConfig,
        userChallengeStatus: Map<String, ChallengeStatus>,
    ): UserCourseStep {
        return UserCourseStep(
            type = CourseStepType.CODING_CHALLENGE,
            key = step.key,
            text = step.title,
            state = when (userChallengeStatus[step.key]) {
                ChallengeStatus.INITIAL -> UserCourseStepState.INITIAL
                ChallengeStatus.SOLVED -> UserCourseStepState.SOLVED
                ChallengeStatus.IN_PROGRESS -> UserCourseStepState.IN_PROGRESS
                ChallengeStatus.IN_REVIEW -> UserCourseStepState.IN_REVIEW
                ChallengeStatus.CHANGES_REQUESTED -> UserCourseStepState.CHANGES_REQUESTED
                null -> UserCourseStepState.INITIAL
            },
        )
    }
}

data class CodingChallengeConfig(val key: String, val title: String)

enum class ChallengeStatus { INITIAL, SOLVED, IN_PROGRESS, IN_REVIEW, CHANGES_REQUESTED }

enum class CourseStepType { CODING_CHALLENGE }

enum class UserCourseStepState { INITIAL, SOLVED, IN_PROGRESS, IN_REVIEW, CHANGES_REQUESTED }

data class UserCourseStep(
    val type: CourseStepType,
    val key: String,
    val text: String,
    val state: UserCourseStepState,
)

data class CourseDetails(
    val name: String,
    val description: String,
    val steps: List<CodingChallengeConfig>,
)

data class UserCourse(
    val courseId: Long,
    val name: String,
    val description: String,
    val steps: List<UserCourseStep>,
)

interface UserService {
    fun readUserId(requestAuth: RequestAuth): Long
}

interface CourseProvider {
    suspend fun getCourse(courseId: Long): CourseDetails
}

interface CourseChallengeService {
    suspend fun getUserChallengeStatus(userId: Long): Map<String, ChallengeStatus>
}

class UserServiceFake : UserService {

    override fun readUserId(requestAuth: RequestAuth): Long {
        return 1L
    }
}

val details = CourseDetails(
    name = "Coroutines Mastery",
    description = "Deep dive and best practices",
    steps = listOf(
        CodingChallengeConfig(
            key = "challenge01",
            title = "First Challenge",
        ),
        CodingChallengeConfig(
            key = "challenge02",
            title = "Second Challenge",
        ),
        CodingChallengeConfig(
            key = "challenge03",
            title = "Third Challenge",
        ),
        CodingChallengeConfig(
            key = "challenge04",
            title = "Fourth Challenge",
        ),
        CodingChallengeConfig(
            key = "challenge05",
            title = "Fifth Challenge",
        ),
    ),
)

class CourseProviderFake : CourseProvider {

    override suspend fun getCourse(courseId: Long): CourseDetails {
        delay(1_000)
        return details
    }
}

val challengeStatus = mapOf(
    "challenge01" to ChallengeStatus.INITIAL,
    "challenge02" to ChallengeStatus.SOLVED,
    "challenge03" to ChallengeStatus.IN_PROGRESS,
    "challenge04" to ChallengeStatus.IN_REVIEW,
    "challenge05" to ChallengeStatus.CHANGES_REQUESTED,
)

class CourseChallengeServiceFake : CourseChallengeService {

    override suspend fun getUserChallengeStatus(userId: Long): Map<String, ChallengeStatus> {
        delay(2_000)
        return challengeStatus
    }
}

class CourseServiceTest {

    val courseService = CourseService(
        userService = UserServiceFake(),
        courseService = CourseProviderFake(),
        courseChallengeService = CourseChallengeServiceFake(),
    )

    @Test
    fun `should provide user course with challenge step status`() = runTest {
        val course = courseService.getUserCourse(RequestAuth("token"), 1L)

        assertEquals(1L, course.courseId)
        assertEquals(details.name, course.name)
        assertEquals(details.description, course.description)
        assertEquals(
            listOf(
                UserCourseStep(
                    type = CourseStepType.CODING_CHALLENGE,
                    key = "challenge01",
                    text = "First Challenge",
                    state = UserCourseStepState.INITIAL,
                ),
                UserCourseStep(
                    type = CourseStepType.CODING_CHALLENGE,
                    key = "challenge02",
                    text = "Second Challenge",
                    state = UserCourseStepState.SOLVED,
                ),
                UserCourseStep(
                    type = CourseStepType.CODING_CHALLENGE,
                    key = "challenge03",
                    text = "Third Challenge",
                    state = UserCourseStepState.IN_PROGRESS,
                ),
                UserCourseStep(
                    type = CourseStepType.CODING_CHALLENGE,
                    key = "challenge04",
                    text = "Fourth Challenge",
                    state = UserCourseStepState.IN_REVIEW,
                ),
                UserCourseStep(
                    type = CourseStepType.CODING_CHALLENGE,
                    key = "challenge05",
                    text = "Fifth Challenge",
                    state = UserCourseStepState.CHANGES_REQUESTED,
                )
            ),
            course.steps,
        )
    }

    @Test
    fun `should call methods concurrently`() = runTest {
        courseService.getUserCourse(RequestAuth("token"), 1L)

        assertEquals(2_000, currentTime, "methods were not called concurrently")
    }
}

