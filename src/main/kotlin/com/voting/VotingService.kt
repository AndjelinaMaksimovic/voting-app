package com.voting

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SessionState { WAITING, VOTING, ENDED }
enum class VoteResult { SUCCESS, NO_CHANGE, SESSION_NOT_ACTIVE, INVALID }

@Serializable
data class VoterMessage(
    val type: String,
    val state: String? = null,
    val sessionId: String? = null
)

@Serializable
data class AdminMessage(
    val type: String,
    val state: String? = null,
    val blueVotes: Int? = null,
    val redVotes: Int? = null,
    val winner: String? = null
)

/** Streamed on the public status WebSocket feed */
@Serializable
data class StatusFeedMessage(
    val number_of_red_votes: Int,
    val number_of_blue_votes: Int
)

@Serializable
data class VoteRequest(val team: String, val voterToken: String)

object VotingService {
    private val mutex = Mutex()

    private var sessionState: SessionState = SessionState.WAITING
    private var blueVotes: Int = 0
    private var redVotes: Int = 0
    private var sessionId: String = UUID.randomUUID().toString()

    /** Tracks each voter's current choice for this session: voterToken -> "red"/"blue" */
    private val voterVotes = ConcurrentHashMap<String, String>()

    private val voterSessions      = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    private val adminSessions      = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    private val statusFeedSessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    // ── WebSocket connection management ─────────────────────────────────────

    suspend fun addVoterSession(session: DefaultWebSocketServerSession): String {
        val id = UUID.randomUUID().toString()
        voterSessions[id] = session
        val (state, sid) = mutex.withLock { sessionState to sessionId }
        session.send(Frame.Text(Json.encodeToString(VoterMessage(type = "state", state = state.name, sessionId = sid))))
        return id
    }
    fun removeVoterSession(id: String) = voterSessions.remove(id)

    suspend fun addAdminSession(session: DefaultWebSocketServerSession): String {
        val id = UUID.randomUUID().toString()
        adminSessions[id] = session
        val (state, blue, red) = mutex.withLock { Triple(sessionState, blueVotes, redVotes) }
        session.send(Frame.Text(Json.encodeToString(AdminMessage(type = "init", state = state.name, blueVotes = blue, redVotes = red))))
        return id
    }
    fun removeAdminSession(id: String) = adminSessions.remove(id)

    suspend fun addStatusFeedSession(session: DefaultWebSocketServerSession): String {
        val id = UUID.randomUUID().toString()
        statusFeedSessions[id] = session
        val (blue, red) = mutex.withLock { blueVotes to redVotes }
        session.send(Frame.Text(Json.encodeToString(StatusFeedMessage(red, blue))))
        return id
    }
    fun removeStatusFeedSession(id: String) = statusFeedSessions.remove(id)

    // ── Session control ──────────────────────────────────────────────────────

    /**
     * Admin UI "Begin Voting Session" — clean fresh start (resets votes + starts).
     */
    suspend fun startFreshSession() {
        val newId = UUID.randomUUID().toString()
        mutex.withLock {
            sessionState = SessionState.VOTING
            blueVotes = 0
            redVotes = 0
            sessionId = newId
            voterVotes.clear()
        }
        broadcastToVoters(VoterMessage(type = "state", state = "VOTING", sessionId = newId))
        broadcastToAdmin(AdminMessage(type = "started", state = "VOTING", blueVotes = 0, redVotes = 0))
        broadcastStatusFeed(0, 0)
    }

    /**
     * External API — starts session WITHOUT resetting votes.
     */
    suspend fun startVotingSession() {
        val newId = UUID.randomUUID().toString()
        val (blue, red) = mutex.withLock {
            sessionState = SessionState.VOTING
            sessionId = newId
            blueVotes to redVotes
        }
        broadcastToVoters(VoterMessage(type = "state", state = "VOTING", sessionId = newId))
        broadcastToAdmin(AdminMessage(type = "started", state = "VOTING", blueVotes = blue, redVotes = red))
        broadcastStatusFeed(blue, red)
    }

    /**
     * External API — ends session WITHOUT resetting votes.
     */
    suspend fun endVotingSession() {
        var alreadyEnded = false
        val (blue, red) = mutex.withLock {
            if (sessionState == SessionState.ENDED) { alreadyEnded = true }
            sessionState = SessionState.ENDED
            blueVotes to redVotes
        }
        if (alreadyEnded) return
        val winner = when {
            blue > red -> "BLUE"
            red > blue -> "RED"
            else -> "TIE"
        }
        broadcastToVoters(VoterMessage(type = "state", state = "ENDED"))
        broadcastToAdmin(AdminMessage(type = "ended", state = "ENDED", blueVotes = blue, redVotes = red, winner = winner))
    }

    /**
     * External API — resets vote counts only, does not change session state.
     */
    suspend fun resetVoteCount() {
        mutex.withLock {
            blueVotes = 0
            redVotes = 0
            voterVotes.clear()
        }
        broadcastToAdmin(AdminMessage(type = "votes", blueVotes = 0, redVotes = 0))
        broadcastStatusFeed(0, 0)
    }

    /**
     * Admin UI "Back to New Session" — full reset back to WAITING.
     */
    suspend fun resetSession() {
        val newId = UUID.randomUUID().toString()
        mutex.withLock {
            sessionState = SessionState.WAITING
            blueVotes = 0
            redVotes = 0
            sessionId = newId
            voterVotes.clear()
        }
        broadcastToVoters(VoterMessage(type = "state", state = "WAITING", sessionId = newId))
        broadcastToAdmin(AdminMessage(type = "reset", state = "WAITING", blueVotes = 0, redVotes = 0))
        broadcastStatusFeed(0, 0)
    }

    // ── Voting ───────────────────────────────────────────────────────────────

    /**
     * Submit or change a vote. The same voterToken can re-vote to change their mind;
     * the old vote is swapped out automatically.
     */
    suspend fun submitVote(voterToken: String, team: String): VoteResult {
        var blue = 0
        var red = 0
        var result = VoteResult.SUCCESS

        mutex.withLock {
            if (sessionState != SessionState.VOTING) { result = VoteResult.SESSION_NOT_ACTIVE; return@withLock }
            if (team != "blue" && team != "red")      { result = VoteResult.INVALID;            return@withLock }

            val previousVote = voterVotes[voterToken]
            if (previousVote == team) { result = VoteResult.NO_CHANGE; return@withLock }

            // Remove previous vote if any
            previousVote?.let { if (it == "blue") blueVotes-- else redVotes-- }

            // Record new vote
            if (team == "blue") blueVotes++ else redVotes++
            voterVotes[voterToken] = team

            blue = blueVotes
            red  = redVotes
        }

        if (result == VoteResult.SUCCESS) {
            broadcastToAdmin(AdminMessage(type = "votes", blueVotes = blue, redVotes = red))
            broadcastStatusFeed(blue, red)
        }
        return result
    }

    // ── Broadcast helpers ────────────────────────────────────────────────────

    private suspend fun broadcastToVoters(msg: VoterMessage) {
        val json = Json.encodeToString(msg)
        voterSessions.values.toList().forEach { try { it.send(Frame.Text(json)) } catch (_: Exception) {} }
    }

    private suspend fun broadcastToAdmin(msg: AdminMessage) {
        val json = Json.encodeToString(msg)
        adminSessions.values.toList().forEach { try { it.send(Frame.Text(json)) } catch (_: Exception) {} }
    }

    private suspend fun broadcastStatusFeed(blue: Int, red: Int) {
        val json = Json.encodeToString(StatusFeedMessage(red, blue))
        statusFeedSessions.values.toList().forEach { try { it.send(Frame.Text(json)) } catch (_: Exception) {} }
    }
}
