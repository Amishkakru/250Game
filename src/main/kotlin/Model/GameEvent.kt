package Model

sealed class GameEvent {
    data class GameStarted(val gameId: String) : GameEvent()
    data class HandUpdated(val gameId: String) : GameEvent()
}
