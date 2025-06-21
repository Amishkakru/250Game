package Model

enum class Suit {
    HEARTS, DIAMONDS, CLUBS, SPADES
}

enum class Rank(val order: Int, val points: Int) {
    FIVE(1, 0), SIX(2, 0), SEVEN(3, 0), EIGHT(4, 0), NINE(5, 0), TEN(6, 0),
    JACK(7, 5), QUEEN(8, 10), KING(9, 15), ACE(10, 20);

    fun getPoints(suit: Suit): Int {
        return if (this == QUEEN && suit == Suit.SPADES) 60 else points
    }
}

data class Card(
    val suit: Suit,
    val rank: Rank,
    val id: String = "${suit}_${rank}"
) {
    val points: Int get() = rank.getPoints(suit)
}

enum class GamePhase {
    WAITING_FOR_PLAYERS, BIDDING, FRIEND_SELECTION, TRICK_PLAYING, GAME_OVER
}

enum class Team {
    CALLER, OPPONENT, UNASSIGNED
}

data class Player(
    val id: String,
    val name: String,
    val sessionId: String,
    var hand: MutableList<Card> = mutableListOf(),
    var team: Team = Team.UNASSIGNED,
    var isCaller: Boolean = false,
    var currentBid: Int? = null,
    var hasPassed: Boolean = false,
    var isConnected: Boolean = true,
    var joinOrder: Int = 0
)

data class BidHistory(
    val playerId: String,
    val bid: Int?, // null represents "pass"
    val timestamp: Long = System.currentTimeMillis()
)

data class TrickPlay(
    val playerId: String,
    val card: Card,
    val timestamp: Long = System.currentTimeMillis()
)

data class Trick(
    val plays: MutableList<TrickPlay> = mutableListOf(),
    var leadPlayerId: String? = null,
    var winnerId: String? = null,
    var isComplete: Boolean = false
) {
    val totalPoints: Int get() = plays.sumOf { it.card.points }
}

data class GameState(
    val gameId: String,
    val players: MutableMap<String, Player> = mutableMapOf(),
    var phase: GamePhase = GamePhase.WAITING_FOR_PLAYERS,
    var currentPlayerIndex: Int = 0,
    var deck: MutableList<Card> = mutableListOf(),
    var bidHistory: MutableList<BidHistory> = mutableListOf(),
    var winningBid: Int = 0,
    var callerId: String? = null,
    var trumpSuit: Suit? = null,
    var friendCards: List<Card> = emptyList(),
    var currentTrick: Trick = Trick(),
    var completedTricks: MutableList<Trick> = mutableListOf(),
    var leadPlayerIndex: Int = 0,
    var callerTeamPoints: Int = 0,
    var opponentTeamPoints: Int = 0,
    var gameWinner: Team? = null,
    var createdAt: Long = System.currentTimeMillis(),
    var lastActivity: Long = System.currentTimeMillis()
) {
    val playersList: List<Player> get() = players.values.sortedBy { it.joinOrder }
    val isGameFull: Boolean get() = players.size == 5
    val currentPlayer: Player? get() = playersList.getOrNull(currentPlayerIndex)
}


data class GameStateDTO(
    val gameId: String,
    var phase: GamePhase,
    val players: List<PlayerDTO>,
    val currentPlayerIndex: Int,
    val bidHistory: List<BidHistory>,
    val winningBid: Int,
    val callerId: String?,
    val trumpSuit: Suit?,
    val friendCards: List<Card>,
    val currentTrick: Trick,
    val completedTricks: Int,
    val callerTeamPoints: Int,
    val opponentTeamPoints: Int,
    val gameWinner: Team?
)

data class PlayerDTO(
    val id: String,
    val name: String,
    val team: Team,
    val isCaller: Boolean,
    val handCount: Int,
    val hasPassed: Boolean,
    val isConnected: Boolean
)
