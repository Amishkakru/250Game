package Services

import Model.GameEvent
import Model.GamePhase
import Model.GameState
import Model.Player
import org.springframework.stereotype.Service

@Service
class GameManagerService(
    private val gameLogicService: GameLogicService
) {
    private val games = mutableMapOf<String, GameState>()
    private val playerSessions = mutableMapOf<String, String>() // sessionId -> gameId
    private val eventListeners = mutableListOf<GameEventListener>()

    fun createGame(): String {
        val gameId = generateGameId()
        games[gameId] = GameState(gameId)
        return gameId
    }

    fun joinGame(gameId: String, playerName: String, sessionId: String): GameManagerResult {
        val game = games[gameId] ?: return GameManagerResult.Error("Game not found")

        if (game.isGameFull) {
            return GameManagerResult.Error("Game is full")
        }

        if (game.phase != GamePhase.WAITING_FOR_PLAYERS) {
            return GameManagerResult.Error("Game has already started")
        }

        val playerId = generatePlayerId()
        val player = Player(
            id = playerId,
            name = playerName,
            sessionId = sessionId,
            joinOrder = game.players.size
        )

        game.players[playerId] = player
        playerSessions[sessionId] = gameId
        game.lastActivity = System.currentTimeMillis()

        // Start game if we have 5 players
        if (game.isGameFull) {
            startGame(game)
        }

        return GameManagerResult.PlayerJoined(playerId, game.isGameFull)
    }

    fun leaveGame(sessionId: String): GameManagerResult {
        val gameId = playerSessions[sessionId] ?: return GameManagerResult.Error("Not in a game")
        val game = games[gameId] ?: return GameManagerResult.Error("Game not found")

        val player = game.players.values.find { it.sessionId == sessionId }
            ?: return GameManagerResult.Error("Player not found")

        game.players.remove(player.id)
        playerSessions.remove(sessionId)

        // If game is in progress and player leaves, mark as disconnected instead
        if (game.phase != GamePhase.WAITING_FOR_PLAYERS && game.phase != GamePhase.GAME_OVER) {
            // Handle player disconnection during game
            player.isConnected = false
            game.players[player.id] = player // Keep player in game but mark disconnected
        }

        // Clean up empty games
        if (game.players.isEmpty()) {
            games.remove(gameId)
        }

        return GameManagerResult.PlayerLeft(player.id)
    }

    private fun startGame(game: GameState) {
        game.phase = GamePhase.BIDDING
        game.currentPlayerIndex = 0
        gameLogicService.dealInitialCards(game)

        // Notify listeners that game has started - this will trigger card distribution
        notifyGameEvent(GameEvent.GameStarted(game.gameId))
    }

    fun getGame(gameId: String): GameState? = games[gameId]

    fun getGameBySession(sessionId: String): GameState? {
        val gameId = playerSessions[sessionId] ?: return null
        return games[gameId]
    }

    fun updateLastActivity(gameId: String) {
        games[gameId]?.lastActivity = System.currentTimeMillis()
    }

    // Cleanup inactive games
    fun cleanupInactiveGames() {
        val cutoffTime = System.currentTimeMillis() - 30 * 60 * 1000 // 30 minutes
        games.entries.removeAll { (_, game) ->
            game.lastActivity < cutoffTime
        }
    }

    private fun generateGameId(): String =
        (1..6).map { ('A'..'Z').random() }.joinToString("")

    private fun generatePlayerId(): String =
        java.util.UUID.randomUUID().toString()

    fun notifyGameEvent(event: GameEvent) {
        eventListeners.forEach { listener ->
            listener.onGameEvent(event)
        }
    }

    fun addGameEventListener(listener: GameEventListener) {
        eventListeners.add(listener)
    }

    interface GameEventListener {
        fun onGameEvent(event: GameEvent)
    }
}

sealed class GameManagerResult {
    data class PlayerJoined(val playerId: String, val gameStarted: Boolean) : GameManagerResult()
    data class PlayerLeft(val playerId: String) : GameManagerResult()
    data class Error(val message: String) : GameManagerResult()
}

// class GameEvent(val type: String, val gameId: String, val data: Any? = null)
