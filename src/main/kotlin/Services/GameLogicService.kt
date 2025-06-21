package Services

import Model.BidHistory
import Model.Card
import Model.GamePhase
import Model.GameState
import Model.Player
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
        gameState.players.values.forEach { player ->
            repeat(4) {
                if (deck.isNotEmpty()) {
                    player.hand.add(deck.removeFirst())
                }
            }
        }
    }

    fun dealRemainingCards(gameState: GameState) {
        // Deal remaining 4 cards to each player after bidding
        val remainingCardsByPlayer = gameState.players.keys.associateWith { playerId ->
            val player = gameState.players[playerId]!!
            val cardsNeeded = 8 - player.hand.size
            val cards = mutableListOf<Card>()

            repeat(cardsNeeded) {
                if (gameState.deck.isNotEmpty()) {
                    cards.add(gameState.deck.removeFirst())
                }
            }

            cards
        }

        // Add cards to each player's hand all at once
        remainingCardsByPlayer.forEach { (playerId, cards) ->
            val player = gameState.players[playerId]!!
            player.hand.addAll(cards)
            player.hand.sortWith(compareBy({ it.suit }, { it.rank.order }))
        }

        println("All players now have ${gameState.players.values.firstOrNull()?.hand?.size ?: 0} cards")
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
        if (player == null || gameState.currentPlayer?.id != playerId || gameState.phase != GamePhase.BIDDING) {
            return GameLogicResult.Error("Not your turn to bid or not in bidding phase.")
        }

        if (bid != null) {
            val minBid = if (gameState.winningBid == 0) 150 else getNextBidAmount(gameState.winningBid)
            if (bid < minBid || bid > 250) {
                return GameLogicResult.Error("Invalid bid amount. Must be between $minBid and 250.")
            }
            if (bid % 5 != 0 && bid < 200) {
                return GameLogicResult.Error("Bid must be a multiple of 5.")
            }
            if (bid % 10 != 0 && bid >= 200) {
                return GameLogicResult.Error("Bid must be a multiple of 10 from 200.")
            }
            gameState.winningBid = bid
            gameState.callerId = playerId
            player.currentBid = bid
            player.hasPassed = false
        } else {
            player.hasPassed = true
        }

        gameState.bidHistory.add(BidHistory(playerId, bid))

        val activeBidders = gameState.players.values.filter { !it.hasPassed }
        if (activeBidders.size <= 1 && gameState.callerId != null) {
            // Bidding is complete
            val caller = gameState.players[gameState.callerId]!!
            caller.isCaller = true
            caller.team = Team.CALLER
            gameState.phase = GamePhase.FRIEND_SELECTION
            gameState.currentPlayerIndex = gameState.playersList.indexOf(caller)

            // Assign teams to other players
            gameState.players.values.forEach { p ->
                if (!p.isCaller) {
                    p.team = Team.OPPONENT
                }
            }

            return GameLogicResult.BiddingComplete
        }

        // Move to the next player
        do {
            gameState.currentPlayerIndex = (gameState.currentPlayerIndex + 1) % gameState.players.size
        } while (gameState.currentPlayer?.hasPassed == true)

        return GameLogicResult.Success
    }

    fun selectTrumpSuit(gameState: GameState, playerId: String, suit: Suit): GameLogicResult {
        if (gameState.callerId != playerId || gameState.phase != GamePhase.FRIEND_SELECTION) {
            return GameLogicResult.Error("Not allowed to select trump suit.")
        }

        gameState.trumpSuit = suit
        return GameLogicResult.Success
    }

    fun selectFriendCards(gameState: GameState, playerId: String, friendCards: List<Card>): GameLogicResult {
        if (gameState.callerId != playerId || gameState.phase != GamePhase.FRIEND_SELECTION) {
            return GameLogicResult.Error("Not allowed to select friend cards.")
        }
        if (friendCards.size !in 1..2) {
            return GameLogicResult.Error("You must select 1 or 2 friend cards.")
        }
        if (gameState.trumpSuit == null) {
            return GameLogicResult.Error("Please select a trump suit first.")
        }

        // Store friend cards
        gameState.friendCards = friendCards.map {
            Card(it.suit, it.rank)
        }

        // Deal remaining cards and ensure the game state is updated atomically
        dealRemainingCards(gameState)

        // Set the current player to the caller for the first trick
        val callerIndex = gameState.playersList.indexOfFirst { it.id == gameState.callerId }
        if (callerIndex != -1) {
            gameState.currentPlayerIndex = callerIndex
        }

        // Change phase last, after all other preparations are complete
        gameState.phase = GamePhase.TRICK_PLAYING

        println("Friend cards selected: ${gameState.friendCards}")
        println("Game phase changed to: ${gameState.phase}")
        println("Current player is now: ${gameState.currentPlayer?.name}")

        return GameLogicResult.Success
    }

    fun playCard(gameState: GameState, playerId: String, card: Card): GameLogicResult {
        val player = gameState.players[playerId]
        if (player == null || gameState.currentPlayer?.id != playerId || gameState.phase != GamePhase.TRICK_PLAYING) {
            return GameLogicResult.Error("Not your turn or not in trick playing phase.")
        }

        println("Player ${player.name} attempting to play ${card.suit} ${card.rank}. Hand size: ${player.hand.size}")
        println("Available cards: ${player.hand.map { "${it.suit} ${it.rank}" }}")

        // Find the matching card in player's hand (avoid object reference issues)
        val cardToPlay = player.hand.find { it.suit == card.suit && it.rank == card.rank }
            ?: return GameLogicResult.Error("You don't have that card. Hand: ${player.hand.map { "${it.suit} ${it.rank}" }}")

        val currentTrick = gameState.currentTrick
        if (currentTrick.plays.isEmpty()) {
            currentTrick.leadPlayerId = playerId
        } else {
            val leadCard = currentTrick.plays.first().card
            val leadSuit = leadCard.suit
            if (cardToPlay.suit != leadSuit && player.hand.any { it.suit == leadSuit }) {
                return GameLogicResult.Error("You must follow suit.")
            }
        }

        player.hand.remove(cardToPlay)
        currentTrick.plays.add(TrickPlay(playerId, cardToPlay))

        // Check if the played card is a friend card
        if (gameState.friendCards.any { it.suit == cardToPlay.suit && it.rank == cardToPlay.rank } && player.id != gameState.callerId) {
            val oldTeam = player.team
            player.team = Team.CALLER

            // Announce the friend has been revealed
            println("Player ${player.name} has been revealed as a friend!")

            // Recalculate points if player's team has changed
            if (oldTeam == Team.OPPONENT) {
                recalculateTeamPoints(gameState)
            }
        }

        if (currentTrick.plays.size == gameState.players.size) {
            // Trick is complete
            val trumpSuit = gameState.trumpSuit ?:
                return GameLogicResult.Error("Trump suit is not set")

            val trickWinner = determineTrickWinner(currentTrick, trumpSuit)
            val winnerPlayer = gameState.players[trickWinner.playerId]!!
            currentTrick.winnerId = trickWinner.playerId
            currentTrick.isComplete = true

            val trickPoints = currentTrick.plays.sumOf { it.card.points }
            if (winnerPlayer.team == Team.CALLER) {
                gameState.callerTeamPoints += trickPoints
            } else {
                gameState.opponentTeamPoints += trickPoints
            }

            gameState.completedTricks.add(currentTrick)
            gameState.currentTrick = Trick()
            gameState.currentPlayerIndex = gameState.playersList.indexOf(winnerPlayer)

            if (gameState.completedTricks.size == 8) {
                // Game is over
                gameState.phase = GamePhase.GAME_OVER
                determineGameWinner(gameState)
                return GameLogicResult.GameComplete(gameState.gameWinner!!)
            } else {
                return GameLogicResult.TrickComplete(winnerPlayer.id, trickPoints)
            }
        } else {
            gameState.currentPlayerIndex = (gameState.currentPlayerIndex + 1) % gameState.players.size
        }

        return GameLogicResult.Success
    }

    /**
     * Recalculates team points based on the current team assignments and completed tricks.
     * This is necessary when a player changes teams during the game.
     */
    private fun recalculateTeamPoints(gameState: GameState) {
        // Reset points
        gameState.callerTeamPoints = 0
        gameState.opponentTeamPoints = 0

        // Recalculate all completed tricks
        for (trick in gameState.completedTricks) {
            if (trick.winnerId != null) {
                val winnerPlayer = gameState.players[trick.winnerId]
                val trickPoints = trick.plays.sumOf { it.card.points }

                // Assign points based on current team membership
                if (winnerPlayer?.team == Team.CALLER) {
                    gameState.callerTeamPoints += trickPoints
                } else if (winnerPlayer?.team == Team.OPPONENT) {
                    gameState.opponentTeamPoints += trickPoints
                }
            }
        }
    }

    private fun determineTrickWinner(trick: Trick, trumpSuit: Suit): TrickPlay {
        if (trick.plays.isEmpty()) {
            throw IllegalArgumentException("Cannot determine winner of empty trick")
        }

        val leadPlay = trick.plays.first()
        val leadSuit = leadPlay.card.suit

        var winner = leadPlay
        var bestRank = leadPlay.card.rank.order
        var isTrumpPlayed = leadPlay.card.suit == trumpSuit

        for (i in 1 until trick.plays.size) {
            val play = trick.plays[i]
            val card = play.card

            if (card.suit == trumpSuit) {
                // This is a trump card
                if (!isTrumpPlayed || card.rank.order > bestRank) {
                    winner = play
                    bestRank = card.rank.order
                    isTrumpPlayed = true
                }
            } else if (card.suit == leadSuit && !isTrumpPlayed) {
                // Same suit as lead and no trump played yet
                if (card.rank.order > bestRank) {
                    winner = play
                    bestRank = card.rank.order
                }
            }
            // Cards of other suits can never win
        }

        return winner
    }

    private fun determineGameWinner(gameState: GameState) {
        gameState.gameWinner = if (gameState.callerTeamPoints >= gameState.winningBid) {
            Team.CALLER
        } else {
            Team.OPPONENT
        }
    }
}

sealed class GameLogicResult {
    object Success : GameLogicResult()
    data class Error(val message: String) : GameLogicResult()
    object BiddingComplete : GameLogicResult()
    data class TrickComplete(val winnerId: String, val points: Int) : GameLogicResult()
    data class GameComplete(val winner: Team) : GameLogicResult()
}