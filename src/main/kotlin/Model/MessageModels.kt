package Model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = ClientMessage.JoinGame::class, name = "JoinGame"),
    JsonSubTypes.Type(value = ClientMessage.PlaceBid::class, name = "PlaceBid"),
    JsonSubTypes.Type(value = ClientMessage.SelectTrump::class, name = "SelectTrump"),
    JsonSubTypes.Type(value = ClientMessage.SelectFriendCards::class, name = "SelectFriendCards"),
    JsonSubTypes.Type(value = ClientMessage.PlayCard::class, name = "PlayCard"),
    JsonSubTypes.Type(value = ClientMessage.RequestHand::class, name = "RequestHand")
)
sealed class ClientMessage {
    data class JoinGame(val gameId: String, val playerName: String) : ClientMessage()
    data class PlaceBid(val bid: Int?) : ClientMessage()
    data class SelectTrump(val trumpSuit: Suit) : ClientMessage()
    data class SelectFriendCards(val friendCards: List<Card>) : ClientMessage()
    data class PlayCard(val card: Card) : ClientMessage()
    data class RequestHand(val gameId: String) : ClientMessage()
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = ServerMessage.GameJoined::class, name = "GameJoined"),
    JsonSubTypes.Type(value = ServerMessage.PlayerJoined::class, name = "PlayerJoined"),
    JsonSubTypes.Type(value = ServerMessage.GameStateUpdate::class, name = "GameStateUpdate"),
    JsonSubTypes.Type(value = ServerMessage.HandUpdate::class, name = "HandUpdate"),
    JsonSubTypes.Type(value = ServerMessage.BidPlaced::class, name = "BidPlaced"),
    JsonSubTypes.Type(value = ServerMessage.TrickComplete::class, name = "TrickComplete"),
    JsonSubTypes.Type(value = ServerMessage.GameComplete::class, name = "GameComplete"),
    JsonSubTypes.Type(value = ServerMessage.Error::class, name = "Error")
)
sealed class ServerMessage {
    data class GameJoined(val gameId: String, val playerId: String, val playerName: String) : ServerMessage()
    data class PlayerJoined(val playerId: String, val playerName: String, val playerCount: Int) : ServerMessage()
    data class GameStateUpdate(val gameState: GameStateDTO) : ServerMessage()
    data class HandUpdate(val hand: List<Card>, val playerId: String? = null) : ServerMessage()
    data class BidPlaced(val playerId: String, val bid: Int?, val nextPlayerId: String?) : ServerMessage()
    data class TrickComplete(val winnerId: String, val points: Int, val nextPlayerId: String) : ServerMessage()
    data class GameComplete(val winner: Team, val callerPoints: Int, val opponentPoints: Int) : ServerMessage()
    data class Error(val message: String) : ServerMessage()
}
