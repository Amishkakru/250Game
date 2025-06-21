package _game.try1

import Model.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.client.postForEntity
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import java.io.File
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import org.springframework.web.socket.sockjs.client.SockJsClient
import org.springframework.web.socket.sockjs.client.Transport
import org.springframework.web.socket.sockjs.client.WebSocketTransport
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * This test runs a complete game flow and generates a detailed report.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GameSystemTest {

    private val logger = LoggerFactory.getLogger(GameSystemTest::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val testReport = StringBuilder()

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private lateinit var wsUrl: String

    @Test
    fun testCompleteGameFlow() {
        wsUrl = "ws://localhost:$port/ws"
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date())
        testReport.appendLine("# Game System Test Report - $timestamp")
        testReport.appendLine("---")

        try {
            // 1. Create a new game
            testReport.appendLine("## 1. Game Creation")
            val response = restTemplate.postForEntity<Map<String, String>>("/api/games/create")
            val gameId = response.body?.get("gameId")

            testReport.appendLine("- Created game with ID: $gameId")
            logger.info("Created game with ID: $gameId")

            // 2. Setting up 5 player sessions
            testReport.appendLine("\n## 2. Player Setup")
            val playerNames = listOf("Alice", "Bob", "Charlie", "David", "Eve")
            val playerQueues = List(5) { LinkedBlockingQueue<Any>() }
            val messageStore = ConcurrentHashMap<Int, MutableList<Map<String, Any>>>()

            val playerSessions = playerNames.mapIndexed { i, name ->
                testReport.appendLine("- Setting up player $name (${i+1})")
                setupPlayerClient(gameId!!, playerQueues[i], messageStore, i)
            }

            // 3. Players Join
            testReport.appendLine("\n## 3. Player Joining")
            val playerIds = mutableListOf<String>()

            playerNames.forEachIndexed { i, name ->
                testReport.appendLine("- $name joining game")
                playerSessions[i].send("/app/game/join", ClientMessage.JoinGame(gameId!!, name))
                Thread.sleep(300)
            }

            Thread.sleep(1000)

            // Collect player IDs from game state
            val joinGameState = findLatestGameState(messageStore)
            if (joinGameState != null) {
                val players = joinGameState["players"] as List<*>
                playerIds.addAll(players.map { (it as Map<*, *>)["id"].toString() })

                testReport.appendLine("\nAssigned player IDs:")
                playerNames.forEachIndexed { i, name ->
                    testReport.appendLine("- $name: ${playerIds[i]}")
                }
            }

            // 4. Bidding Phase
            testReport.appendLine("\n## 4. Bidding Phase")

            // First player bids, others pass
            val winningBid = 160
            testReport.appendLine("- ${playerNames[0]} bids $winningBid")
            playerSessions[0].send("/app/game/bid", ClientMessage.PlaceBid(winningBid))
            Thread.sleep(500)

            for (i in 1..4) {
                testReport.appendLine("- ${playerNames[i]} passes")
                playerSessions[i].send("/app/game/bid", ClientMessage.PlaceBid(null))
                Thread.sleep(500)
            }

            Thread.sleep(1000)
            val biddingState = findLatestGameState(messageStore)
            testReport.appendLine("\nGame phase after bidding: ${biddingState?.get("phase")}")

            // 5. Trump Selection
            testReport.appendLine("\n## 5. Trump Selection")
            testReport.appendLine("- ${playerNames[0]} selects SPADES as trump")
            playerSessions[0].send("/app/game/trump", ClientMessage.SelectTrump(Suit.SPADES))
            Thread.sleep(500)

            // 6. Friend Card Selection
            testReport.appendLine("\n## 6. Friend Card Selection")

            val friendCards = listOf(
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.DIAMONDS, Rank.KING)
            )

            testReport.appendLine("- ${playerNames[0]} selects friend cards: HEARTS ACE and DIAMONDS KING")
            playerSessions[0].send("/app/game/friends", ClientMessage.SelectFriendCards(friendCards))
            Thread.sleep(1000)

            val afterFriendState = findLatestGameState(messageStore)
            testReport.appendLine("\nGame phase after friend selection: ${afterFriendState?.get("phase")}")

            // 7. Playing Cards
            testReport.appendLine("\n## 7. Trick Playing")
            val cardsToPlay = listOf(
                Card(Suit.SPADES, Rank.ACE),
                Card(Suit.SPADES, Rank.KING),
                Card(Suit.SPADES, Rank.QUEEN),
                Card(Suit.HEARTS, Rank.ACE),
                Card(Suit.DIAMONDS, Rank.ACE)
            )

            // Find out who starts
            val currentPlayerIdx = (afterFriendState?.get("currentPlayerIndex") as? Int) ?: 0
            testReport.appendLine("- First player to lead: ${playerNames[currentPlayerIdx]}")

            // Try to play cards one by one
            var cardsPlayed = 0
            for (i in 0 until 5) {
                val playerIdx = (currentPlayerIdx + i) % 5
                val card = cardsToPlay[i]

                testReport.appendLine("\n${playerNames[playerIdx]} attempting to play ${card.suit} ${card.rank}...")

                // We'll try different cards if the one we want isn't in hand
                var validCardFound = false
                val attemptCards = listOf(card) + generateFallbackCards()

                for (attemptCard in attemptCards) {
                    playerSessions[playerIdx].send("/app/game/play", ClientMessage.PlayCard(attemptCard))
                    Thread.sleep(500)

                    // Check if card was accepted by looking at current trick
                    val latestState = findLatestGameState(messageStore)
                    val currentTrick = latestState?.get("currentTrick") as? Map<*, *>
                    val plays = currentTrick?.get("plays") as? List<*> ?: emptyList<Any>()

                    if (plays.size > cardsPlayed) {
                        val playedCard = plays.last() as Map<*, *>
                        val cardInfo = (playedCard["card"] as? Map<*, *>)
                        val suit = cardInfo?.get("suit")
                        val rank = cardInfo?.get("rank")

                        testReport.appendLine("Card played: $suit $rank")
                        cardsPlayed = plays.size
                        validCardFound = true
                        break
                    }

                    // Also check if a trick was just completed (trick count increased)
                    val completedTricks = latestState?.get("completedTricks") as? Int ?: 0
                    if (completedTricks > 0) {
                        testReport.appendLine("Trick completed! ($completedTricks tricks total)")
                        validCardFound = true
                        break
                    }
                }

                if (!validCardFound) {
                    testReport.appendLine("Could not play a valid card for ${playerNames[playerIdx]}")
                }
            }

            // 8. Check Trick Completion
            Thread.sleep(1000)
            testReport.appendLine("\n## 8. Trick Results")

            // Find TrickComplete messages
            messageStore.values.flatten().forEach { message ->
                if (message["type"] == "TrickComplete") {
                    val winnerId = message["winnerId"] as? String
                    val points = message["points"] as? Int

                    val winnerIndex = playerIds.indexOf(winnerId)
                    val winnerName = if (winnerIndex >= 0) playerNames[winnerIndex] else "Unknown"

                    testReport.appendLine("- Trick won by: $winnerName (ID: $winnerId)")
                    testReport.appendLine("- Points: $points")
                }
            }

            // 9. Final Game State
            val finalState = findLatestGameState(messageStore)
            testReport.appendLine("\n## 9. Final Game State")
            testReport.appendLine("- Phase: ${finalState?.get("phase")}")
            testReport.appendLine("- Completed Tricks: ${finalState?.get("completedTricks")}")
            testReport.appendLine("- Caller Team Points: ${finalState?.get("callerTeamPoints")}")
            testReport.appendLine("- Opponent Team Points: ${finalState?.get("opponentTeamPoints")}")

            testReport.appendLine("\n## Test Result")
            testReport.appendLine("✅ Test Completed Successfully")

        } catch (e: Exception) {
            testReport.appendLine("\n## Error Occurred")
            testReport.appendLine("```")
            testReport.appendLine("${e.javaClass.name}: ${e.message}")
            e.stackTrace.take(10).forEach { testReport.appendLine("  at $it") }
            testReport.appendLine("```")

            logger.error("Test failed", e)
            throw e
        } finally {
            // Write report to file
            val reportFileName = "game_test_report_$timestamp.md"
            File("target/$reportFileName").writeText(testReport.toString())
            logger.info("Test report written to target/$reportFileName")
        }
    }

    private fun findLatestGameState(messageStore: Map<Int, List<Map<String, Any>>>): Map<*, *>? {
        // Look for the most recent GameStateUpdate message
        val allMessages = messageStore.values.flatten()
        val gameStates = allMessages.filter { it["type"] == "GameStateUpdate" }

        if (gameStates.isNotEmpty()) {
            val gameState = gameStates.last()["gameState"] as? Map<*, *>
            return gameState
        }

        return null
    }

    private fun generateFallbackCards(): List<Card> {
        // Generate all possible cards to try if our preferred card is not available
        return Suit.values().flatMap { suit ->
            Rank.values().map { rank ->
                Card(suit, rank)
            }
        }
    }

    private fun setupPlayerClient(
        gameId: String,
        queue: LinkedBlockingQueue<Any>,
        messageStore: ConcurrentHashMap<Int, MutableList<Map<String, Any>>>,
        playerIndex: Int
    ): StompSession {
        val transports = mutableListOf<Transport>(WebSocketTransport(StandardWebSocketClient()))
        val sockJsClient = SockJsClient(transports)
        val stompClient = WebSocketStompClient(sockJsClient)

        val converter = MappingJackson2MessageConverter()
        converter.objectMapper = objectMapper
        stompClient.messageConverter = converter

        val session = stompClient.connectAsync(
            wsUrl,
            WebSocketHttpHeaders(),
            object : StompSessionHandlerAdapter() {}
        ).get(5, TimeUnit.SECONDS)

        // Initialize message store for this player
        messageStore[playerIndex] = mutableListOf()

        val frameHandler = object : StompFrameHandler {
            override fun getPayloadType(headers: StompHeaders): Type = ByteArray::class.java

            override fun handleFrame(headers: StompHeaders, payload: Any?) {
                try {
                    if (payload is ByteArray) {
                        val rawJson = String(payload)
                        val message = objectMapper.readValue<Map<String, Any>>(rawJson)
                        queue.add(message)

                        // Store the message for later analysis
                        messageStore[playerIndex]?.add(message)
                    }
                } catch (e: Exception) {
                    logger.error("Error handling frame for player $playerIndex", e)
                }
            }
        }

        session.subscribe("/topic/game/$gameId", frameHandler)
        session.subscribe("/user/queue/game", frameHandler)
        session.subscribe("/user/queue/hand", frameHandler)
        return session
    }
}
