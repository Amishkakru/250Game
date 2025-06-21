package Controller

import Model.*
import Services.GameLogicService
import Services.GameManagerService
import Services.GameLogicResult
import Services.GameManagerResult
import jakarta.annotation.PostConstruct
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller

@Controller
class GameWebSocketController(
    private val gameManagerService: GameManagerService,
    private val gameLogicService: GameLogicService,
    private val messagingTemplate: SimpMessagingTemplate
) : GameManagerService.GameEventListener {

    @PostConstruct
    fun init() {
        // Register as event listener when bean is created
        gameManagerService.addGameEventListener(this)
    }

    override fun onGameEvent(event: GameEvent) {
        when (event) {
            is GameEvent.GameStarted -> {
                println("Game started event received for game ${event.gameId}")
                val game = gameManagerService.getGame(event.gameId) ?: return
                println("Sending initial hand updates to all players in game ${event.gameId}")
                // Send hand updates to all players when game starts
                sendHandUpdates(game)
            }
            is GameEvent.HandUpdated -> {
                // Handle other events if needed
                println("Hand updated event received for game ${event.gameId}")
            }
        }
    }

    @MessageMapping("/game/join")
    fun joinGame(message: ClientMessage.JoinGame, session: SimpMessageHeaderAccessor) {
        val sessionId = session.sessionId!!

        when (val result = gameManagerService.joinGame(message.gameId, message.playerName, sessionId)) {
            is GameManagerResult.PlayerJoined -> {
                messagingTemplate.convertAndSendToUser(
                    sessionId,
                    "/queue/game",
                    ServerMessage.GameJoined(message.gameId, result.playerId, message.playerName)
                )

                broadcastToGame(message.gameId, ServerMessage.PlayerJoined(
                    result.playerId, message.playerName,
                    gameManagerService.getGame(message.gameId)?.players?.size ?: 0
                ))

                if (result.gameStarted) {
                    // Game started, send the game state and hand updates
                    broadcastGameState(message.gameId)

                    // Send initial cards to all players
                    val game = gameManagerService.getGame(message.gameId)
                    if (game != null) {
                        println("Game started - sending initial hand updates to all players")
                        sendHandUpdates(game)
                    }
                }
            }
            is GameManagerResult.Error -> {
                messagingTemplate.convertAndSendToUser(
                    sessionId,
                    "/queue/game",
                    ServerMessage.Error(result.message)
                )
            }
            else -> {}
        }
    }

    @MessageMapping("/game/bid")
    fun placeBid(message: ClientMessage.PlaceBid, session: SimpMessageHeaderAccessor) {
        val game = gameManagerService.getGameBySession(session.sessionId!!) ?: return
        val playerId = game.players.values.find { it.sessionId == session.sessionId!! }?.id ?: return

        when (val result = gameLogicService.processBid(game, playerId, message.bid)) {
            is GameLogicResult.Success -> {
                broadcastToGame(game.gameId, ServerMessage.BidPlaced(
                    playerId, message.bid, game.currentPlayer?.id
                ))
                broadcastGameState(game.gameId)
            }
            is GameLogicResult.BiddingComplete -> {
                broadcastToGame(game.gameId, ServerMessage.BidPlaced(
                    playerId, message.bid, null
                ))
                broadcastGameState(game.gameId)
                sendHandUpdates(game)
            }
            is GameLogicResult.Error -> {
                messagingTemplate.convertAndSendToUser(
                    session.sessionId!!,
                    "/queue/game",
                    ServerMessage.Error(result.message)
                )
            }
            else -> {}
        }
    }

    @MessageMapping("/game/trump")
    fun selectTrump(message: ClientMessage.SelectTrump, session: SimpMessageHeaderAccessor) {
        val game = gameManagerService.getGameBySession(session.sessionId!!) ?: return
        val playerId = game.players.values.find { it.sessionId == session.sessionId!! }?.id ?: return

        if (game.callerId == playerId && game.phase == GamePhase.FRIEND_SELECTION) {
            game.trumpSuit = message.trumpSuit
            broadcastGameState(game.gameId)
        }
    }

    @MessageMapping("/game/friends")
    fun selectFriendCards(message: ClientMessage.SelectFriendCards, session: SimpMessageHeaderAccessor) {
        val game = gameManagerService.getGameBySession(session.sessionId!!) ?: return
        val playerId = game.players.values.find { it.sessionId == session.sessionId!! }?.id ?: return

        when (val result = gameLogicService.selectFriendCards(game, playerId, message.friendCards)) {
            is GameLogicResult.Success -> {
                broadcastGameState(game.gameId)
            }
            is GameLogicResult.Error -> {
                messagingTemplate.convertAndSendToUser(
                    session.sessionId!!,
                    "/queue/game",
                    ServerMessage.Error(result.message)
                )
            }
            else -> {}
        }
    }

    @MessageMapping("/game/play")
    fun playCard(message: ClientMessage.PlayCard, session: SimpMessageHeaderAccessor) {
        val game = gameManagerService.getGameBySession(session.sessionId!!) ?: return
        val playerId = game.players.values.find { it.sessionId == session.sessionId!! }?.id ?: return

        when (val result = gameLogicService.playCard(game, playerId, message.card)) {
            is GameLogicResult.Success -> {
                broadcastGameState(game.gameId)
                sendHandUpdates(game)
            }
            is GameLogicResult.TrickComplete -> {
                broadcastToGame(game.gameId, ServerMessage.TrickComplete(
                    result.winnerId, result.points, game.currentPlayer?.id ?: ""
                ))
                broadcastGameState(game.gameId)
                sendHandUpdates(game)
            }
            is GameLogicResult.GameComplete -> {
                broadcastToGame(game.gameId, ServerMessage.GameComplete(
                    result.winner, game.callerTeamPoints, game.opponentTeamPoints
                ))
                broadcastGameState(game.gameId)
            }
            is GameLogicResult.Error -> {
                messagingTemplate.convertAndSendToUser(
                    session.sessionId!!,
                    "/queue/game",
                    ServerMessage.Error(result.message)
                )
            }
            else -> {}
        }
    }

    @MessageMapping("/game/hand")
    fun requestHand(message: ClientMessage.RequestHand, session: SimpMessageHeaderAccessor) {
        // Get game using session ID instead of game ID for more reliable lookup
        val sessionId = session.sessionId!!
        val game = gameManagerService.getGameBySession(sessionId) ?: return
        val player = game.players.values.find { it.sessionId == sessionId }

        if (player != null) {
            println("Sending hand update to player ${player.name} (${player.id}). Cards: ${player.hand.size}")

            // Try alternative approach - broadcast to all clients with player ID in the message
            // Each client can filter out the message if it's not for them
            broadcastToGame(game.gameId, ServerMessage.HandUpdate(player.hand, player.id))

            // Also try the standard approach
            try {
                messagingTemplate.convertAndSendToUser(
                    sessionId,
                    "/queue/hand",
                    ServerMessage.HandUpdate(player.hand)
                )
            } catch (e: Exception) {
                println("Error in convertAndSendToUser: ${e.message}")
            }
        } else {
            println("Player not found for session $sessionId")
            messagingTemplate.convertAndSendToUser(
                sessionId,
                "/queue/game",
                ServerMessage.Error("Unable to retrieve your cards. Please try reconnecting.")
            )
        }
    }

    private fun broadcastToGame(gameId: String, message: ServerMessage) {
        messagingTemplate.convertAndSend("/topic/game/$gameId", message)
    }

    private fun broadcastGameState(gameId: String) {
        val game = gameManagerService.getGame(gameId) ?: return
        val gameStateDTO = game.toDTO()
        broadcastToGame(gameId, ServerMessage.GameStateUpdate(gameStateDTO))
    }

    private fun sendHandUpdates(game: GameState) {
        game.players.values.forEach { player ->
            try {
                // Log the attempt to send cards
                println("Attempting to send ${player.hand.size} cards to player ${player.name} (${player.id}) with sessionId ${player.sessionId}")

                // Broadcast to game topic with player ID to allow filtering
                broadcastToGame(game.gameId, ServerMessage.HandUpdate(player.hand, player.id))
                println("Broadcast hand update to game topic with playerId: ${player.id}")

                // Also try direct messaging as a fallback
                try {
                    messagingTemplate.convertAndSendToUser(
                        player.sessionId,
                        "/queue/hand",
                        ServerMessage.HandUpdate(player.hand)
                    )
                    println("Sent direct hand update to player ${player.name} via user queue")
                } catch (e: Exception) {
                    println("Error sending direct hand update: ${e.message}")
                }
            } catch (e: Exception) {
                // Log any errors that occur during message sending
                println("Error sending hand update to player ${player.name}: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

// Extension function to convert GameState to DTO
fun GameState.toDTO(): GameStateDTO {
    return GameStateDTO(
        gameId = gameId,
        phase = phase,
        players = playersList.map { player ->
            PlayerDTO(
                id = player.id,
                name = player.name,
                team = player.team,
                isCaller = player.isCaller,
                handCount = player.hand.size,
                hasPassed = player.hasPassed,
                isConnected = player.isConnected
            )
        },
        currentPlayerIndex = currentPlayerIndex,
        bidHistory = bidHistory,
        winningBid = winningBid,
        callerId = callerId,
        trumpSuit = trumpSuit,
        friendCards = friendCards,
        currentTrick = currentTrick,
        completedTricks = completedTricks.size,
        callerTeamPoints = callerTeamPoints,
        opponentTeamPoints = opponentTeamPoints,
        gameWinner = gameWinner
    )
}