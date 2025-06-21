package Services

import Model.BidHistory
import Model.Card
import Model.GamePhase
import Model.GameState
import Model.Rank
import Model.Suit
import Model.Team
import Model.Trick
import Model.TrickPlay
import org.springframework.stereotype.Service

@Service
class GameLogicService {

    fun createDeck(): MutableList<Card> {
        val deck = mutableListOf<Card>()
        Suit.values().forEach { suit ->
            Rank.values().forEach { rank ->
                deck.add(Card(suit, rank))
            }
        }
        return deck.shuffled().toMutableList()
    }

    fun dealInitialCards(gameState: GameState) {
        val deck = createDeck()
        gameState.deck = deck

        // Deal 4 cards to each player for bidding phase
        gameState.playersList.forEach { player ->
            repeat(4) {
                if (deck.isNotEmpty()) {
                    player.hand.add(deck.removeFirst())
                }
            }
        }
    }

    fun dealRemainingCards(gameState: GameState) {
        // Deal remaining 4 cards to each player after bidding
        gameState.playersList.forEach { player ->
            repeat(4) {
                if (gameState.deck.isNotEmpty()) {
                    player.hand.add(gameState.deck.removeFirst())
                }
            }
        }
    }

    fun getNextBidAmount(currentBid: Int): Int {
        return when {
            currentBid < 150 -> 150
            currentBid < 200 -> currentBid + 5
            currentBid < 250 -> currentBid + 10
            else -> 250
        }
    }

    fun processBid(gameState: GameState, playerId: String, bid: Int?): GameLogicResult {
        val player = gameState.players[playerId]
            ?: return GameLogicResult.Error("Player not found")

        if (gameState.phase != GamePhase.BIDDING) {
            return GameLogicResult.Error("Not in bidding phase")
        }

        if (gameState.currentPlayer?.id != playerId) {
            return GameLogicResult.Error("Not your turn")
        }

        // Validate bid amount
        if (bid != null) {
            val highestBid = gameState.bidHistory.mapNotNull { it.bid }.maxOrNull() ?: 0
            val nextValidBid = getNextBidAmount(highestBid)

            if (bid < nextValidBid || bid > 250) {
                return GameLogicResult.Error("Invalid bid amount")
            }
        }

        // Record bid
        gameState.bidHistory.add(BidHistory(playerId, bid))
        player.currentBid = bid
        if (bid == null) player.hasPassed = true

        // Check if bidding is complete
        val activeBidders = gameState.playersList.filter { !it.hasPassed }
        if (activeBidders.size == 1 || bid == 250) {
            // Bidding complete
            val winner = if (bid == 250) player else activeBidders.first()
            gameState.callerId = winner.id
            gameState.winningBid = bid ?: gameState.bidHistory.mapNotNull { it.bid }.maxOrNull() ?: 150
            winner.isCaller = true
            gameState.phase = GamePhase.FRIEND_SELECTION
            gameState.currentPlayerIndex = gameState.playersList.indexOf(winner)

            // Deal remaining cards
            dealRemainingCards(gameState)

            return GameLogicResult.BiddingComplete(winner.id, gameState.winningBid)
        }

        // Move to next player
        do {
            gameState.currentPlayerIndex = (gameState.currentPlayerIndex + 1) % 5
        } while (gameState.currentPlayer?.hasPassed == true)

        return GameLogicResult.Success
    }

    fun selectFriendCards(gameState: GameState, playerId: String, friendCards: List<Card>): GameLogicResult {
        if (gameState.callerId != playerId) {
            return GameLogicResult.Error("Only caller can select friend cards")
        }

        if (gameState.phase != GamePhase.FRIEND_SELECTION) {
            return GameLogicResult.Error("Not in friend selection phase")
        }

        if (friendCards.size != 2) {
            return GameLogicResult.Error("Must select exactly 2 friend cards")
        }

        val caller = gameState.players[playerId]!!

        // Validate friend cards
        friendCards.forEach { friendCard ->
            if (friendCard.suit == Suit.SPADES && friendCard.rank == Rank.ACE) {
                return GameLogicResult.Error("Cannot select Ace of Spades as friend card")
            }

            if (caller.hand.any { it.id == friendCard.id }) {
                return GameLogicResult.Error("Cannot select cards from your own hand")
            }
        }

        gameState.friendCards = friendCards

        // Assign teams based on friend cards
        assignTeams(gameState)

        gameState.phase = GamePhase.TRICK_PLAYING
        gameState.leadPlayerIndex = gameState.playersList.indexOfFirst { it.isCaller }
        gameState.currentPlayerIndex = gameState.leadPlayerIndex

        return GameLogicResult.Success
    }

    private fun assignTeams(gameState: GameState) {
        gameState.playersList.forEach { player ->
            when {
                player.isCaller -> player.team = Team.CALLER
                player.hand.any { handCard ->
                    gameState.friendCards.any { friendCard ->
                        handCard.suit == friendCard.suit && handCard.rank == friendCard.rank
                    }
                } -> player.team = Team.CALLER
                else -> player.team = Team.OPPONENT
            }
        }
    }

    fun playCard(gameState: GameState, playerId: String, card: Card): GameLogicResult {
        if (gameState.phase != GamePhase.TRICK_PLAYING) {
            return GameLogicResult.Error("Not in trick playing phase")
        }

        if (gameState.currentPlayer?.id != playerId) {
            return GameLogicResult.Error("Not your turn")
        }

        val player = gameState.players[playerId]!!

        if (!player.hand.any { it.id == card.id }) {
            return GameLogicResult.Error("Card not in hand")
        }

        // Validate play according to suit-following rules
        val leadSuit = gameState.currentTrick.plays.firstOrNull()?.card?.suit
        if (leadSuit != null) {
            val canFollowSuit = player.hand.any { it.suit == leadSuit }
            if (canFollowSuit && card.suit != leadSuit) {
                return GameLogicResult.Error("Must follow suit if possible")
            }
        }

        // Remove card from hand and add to trick
        player.hand.removeAll { it.id == card.id }
        gameState.currentTrick.plays.add(TrickPlay(playerId, card))

        if (gameState.currentTrick.plays.isEmpty()) {
            gameState.currentTrick.leadPlayerId = playerId
        }

        // Check if trick is complete
        if (gameState.currentTrick.plays.size == 5) {
            return completeTrick(gameState)
        }

        // Move to next player
        gameState.currentPlayerIndex = (gameState.currentPlayerIndex + 1) % 5

        return GameLogicResult.Success
    }

    private fun completeTrick(gameState: GameState): GameLogicResult {
        val trick = gameState.currentTrick
        val leadSuit = trick.plays.first().card.suit
        val trumpSuit = gameState.trumpSuit

        // Determine winner
        var winner = trick.plays.first()
        var hasTrump = false

        trick.plays.forEach { play ->
            val card = play.card
            when {
                card.suit == trumpSuit && !hasTrump -> {
                    winner = play
                    hasTrump = true
                }
                card.suit == trumpSuit && hasTrump -> {
                    if (card.rank.order > winner.card.rank.order) {
                        winner = play
                    }
                }
                !hasTrump && card.suit == leadSuit -> {
                    if (card.rank.order > winner.card.rank.order) {
                        winner = play
                    }
                }
            }
        }

        trick.winnerId = winner.playerId
        trick.isComplete = true

        // Award points to winning team
        val winnerPlayer = gameState.players[winner.playerId]!!
        val points = trick.totalPoints

        if (winnerPlayer.team == Team.CALLER) {
            gameState.callerTeamPoints += points
        } else {
            gameState.opponentTeamPoints += points
        }

        // Move completed trick to history
        gameState.completedTricks.add(trick)
        gameState.currentTrick = Trick()

        // Set up next trick or end game
        if (gameState.completedTricks.size == 8) {
            // Game complete
            gameState.phase = GamePhase.GAME_OVER
            gameState.gameWinner = if (gameState.callerTeamPoints >= gameState.winningBid) {
                Team.CALLER
            } else {
                Team.OPPONENT
            }
            return GameLogicResult.GameComplete(gameState.gameWinner!!)
        } else {
            // Next trick
            val winnerIndex = gameState.playersList.indexOfFirst { it.id == winner.playerId }
            gameState.leadPlayerIndex = winnerIndex
            gameState.currentPlayerIndex = winnerIndex
            return GameLogicResult.TrickComplete(winner.playerId, points)
        }
    }
}

sealed class GameLogicResult {
    object Success : GameLogicResult()
    data class Error(val message: String) : GameLogicResult()
    data class BiddingComplete(val winnerId: String, val winningBid: Int) : GameLogicResult()
    data class TrickComplete(val winnerId: String, val points: Int) : GameLogicResult()
    data class GameComplete(val winner: Team) : GameLogicResult()
}