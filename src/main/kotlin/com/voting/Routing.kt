package com.voting

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import java.net.NetworkInterface

const val ADMIN_SECRET = "admin-panel-v9k3m"

/** Set env var VOTING_API_PASSWORD to override the default */
val ADMIN_API_PASSWORD: String = System.getenv("VOTING_API_PASSWORD") ?: "let-them-vote-2024"

@Serializable
data class AdminApiRequest(val password: String)

fun getLanIp(): String =
    NetworkInterface.getNetworkInterfaces()
        ?.asSequence()
        ?.filter { !it.isLoopback && it.isUp }
        ?.flatMap { it.inetAddresses.asSequence() }
        ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress.contains('.') }
        ?.hostAddress ?: "localhost"

fun Application.configureRouting() {
    val lanIp = getLanIp()

    routing {

        get("/health") {
            call.respond(HttpStatusCode.OK)
        }

        // ── Voter page ───────────────────────────────────────────────────────
        get("/") {
            call.respondText(voterHtml(), ContentType.Text.Html)
        }

        // ── Admin UI page (secret URL) ───────────────────────────────────────
        get("/$ADMIN_SECRET") {
            call.respondText(adminHtml(lanIp), ContentType.Text.Html)
        }

        // ── Admin UI API (used by the browser admin page) ────────────────────
        post("/api/admin/start") {
            VotingService.startFreshSession()   // reset + start in one go
            call.respond(HttpStatusCode.OK)
        }
        post("/api/admin/end") {
            VotingService.endVotingSession()
            call.respond(HttpStatusCode.OK)
        }
        post("/api/admin/reset") {
            VotingService.resetSession()
            call.respond(HttpStatusCode.OK)
        }

        // ── External API (password-protected, per Wout's spec) ───────────────
        post("/$ADMIN_SECRET/startVotingSession") {
            val req = call.receive<AdminApiRequest>()
            if (req.password != ADMIN_API_PASSWORD) { call.respond(HttpStatusCode.Unauthorized, "Wrong password"); return@post }
            VotingService.startVotingSession()
            call.respond(HttpStatusCode.OK, "Voting session started")
        }

        post("/$ADMIN_SECRET/endVotingSessionButDontResetVoteCount") {
            val req = call.receive<AdminApiRequest>()
            if (req.password != ADMIN_API_PASSWORD) { call.respond(HttpStatusCode.Unauthorized, "Wrong password"); return@post }
            VotingService.endVotingSession()
            call.respond(HttpStatusCode.OK, "Voting session ended")
        }

        post("/$ADMIN_SECRET/resetVoteCount") {
            val req = call.receive<AdminApiRequest>()
            if (req.password != ADMIN_API_PASSWORD) { call.respond(HttpStatusCode.Unauthorized, "Wrong password"); return@post }
            VotingService.resetVoteCount()
            call.respond(HttpStatusCode.OK, "Vote count reset")
        }

        // ── Voter vote submission ────────────────────────────────────────────
        post("/api/vote") {
            val req = call.receive<VoteRequest>()
            when (VotingService.submitVote(req.voterToken, req.team)) {
                VoteResult.SUCCESS, VoteResult.NO_CHANGE -> call.respond(HttpStatusCode.OK)
                VoteResult.SESSION_NOT_ACTIVE -> call.respond(HttpStatusCode.Conflict, "Session not active")
                VoteResult.INVALID -> call.respond(HttpStatusCode.BadRequest, "Invalid team")
            }
        }

        // ── WebSockets ───────────────────────────────────────────────────────
        webSocket("/ws/voter") {
            val id = VotingService.addVoterSession(this)
            try { for (frame in incoming) {} } finally { VotingService.removeVoterSession(id) }
        }

        webSocket("/ws/admin") {
            val id = VotingService.addAdminSession(this)
            try { for (frame in incoming) {} } finally { VotingService.removeAdminSession(id) }
        }

        /** Public live feed — streams {"number_of_red_votes": N, "number_of_blue_votes": N} */
        webSocket("/ws/live-vote-feed/status") {
            val id = VotingService.addStatusFeedSession(this)
            try { for (frame in incoming) {} } finally { VotingService.removeStatusFeedSession(id) }
        }
    }
}
